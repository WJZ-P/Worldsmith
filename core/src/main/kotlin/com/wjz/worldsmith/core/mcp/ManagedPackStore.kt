package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.*
import java.nio.charset.StandardCharsets

/** One owner for portable content-addressed pack persistence. */
class ManagedPackStore(packDirectory:Path) {
    private val packDirectory=packDirectory.toAbsolutePath().normalize()
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
        require(manifest.formatVersion==com.wjz.worldsmith.core.pack.WorldContentBundleIO.FORMAT_VERSION) { "New managed bundles must use the current writer format; legacy formats 3/4 are read-only" }
        return persistVerified(manifest,contents,binaries)
    }

    /** Import is preservation, not a legacy writer: retain the validated archive's original version and hash. */
    fun importValidated(pack:WorldsmithPack):Path {
        val diagnostics=WorldsmithPackValidator.validate(pack)
        require(diagnostics.none {it.severity==DiagnosticSeverity.ERROR}) {
            diagnostics.filter {it.severity==DiagnosticSeverity.ERROR}.take(16).joinToString("; ") {"${it.path}: ${it.message}"}
        }
        val bundle=WorldContentBundleIO.encode(pack)
        require(bundle.manifest.id==pack.manifest.id && pack.computedId==pack.manifest.id) {"Imported bundle identity must remain unchanged"}
        return persistVerified(pack.manifest,bundle.texts,bundle.binaries)
    }

    private fun persistVerified(manifest: WorldsmithPackManifest, contents: Map<String, String>, binaries: Map<String,ByteArray>): Path {
        com.wjz.worldsmith.core.pack.WorldContentBundleIO.validateManifest(manifest)
        require(PACK_ID.matches(manifest.id)) { "Invalid content address" }
        require(contents.keys.all { com.wjz.worldsmith.core.content.WorldContentRegistry.validRelativePath(it) && it.endsWith(".json") && it != "worldsmith.json" })
        val pngPaths=manifest.assets.map { requireNotNull(it.path) }.toSet()
        require(binaries.keys.all { it in pngPaths || it.matches(Regex("drawings/[a-f0-9]{64}\\.wsdraw")) })
        require(com.wjz.worldsmith.core.hash.WorldsmithHashUtil.finalizeManifest(manifest,contents,binaries).id==manifest.id) { "Bundle contents do not match their content address" }
        Files.createDirectories(packDirectory)
        val root=packDirectory.toRealPath()
        // Windows 8.3 aliases and casing canonicalize without being links. Compare canonical paths on both sides.
        require(Files.isDirectory(packDirectory,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(packDirectory) &&
            packDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS)==root) {"Managed pack root must be a regular, unlinked directory"}
        val target = root.resolve(manifest.id)
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingTarget(target, manifest.id)
            return target
        }

        val pending = Files.createTempDirectory(root, ".pending-")
        try {
            writeUtf8(pending.resolve("worldsmith.json"), WorldsmithJson.encode(manifest))
            contents.forEach { (name, content) -> writeUtf8(pending.resolve(name), content) }
            binaries.forEach { (name, bytes) -> val p=pending.resolve(name);Files.createDirectories(p.parent);Files.write(p,bytes) }
            verifyExistingTarget(pending,manifest.id)
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
