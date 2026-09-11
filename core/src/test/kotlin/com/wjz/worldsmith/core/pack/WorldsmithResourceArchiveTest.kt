package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.content.WorldNarrativeBeat
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.drawhost.DrawingArtifact
import com.wjz.worldsmith.core.drawhost.DrawingSourceRecord
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class WorldsmithResourceArchiveTest {
    @TempDir lateinit var temp: Path
    private fun base() = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
    private fun freeze(pack: WorldsmithPack) = WorldContentBundleIO.encode(pack).let { pack.copy(manifest = it.manifest, computedId = it.manifest.id) }

    private fun assets(): WorldsmithPack {
        val base = base()
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val drawing = DrawStructure(Box.of(0, 0, 0, 0, 0, 0), listOf(DrawVoxel(Vec3i.ZERO, DrawBlock(BlockStateRef.parse("minecraft:stone")))), emptyMap())
        val dataHash = DrawSnapshotCodec.hash(DrawSnapshotCodec.encode(drawing))
        // Deliberately not valid Java: importing provenance must neither compile nor execute it.
        val sourceText = "archived provenance only; this is not executable Java"
        val sourceHash = ContentAssetValidation.hash(sourceText.toByteArray())
        val artifact = DrawingArtifact("d".repeat(64), dataHash, sourceHash, 0, emptyMap(), 4903, "archive", "archive", "archived_stone", 1)
        val source = DrawingSourceRecord(sourceHash, "NeverRun", mapOf("NeverRun.java" to sourceText))
        val structures = base.structures.copy(schemaVersion = 2, artifacts = mapOf(artifact.id to artifact), sources = mapOf(sourceHash to source), drawingAssets = mapOf(artifact.id to drawing))
        return WorldContentBundleIO.create("Archive fixture", "PNG, frozen drawing and inert source provenance", base.terrain, base.biomes, base.features,
            structures, base.theme, assets = mapOf(ContentAssetValidation.hash(png) to png))
    }

    private fun entries(pack: WorldsmithPack): List<Pair<String, ByteArray>> {
        val encoded = WorldContentBundleIO.encode(pack)
        return listOf("wspack.json" to """{"format":"worldsmith-resource-pack","containerVersion":1,"bundleId":"${encoded.manifest.id}"}""".toByteArray(),
            "worldsmith.json" to WorldsmithJson.encode(encoded.manifest).toByteArray()) +
            encoded.texts.map { (name, value) -> name to value.toByteArray() } + encoded.binaries.map { it.key to it.value }
    }

    private fun zip(files: List<Pair<String, ByteArray>>, name: String = "input.wspack", stored: Boolean = false): Path {
        val path = temp.resolve(name)
        ZipOutputStream(Files.newOutputStream(path), Charsets.UTF_8).use { zip ->
            files.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = entry.size; entry.crc = CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        return path
    }

    @Test fun `single file preserves nine modules PNG frozen drawing and inert provenance`() {
        val source = assets()
        val path = temp.resolve("world.wspack")
        val info = WorldsmithResourceArchive.write(source, path)
        val restored = WorldsmithResourceArchive.read(path)
        assertEquals(source.computedId, restored.pack.computedId)
        assertEquals(source.manifest.modules, restored.pack.manifest.modules)
        assertEquals(9, restored.pack.manifest.modules.size)
        assertEquals(source.structures.sources, restored.pack.structures.sources)
        assertArrayEquals(source.assets.values.single(), restored.pack.assets.values.single())
        assertArrayEquals(DrawSnapshotCodec.encode(source.structures.drawingAssets.values.single()), DrawSnapshotCodec.encode(restored.pack.structures.drawingAssets.values.single()))
        assertEquals(info, restored.info)
        assertEquals(Files.size(path), info.byteLength)
        assertEquals(entries(source).size, info.entryCount)
        ZipFile(path.toFile()).use { zip -> assertEquals(zip.entries().asSequence().sumOf { it.size }, info.uncompressedBytes) }
    }

    @Test fun `exports are byte deterministic and identical target is reusable`() {
        val pack = base()
        val first = temp.resolve("first.wspack"); val second = temp.resolve("second.wspack")
        val a = WorldsmithResourceArchive.write(pack, first)
        val b = WorldsmithResourceArchive.write(pack, second)
        assertEquals(a, b)
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        assertEquals(a, WorldsmithResourceArchive.write(pack, first))
    }

    @Test fun `legacy formats retain their own content identity`() {
        val base = base()
        for (version in listOf(3, 4)) {
            val names = if (version == 3) WorldContentBundleIO.LEGACY_MODULES else WorldContentBundleIO.FORMAT4_MODULES
            val source = freeze(base.copy(manifest = base.manifest.copy(formatVersion = version, modules = base.manifest.modules.filterKeys { it in names })))
            val path = temp.resolve("v$version.wspack")
            WorldsmithResourceArchive.write(source, path)
            val restored = WorldsmithResourceArchive.read(path).pack
            assertEquals(version, restored.manifest.formatVersion)
            assertEquals(source.computedId, restored.computedId)
        }
    }

    @Test fun `valid deflated ZIP with descriptors is also accepted`() {
        val pack = base()
        assertEquals(pack.computedId, WorldsmithResourceArchive.read(zip(entries(pack))).pack.computedId)
    }

    @Test fun `missing and undeclared members are rejected without extraction`() {
        val files = entries(base())
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(zip(files.filterNot { it.first == "terrain.json" })) }
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(zip(files + ("untracked.json" to "{}".toByteArray()))) }
        assertFalse(Files.exists(temp.resolve("terrain.json")))
    }

    @Test fun `traversal absolute backslash case and Windows device aliases are rejected`() {
        val files = entries(base())
        for (name in listOf("../outside.json", "/outside.json", "c:/outside.json", "folder\\outside.json", "THEME.json", "con.json", "nested/lpt1.json", "folder./outside.json", "folder//outside.json")) {
            assertThrows(Exception::class.java, { WorldsmithResourceArchive.read(zip(files + (name to "{}".toByteArray()))) }, name)
        }
        assertFalse(Files.exists(temp.parent.resolve("outside.json")))
    }

    @Test fun `duplicate central and local names are rejected`() {
        val path = zip(entries(base()) + listOf("extraa.json" to "{}".toByteArray(), "extrab.json" to "{}".toByteArray()), stored = true)
        val bytes = Files.readAllBytes(path)
        val before = "extrab.json".toByteArray(); val after = "extraa.json".toByteArray()
        for (i in 0..bytes.size - before.size) if (before.indices.all { bytes[i + it] == before[it] }) after.copyInto(bytes, i)
        Files.write(path, bytes)
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(path) }
    }

    @Test fun `encrypted flags malformed directory CRC mismatch and trailing data are rejected`() {
        val files = entries(base())
        val original = Files.readAllBytes(zip(files, stored = true))
        val encrypted = original.copyOf()
        val view = ByteBuffer.wrap(encrypted).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..encrypted.size - 46) when (view.getInt(i)) {
            0x04034b50 -> view.putShort(i + 6, (view.getShort(i + 6).toInt() or 1).toShort())
            0x02014b50 -> view.putShort(i + 8, (view.getShort(i + 8).toInt() or 1).toShort())
        }
        Files.write(temp.resolve("encrypted.wspack"), encrypted)
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(temp.resolve("encrypted.wspack")) }
        val corrupt = original.copyOf()
        val local = ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN)
        val data = 30 + (local.getShort(26).toInt() and 0xffff) + (local.getShort(28).toInt() and 0xffff)
        corrupt[data] = (corrupt[data].toInt() xor 1).toByte()
        Files.write(temp.resolve("crc.wspack"), corrupt)
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(temp.resolve("crc.wspack")) }
        Files.write(temp.resolve("trailing.wspack"), original + byteArrayOf(1, 2, 3))
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(temp.resolve("trailing.wspack")) }
        Files.write(temp.resolve("truncated.wspack"), original.copyOf(original.size - 8))
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(temp.resolve("truncated.wspack")) }
    }

    @Test fun `declared oversize and excessive entry count fail before inflation`() {
        val path = zip(entries(base()), stored = true)
        val bytes = Files.readAllBytes(path)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val central = (0..bytes.size - 46).first { data.getInt(it) == 0x02014b50 }
        data.putInt(central + 24, 4097) // First entry is the bounded 4 KiB container header.
        Files.write(path, bytes)
        val failure = assertThrows(IllegalArgumentException::class.java) { WorldsmithResourceArchive.read(path) }
        assertTrue(failure.message.orEmpty().contains("budget"))
        val excessive = entries(base()) + (0 until WorldsmithResourceArchive.MAX_ENTRIES).map { "extra/$it.json" to "{}".toByteArray() }
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(zip(excessive)) }
    }

    @Test fun `inner hash PNG drawing and cross references are fully validated`() {
        val source = assets()
        val files = entries(source)
        for (extension in listOf(".png", ".wsdraw")) {
            val corrupted = files.map { (name, bytes) -> name to if (name.endsWith(extension)) bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes }
            assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(zip(corrupted)) }
        }
        val alteredHeader = files.map { (name, bytes) -> name to if (name == "wspack.json") bytes.toString(Charsets.UTF_8).replace(source.computedId, "0".repeat(64)).toByteArray() else bytes }
        assertThrows(Exception::class.java) { WorldsmithResourceArchive.read(zip(alteredHeader)) }
        val missing = (0 until 32).map { ContentKey("block", "missing_${it}_" + "x".repeat(1024)) }
        val invalid = freeze(base().let { it.copy(theme = it.theme.copy(beats = listOf(WorldNarrativeBeat("dangling", "Dangling", "References absent assets", missing)))) })
        val error = assertThrows(IllegalArgumentException::class.java) { WorldsmithResourceArchive.read(zip(entries(invalid))) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("32 errors; showing 16"))
        assertEquals(16, Regex("CONTENT_REFERENCE_MISSING").findAll(message).count())
        assertTrue(message.length <= 8192, "Bad archive diagnostics must stay within the CLI/UI message budget")
    }

    @Test fun `different existing targets remain untouched and no temporary file leaks`() {
        val pack = base(); val target = temp.resolve("existing.wspack")
        WorldsmithResourceArchive.write(pack, target)
        val previous = Files.readAllBytes(target)
        val renamed = pack.copy(manifest = pack.manifest.copy(displayName = "Different archive metadata"))
        assertThrows(IllegalArgumentException::class.java) { WorldsmithResourceArchive.write(renamed, target) }
        assertArrayEquals(previous, Files.readAllBytes(target))
        Files.list(temp).use { files -> assertEquals(listOf(target), files.toList()) }
    }
}
