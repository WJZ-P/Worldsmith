package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAsset
import com.wjz.worldsmith.core.content.ContentAssetValidation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Transient authoring result. Persistence must store the verified bytes before attaching its descriptor. */
data class TextureAsset(val bytes: ByteArray, val descriptor: ContentAsset, val width: Int, val height: Int)

/** Explicit PNG upload and deterministic pixel-grid authoring; no image-model capability is implied. */
object ContentTextureMcp {
    const val MAX_GRID_DIMENSION = 256
    const val MAX_PALETTE_COLORS = 256
    private const val MAX_BASE64_CHARS = ((ContentAssetValidation.MAX_ASSET_BYTES + 2) / 3) * 4
    private val COLOR = Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

    @JvmStatic
    fun upload(base64: String): TextureAsset {
        require(base64.length in 1..MAX_BASE64_CHARS) { "PNG upload exceeds the 4 MiB byte budget or is empty" }
        val bytes = try { Base64.getDecoder().decode(base64) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Upload must be raw standard base64 PNG data, without a data URL or whitespace")
        }
        return verified(bytes)
    }

    @JvmStatic
    fun pixels(palette: List<String>, rows: List<List<Int>>): TextureAsset {
        require(palette.size in 1..MAX_PALETTE_COLORS && palette.all { COLOR.matches(it) }) {
            "Palette requires 1..256 exact #RRGGBB or #RRGGBBAA colors"
        }
        require(rows.size in 1..MAX_GRID_DIMENSION) { "Pixel grid height must be 1..256" }
        val width = rows.first().size
        require(width in 1..MAX_GRID_DIMENSION && rows.all { it.size == width }) { "Pixel rows must form a rectangle 1..256 pixels wide" }
        require(rows.all { row -> row.all { it in palette.indices } }) { "Each pixel must be a zero-based palette index" }
        val colors = palette.map { text ->
            val value = text.substring(1).toLong(16)
            if (text.length == 7) (0xff000000L or value).toInt()
            else (((value and 0xff) shl 24) or (value ushr 8)).toInt()
        }
        val image = BufferedImage(width, rows.size, BufferedImage.TYPE_INT_ARGB)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, index -> image.setRGB(x, y, colors[index]) } }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "PNG encoder is not installed" }
        return verified(output.toByteArray())
    }

    @JvmStatic
    fun describe(asset: TextureAsset): JsonObject = buildJsonObject {
        put("asset", McpJson.encode(asset.descriptor))
        put("width", asset.width)
        put("height", asset.height)
        put("verified", true)
        put("persisted", false)
        put("nativeUsageValidated", false)
    }.also { verifyResult(asset) }

    @JvmStatic
    fun preview(asset: TextureAsset): McpImage {
        verifyResult(asset)
        return McpImage(Base64.getEncoder().encodeToString(asset.bytes), "image/png")
    }

    @JvmStatic
    fun result(asset: TextureAsset): McpToolResult = McpToolResult.success(describe(asset), images = listOf(preview(asset)))

    private fun verified(bytes: ByteArray): TextureAsset {
        val hash = ContentAssetValidation.hash(bytes)
        val descriptor = ContentAsset(hash, hash, "image/png", bytes.size.toLong(), ContentAssetValidation.path(hash))
        val size = ContentAssetValidation.verify(descriptor, bytes)
        return TextureAsset(bytes.copyOf(), descriptor, size.width, size.height)
    }

    private fun verifyResult(asset: TextureAsset) {
        require(asset.descriptor.id == asset.descriptor.sha256) { "Texture identity must equal its digest" }
        val size = ContentAssetValidation.verify(asset.descriptor, asset.bytes)
        require(size.width == asset.width && size.height == asset.height) { "Texture preview dimensions differ from verified bytes" }
    }
}
