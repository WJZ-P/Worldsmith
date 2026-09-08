package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.*
import java.nio.charset.StandardCharsets

/** One owner for portable content-addressed pack persistence. */
class ManagedPackStore(private val packDirectory:Path) {
    private val PACK_ID=Regex("[a-f0-9]{64}")
    fun managed(id: String): Path? {
        if (!PACK_ID.matches(id)) {
            return null
        }
        val directory = packDirectory.resolve(id).normalize()
        if (!directory.startsWith(packDirectory) || !Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            return null
        }
        return directory
    }

    fun persist(manifest: WorldsmithPackManifest, contents: Map<String, String>, binaries: Map<String,ByteArray> = emptyMap()): Path {
        Files.createDirectories(packDirectory)
        val target = packDirectory.resolve(manifest.id)
        if (Files.exists(target)) {
            verifyExistingTarget(target, manifest.id)
            return target
        }

        val pending = Files.createTempDirectory(packDirectory, ".pending-")
        try {
            writeUtf8(pending.resolve("worldsmith.json"), WorldsmithJson.encode(manifest))
            contents.forEach { (name, content) -> writeUtf8(pending.resolve(name), content) }
            binaries.forEach { (name, bytes) -> require(name.matches(Regex("drawings/[a-f0-9]{64}\\.wsdraw")));val p=pending.resolve(name);Files.createDirectories(p.parent);Files.write(p,bytes) }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, target)
            } catch (_: FileAlreadyExistsException) {
                verifyExistingTarget(target, manifest.id)
            }
        } finally {
            // Only our compiler-generated files live in pending. A successful
            // atomic move removes the directory; a failed write is cleaned up.
            if (Files.exists(pending)) {
                Files.walk(pending).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
        return target
    }

    private fun verifyExistingTarget(target: Path, expectedId: String) {
        require(Files.isDirectory(target) && !Files.isSymbolicLink(target)) {
            "Existing pack target is not a regular directory"
        }
        val existing = WorldsmithPackLoader.loadDirectory(target)
        require(existing.computedId == expectedId && existing.manifest.id == expectedId) {
            "Existing pack target does not contain the expected generation content"
        }
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

}
