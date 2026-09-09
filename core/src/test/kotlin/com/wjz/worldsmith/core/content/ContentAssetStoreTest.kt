package com.wjz.worldsmith.core.content

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ContentAssetStoreTest {
    @TempDir lateinit var root: Path

    @Test fun `content addressed bytes survive reopening and callers never share mutable storage`() {
        val store = ContentAssetStore(root)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val asset = store.put(bytes, "application/octet-stream")
        bytes[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.read(asset))
        val returned = store.read(asset); returned[1] = 77
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ContentAssetStore(root).read(asset))
        assertEquals(asset, store.put(byteArrayOf(1, 2, 3, 4), "application/octet-stream"))
        assertEquals(1L, Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
    }

    @Test fun `corrupt immutable content is reported rather than replaced`() {
        val store = ContentAssetStore(root)
        val asset = store.put(byteArrayOf(1, 2, 3), "application/octet-stream")
        val path = root.resolve(asset.path!!)
        Files.write(path, byteArrayOf(3, 2, 1))
        assertThrows(IllegalArgumentException::class.java) { store.read(asset) }
        assertThrows(IllegalArgumentException::class.java) { store.put(byteArrayOf(1, 2, 3), "application/octet-stream") }
        assertArrayEquals(byteArrayOf(3, 2, 1), Files.readAllBytes(path))
    }

    @Test fun `budgets invalid handles and paths are rejected`() {
        val store = ContentAssetStore(root, 8)
        assertThrows(IllegalArgumentException::class.java) { store.put(ByteArray(9), "image/png") }
        assertThrows(IllegalArgumentException::class.java) { store.put(ByteArray(1), "PNG") }
        val asset = store.put(byteArrayOf(1), "image/png") // Byte storage does not assert image decodability.
        assertThrows(IllegalArgumentException::class.java) { store.read(asset.copy(path = "../outside")) }
        assertThrows(IllegalArgumentException::class.java) { store.read(asset.copy(sha256 = "../../bad")) }
        assertThrows(IllegalArgumentException::class.java) { store.read(asset.copy(byteLength = 2)) }
    }
}
