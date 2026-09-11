package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.content.WorldContentRegistry
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.HexFormat
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ResourceArchiveInfo(
    val archiveVersion: Int,
    val bundleId: String,
    val archiveSha256: String,
    val byteLength: Long,
    val entryCount: Int,
    val uncompressedBytes: Long,
)

data class ResourceArchiveRead(val pack: WorldsmithPack, val info: ResourceArchiveInfo)

/** A data-only, single-file envelope. Source provenance is preserved as text, never compiled or executed. */
object WorldsmithResourceArchive {
    const val VERSION = 1
    const val MAX_ENTRIES = 4096
    const val MAX_ARCHIVE_BYTES = 384L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 384L * 1024 * 1024
    private const val HEADER = "wspack.json"
    private const val MANIFEST = "worldsmith.json"
    private const val FORMAT = "worldsmith-resource-pack"
    private const val MAX_HEADER_BYTES = 4096
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private val FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)
    private val WINDOWS_DEVICES = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    @Serializable private data class Header(val format: String, val containerVersion: Int, val bundleId: String)
    private data class Entry(val name: String, val flags: Int, val method: Int, val crc: Long,
                             val compressed: Long, val size: Long, val offset: Long)

    /** No archive pathname is ever resolved onto the filesystem; only bounded byte buffers reach the loader. */
    @JvmStatic fun read(path: Path): ResourceArchiveRead {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Resource archive must be a regular, non-linked file" }
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val length = channel.size()
            require(length in 22..MAX_ARCHIVE_BYTES) { "Resource archive exceeds the compressed byte budget or is truncated" }
            val sha256 = hash(channel)
            val entries = inspectZip(channel)
            val buffers = linkedMapOf<String, ByteArray>()
            channel.position(0)
            val input = object : FilterInputStream(Channels.newInputStream(channel)) { override fun close() {} }
            ZipInputStream(input, Charsets.UTF_8).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                for (expected in entries) {
                    val actual = requireNotNull(zip.nextEntry) { "Missing local ZIP entry: ${expected.name}" }
                    require(!actual.isDirectory && actual.name == expected.name && actual.method == expected.method) { "ZIP local/central entry mismatch" }
                    val output = ByteArrayOutputStream(minOf(expected.size, 64 * 1024L).toInt())
                    var count = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        count += n; total += n
                        require(count <= expected.size && count <= entryLimit(expected.name) && total <= MAX_UNCOMPRESSED_BYTES) { "ZIP expansion exceeds its declared size or byte budget" }
                        output.write(buffer, 0, n)
                    }
                    zip.closeEntry() // The JDK checks the real inflater sizes and CRC, including data descriptors.
                    require(count == expected.size && actual.size == expected.size && actual.compressedSize == expected.compressed && actual.crc == expected.crc) { "ZIP entry size/CRC differs from its central directory" }
                    buffers[expected.name] = output.toByteArray()
                }
                require(zip.nextEntry == null) { "Untracked local ZIP entry" }
            }
            require(channel.size() == length && hash(channel) == sha256) { "Resource archive changed while being read" }
            val header = WorldsmithJson.decode<Header>(utf8(requireNotNull(buffers[HEADER]) { "Missing wspack.json container header" }))
            require(header.format == FORMAT && header.containerVersion == VERSION && WorldContentRegistry.SHA256.matches(header.bundleId)) { "Unsupported resource archive header" }
            val consumed = mutableSetOf<String>()
            val source = object : WorldsmithPackSource {
                private fun bytes(relativePath: String): ByteArray {
                    require(relativePath != HEADER && WorldContentRegistry.validRelativePath(relativePath)) { "Invalid inner bundle path" }
                    consumed += relativePath
                    return requireNotNull(buffers[relativePath]) { "Missing declared resource archive entry: $relativePath" }
                }
                override fun readText(relativePath: String): String {
                    require(relativePath.endsWith(".json")) { "Bundle text must be a JSON document" }
                    return utf8(bytes(relativePath))
                }
                override fun readBytes(relativePath: String): ByteArray = bytes(relativePath)
            }
            val pack = WorldsmithPackLoader.load(source)
            require(buffers.keys == consumed + HEADER) { "Resource archive contains undeclared entries: ${buffers.keys - consumed - HEADER}" }
            require(pack.manifest.id == header.bundleId && pack.computedId == header.bundleId) { "Resource archive and bundle content identities differ" }
            validate(pack)
            ResourceArchiveRead(pack, ResourceArchiveInfo(VERSION, header.bundleId, sha256, length, entries.size, entries.sumOf { it.size }))
        }
    }

    /** Validate a sibling temporary archive before publishing. An existing different file is never replaced. */
    @JvmStatic fun write(pack: WorldsmithPack, path: Path): ResourceArchiveInfo {
        validate(pack)
        val bundle = WorldContentBundleIO.encode(pack)
        require(bundle.manifest.id == pack.manifest.id && bundle.manifest.id == pack.computedId) { "Export requires a frozen, validated bundle identity" }
        val files = sortedMapOf<String, ByteArray>()
        fun add(name: String, bytes: ByteArray) {
            validateName(name)
            require(files.putIfAbsent(name, bytes) == null) { "Resource archive entry collision: $name" }
        }
        add(HEADER, WorldsmithJson.encode(Header(FORMAT, VERSION, bundle.manifest.id)).toByteArray(Charsets.UTF_8))
        add(MANIFEST, WorldsmithJson.encode(bundle.manifest).toByteArray(Charsets.UTF_8))
        bundle.texts.forEach { (name, value) -> add(name, value.toByteArray(Charsets.UTF_8)) }
        bundle.binaries.forEach { (name, bytes) -> add(name, bytes) }
        checkBudgets(files.map { (name, bytes) -> Entry(name, 0, ZipEntry.STORED, 0, bytes.size.toLong(), bytes.size.toLong(), 0) })
        val target = path.toAbsolutePath().normalize()
        Files.createDirectories(requireNotNull(target.parent))
        require(!Files.isSymbolicLink(target)) { "Resource archive target is a symbolic link" }
        val temporary = Files.createTempFile(target.parent, ".wspack-", ".tmp")
        try {
            // PNG/wsdraw are already compressed. STORED gives stable bytes across deflater/JDK versions.
            ZipOutputStream(Files.newOutputStream(temporary), Charsets.UTF_8).use { zip ->
                for ((name, bytes) in files) {
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                        setTimeLocal(FIXED_TIME)
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
            }
            val verified = read(temporary).info
            require(verified.bundleId == bundle.manifest.id) { "Resource archive readback changed its bundle identity" }
            fun reuse(): ResourceArchiveInfo {
                val existing = read(target).info
                require(existing.archiveSha256 == verified.archiveSha256) { "A different resource archive already exists at the target" }
                return existing
            }
            if (Files.exists(target, NOFOLLOW_LINKS)) return reuse()
            try { Files.move(temporary, target) } catch (_: FileAlreadyExistsException) { return reuse() }
            return verified
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validate(pack: WorldsmithPack) {
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        require(errors.isEmpty()) {
            fun bounded(value: String, limit: Int) = if (value.length <= limit) value else value.take(limit - 3) + "..."
            val shown = errors.take(16)
            val details = shown.joinToString("; ") {
                "${bounded(it.path, 128)} [${bounded(it.code, 64)}] ${bounded(it.message, 256)}"
            }
            "Invalid resource archive bundle (${errors.size} errors; showing ${shown.size}): $details"
        }
    }

    private fun validateName(name: String) {
        require(WorldContentRegistry.validRelativePath(name)) { "Archive paths must be normalized lowercase relative paths, without backslashes, empty segments or traversal: $name" }
        require(name.split('/').none { it.endsWith('.') || it.substringBefore('.') in WINDOWS_DEVICES }) { "Archive paths must not use Windows device names or trailing-dot aliases: $name" }
        require(name.endsWith(".json") || name.endsWith(".png") || name.endsWith(".wsdraw")) { "Unexpected resource archive file type: $name" }
    }

    private fun entryLimit(name: String): Long = when {
        name == HEADER -> MAX_HEADER_BYTES.toLong()
        name == MANIFEST -> MAX_MANIFEST_BYTES.toLong()
        name.endsWith(".json") -> WorldContentBundleIO.MAX_TEXT_BYTES.toLong()
        name.endsWith(".png") -> ContentAssetValidation.MAX_ASSET_BYTES.toLong()
        name.endsWith(".wsdraw") -> DrawSnapshotCodec.MAX_BYTES.toLong()
        else -> 0
    }

    private fun checkBudgets(entries: List<Entry>) {
        require(entries.size in 2..MAX_ENTRIES) { "Resource archive entry count exceeds $MAX_ENTRIES" }
        val names = mutableSetOf<String>()
        for (entry in entries) {
            validateName(entry.name)
            require(names.add(entry.name.lowercase(Locale.ROOT))) { "Duplicate or case-conflicting archive entry: ${entry.name}" }
            require(entry.size in 1..entryLimit(entry.name) && entry.compressed in 0..MAX_ARCHIVE_BYTES) { "Archive entry exceeds its file budget: ${entry.name}" }
        }
        require(HEADER in names && MANIFEST in names) { "Resource archive needs both container and bundle manifests" }
        require(entries.sumOf { it.size } <= MAX_UNCOMPRESSED_BYTES) { "Resource archive expansion budget exceeded" }
        require(entries.filter { it.name.endsWith(".json") && it.name != HEADER && it.name != MANIFEST }.sumOf { it.size } <= WorldContentBundleIO.MAX_TEXT_BYTES) { "Bundle JSON budget exceeded" }
        require(entries.filter { it.name.endsWith(".png") }.sumOf { it.size } <= ContentAssetValidation.MAX_TOTAL_BYTES) { "Bundle PNG budget exceeded" }
        require(entries.filter { it.name.endsWith(".wsdraw") }.sumOf { it.size } <= WorldContentBundleIO.MAX_DRAWING_BYTES) { "Bundle drawing budget exceeded" }
    }

    /** Strict ZIP32 framing rejects encrypted/split/ZIP64 archives, hidden members, overlapping data and aliases. */
    private fun inspectZip(channel: FileChannel): List<Entry> {
        val length = channel.size()
        val tailStart = maxOf(0, length - 22 - 65535)
        val tail = bytes(channel, tailStart, (length - tailStart).toInt())
        val end = (tail.limit() - 22 downTo 0).firstOrNull { i ->
            tail.getInt(i) == 0x06054b50 && i + 22 + u16(tail, i + 20) == tail.limit()
        } ?: throw IllegalArgumentException("Missing or malformed ZIP end directory")
        val count = u16(tail, end + 10)
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 && u16(tail, end + 8) == count && count in 2..MAX_ENTRIES) { "Split, ZIP64 or excessive-entry archives are not supported" }
        val centralSize = u32(tail, end + 12)
        val centralStart = u32(tail, end + 16)
        require(centralStart + centralSize == tailStart + end) { "ZIP directory offsets or trailing data are invalid" }
        val entries = ArrayList<Entry>(count)
        var cursor = centralStart
        repeat(count) {
            val h = bytes(channel, cursor, 46)
            require(h.getInt(0) == 0x02014b50 && u16(h, 6) <= 20 && u16(h, 34) == 0) { "Unsupported ZIP central directory record" }
            val flags = u16(h, 8); val method = u16(h, 10)
            require(flags and 0xf7f1 == 0 && method in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) { "Encrypted or unsupported ZIP entries are rejected" }
            val nameLength = u16(h, 28); val extraLength = u16(h, 30); val commentLength = u16(h, 32)
            require(nameLength in 1..512 && cursor + 46 + nameLength + extraLength + commentLength <= centralStart + centralSize) { "Invalid ZIP entry metadata lengths" }
            val name = utf8(bytes(channel, cursor + 46, nameLength).array())
            val unixType = (u32(h, 38) shr 16).toInt() and 0xf000
            require(unixType == 0 || unixType == 0x8000) { "Non-regular ZIP members are rejected" }
            require(u32(h, 38) and 0x10L == 0L) { "ZIP directory members are not used by this container" }
            checkExtra(bytes(channel, cursor + 46 + nameLength, extraLength))
            entries += Entry(name, flags, method, u32(h, 16), u32(h, 20), u32(h, 24), u32(h, 42))
            cursor += 46 + nameLength + extraLength + commentLength
        }
        require(cursor == centralStart + centralSize) { "Untracked ZIP central directory data" }
        checkBudgets(entries)
        val ordered = entries.sortedBy { it.offset }
        require(ordered.first().offset == 0L && ordered.map { it.offset }.distinct().size == ordered.size) { "ZIP preambles or overlapping local headers are rejected" }
        ordered.forEachIndexed { index, entry ->
            val next = ordered.getOrNull(index + 1)?.offset ?: centralStart
            val h = bytes(channel, entry.offset, 30)
            require(h.getInt(0) == 0x04034b50 && u16(h, 4) <= 20 && u16(h, 6) == entry.flags && u16(h, 8) == entry.method) { "ZIP local header differs from central directory" }
            val nameLength = u16(h, 26); val extraLength = u16(h, 28)
            require(nameLength in 1..512) { "Invalid ZIP local filename length" }
            require(utf8(bytes(channel, entry.offset + 30, nameLength).array()) == entry.name) { "ZIP local filename alias is rejected" }
            checkExtra(bytes(channel, entry.offset + 30 + nameLength, extraLength))
            val dataEnd = entry.offset + 30 + nameLength + extraLength + entry.compressed
            if (entry.flags and 8 == 0) {
                require(dataEnd == next && u32(h, 14) == entry.crc && u32(h, 18) == entry.compressed && u32(h, 22) == entry.size) { "ZIP local size, CRC or member boundary is invalid" }
            } else {
                val gap = next - dataEnd
                require(gap == 12L || gap == 16L) { "ZIP data descriptor boundary is invalid" }
                val descriptor = bytes(channel, dataEnd, gap.toInt())
                val offset = if (gap == 16L) { require(descriptor.getInt(0) == 0x08074b50); 4 } else 0
                require(u32(descriptor, offset) == entry.crc && u32(descriptor, offset + 4) == entry.compressed && u32(descriptor, offset + 8) == entry.size) { "ZIP descriptor differs from central directory" }
            }
        }
        return ordered
    }

    private fun checkExtra(extra: ByteBuffer) {
        var offset = 0
        while (offset < extra.limit()) {
            require(extra.limit() - offset >= 4) { "Malformed ZIP extra field" }
            val id = u16(extra, offset); val size = u16(extra, offset + 2)
            require(id != 0x0001 && id != 0x9901 && offset + 4 + size <= extra.limit()) { "ZIP64, AES or malformed ZIP extra fields are rejected" }
            offset += 4 + size
        }
    }

    private fun bytes(channel: FileChannel, position: Long, length: Int): ByteBuffer {
        require(position >= 0 && length >= 0 && position <= channel.size() - length) { "ZIP record points outside the archive" }
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var offset = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset)
            require(n > 0) { "Truncated ZIP record" }; offset += n
        }
        return buffer.flip()
    }

    private fun u16(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    private fun u32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    private fun hash(channel: FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var count = 0L
        while (true) {
            val n = channel.read(buffer)
            if (n < 0) break
            count += n; require(count <= MAX_ARCHIVE_BYTES) { "Resource archive grew beyond its compressed byte budget" }
            buffer.flip(); digest.update(buffer); buffer.clear()
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
