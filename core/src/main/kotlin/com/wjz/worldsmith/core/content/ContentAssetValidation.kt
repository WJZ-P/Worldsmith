package com.wjz.worldsmith.core.content

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** The bundle accepts decoded, bounded PNG assets, not arbitrary bytes bearing a MIME label. */
object ContentAssetValidation {
    const val MAX_ASSETS = 256
    const val MAX_ASSET_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_DIMENSION = 2048
    const val MAX_TOTAL_PIXELS = 16L * 1024 * 1024
    data class PngSize(val width: Int, val height: Int)

    fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun path(sha256: String) = "assets/$sha256.png"

    fun verifyAll(descriptors: List<ContentAsset>, bytesById: Map<String, ByteArray>): Map<String, PngSize> {
        require(descriptors.size <= MAX_ASSETS && descriptors.map { it.id }.distinct().size == descriptors.size) { "Asset count exceeded or duplicate asset id" }
        require(descriptors.map { it.id }.toSet() == bytesById.keys) { "Asset descriptors and supplied bytes must match exactly" }
        require(descriptors.sumOf { it.byteLength ?: -MAX_TOTAL_BYTES } in 0..MAX_TOTAL_BYTES) { "Asset byte budget exceeded or byte length missing" }
        val sizes = descriptors.associate { asset -> asset.id to verify(asset, bytesById.getValue(asset.id)) }
        require(sizes.values.sumOf { it.width.toLong() * it.height } <= MAX_TOTAL_PIXELS) { "Decoded texture pixel budget exceeded" }
        return sizes
    }

    fun verify(asset: ContentAsset, bytes: ByteArray): PngSize {
        require(WorldContentRegistry.validName(asset.id) && WorldContentRegistry.SHA256.matches(asset.sha256)) { "Invalid asset identity" }
        require(asset.mediaType == "image/png" && asset.path == path(asset.sha256)) { "Only content-addressed PNG assets are installed" }
        require(bytes.size in 1..MAX_ASSET_BYTES && asset.byteLength == bytes.size.toLong()) { "Asset byte length mismatch or budget exceeded" }
        require(hash(bytes) == asset.sha256) { "Asset digest mismatch: ${asset.id}" }
        val size = pngSize(bytes)
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("PNG asset did not decode")
        require(image.width == size.width && image.height == size.height) { "Decoded PNG dimensions differ from header" }
        return size
    }

    /** Check dimensions before image allocation, and verify every chunk CRC including ancillary data. */
    private fun pngSize(bytes: ByteArray): PngSize {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        require(bytes.size >= 45 && bytes.take(8).toByteArray().contentEquals(signature)) { "Asset is not a PNG" }
        var position = 8
        var size: PngSize? = null
        var data = false
        var ended = false
        while (position < bytes.size) {
            require(bytes.size - position >= 12) { "Truncated PNG chunk" }
            val length = ByteBuffer.wrap(bytes, position, 4).int
            require(length >= 0 && length <= bytes.size - position - 12) { "Invalid PNG chunk length" }
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            require(type.matches(Regex("[a-zA-Z]{4}")) && type !in setOf("acTL", "fcTL", "fdAT")) { "Animated or invalid PNG chunks are not supported" }
            val crc = CRC32().apply { update(bytes, position + 4, length + 4) }.value
            val expected = ByteBuffer.wrap(bytes, position + 8 + length, 4).int.toLong() and 0xffffffffL
            require(crc == expected) { "PNG chunk checksum mismatch" }
            if (position == 8) require(type == "IHDR" && length == 13) { "PNG must begin with IHDR" }
            when (type) {
                "IHDR" -> {
                    require(size == null && length == 13) { "Duplicate or invalid PNG header" }
                    val w = ByteBuffer.wrap(bytes, position + 8, 4).int
                    val h = ByteBuffer.wrap(bytes, position + 12, 4).int
                    require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION) { "PNG dimensions exceed $MAX_DIMENSION" }
                    size = PngSize(w, h)
                }
                "IDAT" -> data = true
                "IEND" -> { require(length == 0 && data && position + 12 == bytes.size) { "Invalid PNG end or trailing data" }; ended = true }
            }
            position += length + 12
        }
        require(ended && size != null) { "Incomplete PNG" }
        return size
    }
}
