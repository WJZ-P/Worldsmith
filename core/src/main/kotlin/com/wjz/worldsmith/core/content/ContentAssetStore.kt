package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.drawhost.DurableFiles
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Shared content-addressed bytes for future models/textures/sounds, separate from any renderer. */
class ContentAssetStore(root: Path, private val maxAssetBytes: Int = MAX_ASSET_BYTES) {
    private val root: Path
    init {
        require(maxAssetBytes in 1..MAX_ASSET_BYTES)
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root)) { "Asset root must not be a symbolic link" }
        this.root = root.toRealPath()
    }

    @Synchronized
    fun put(bytes: ByteArray, mediaType: String): ContentAsset {
        require(bytes.size <= maxAssetBytes) { "Asset exceeds the per-asset byte budget" }
        require(mediaType.length <= 128 && mediaType.matches(Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))) { "Use a normalized media type" }
        val frozen = bytes.copyOf()
        val digest = hash(frozen)
        val path = location(digest)
        Files.createDirectories(path.parent)
        require(path.parent.toRealPath().startsWith(root) && !Files.isSymbolicLink(path.parent)) { "Asset parent is outside the store" }
        val asset = ContentAsset(digest, digest, mediaType, frozen.size.toLong(), root.relativize(path).toString().replace('\\', '/'))
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            require(read(asset).contentEquals(frozen)) { "Existing immutable asset differs; file preserved" }
        } else {
            DurableFiles.write(path, frozen)
        }
        return asset
    }

    fun read(asset: ContentAsset): ByteArray {
        require(asset.id == asset.sha256 && WorldContentRegistry.SHA256.matches(asset.sha256)) { "Invalid stored asset handle" }
        val path = location(asset.sha256)
        require(asset.path == root.relativize(path).toString().replace('\\', '/')) { "Asset path differs from its content address" }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path.parent) && path.toRealPath().startsWith(root)) { "Asset is outside the store or not a regular file" }
        require(asset.byteLength != null && asset.byteLength in 0..maxAssetBytes.toLong() && Files.size(path) == asset.byteLength) { "Asset size mismatch" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxAssetBytes + 1) }
        require(bytes.size.toLong() == asset.byteLength && hash(bytes) == asset.sha256) { "Asset content hash mismatch" }
        return bytes
    }

    private fun location(hash: String): Path = root.resolve(hash.take(2)).resolve("$hash.blob")
    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    companion object { const val MAX_ASSET_BYTES = 64 * 1024 * 1024 }
}
