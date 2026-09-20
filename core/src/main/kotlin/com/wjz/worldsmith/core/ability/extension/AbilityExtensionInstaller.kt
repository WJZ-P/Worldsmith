package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.ability.AbilityPrograms
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.ByteBuffer
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class ExtensionPaths(directory: Path) {
    val root: Path
    init {
        val absolute = directory.toAbsolutePath().normalize()
        var ancestor: Path? = absolute
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "Extension storage must not contain symbolic-link ancestors." }
            ancestor = ancestor.parent
        }
        Files.createDirectories(absolute)
        root = absolute.toRealPath()
        // Resolve harmless Windows 8.3/casing aliases once. Every subsequent child is rooted
        // at this real path; symbolic-link ancestors above were rejected independently.
    }

    fun path(relative: String): Path {
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && path != root) { "Extension path escapes its controlled root." }
        var ancestor: Path? = path
        while (ancestor != null && ancestor.startsWith(root)) {
            require(!Files.isSymbolicLink(ancestor)) { "Extension storage path is a symbolic link." }
            if (Files.exists(ancestor, NOFOLLOW_LINKS)) require(ancestor.toRealPath().startsWith(root)) { "Extension storage path escapes its controlled root." }
            ancestor = ancestor.parent
        }
        return path
    }

    fun directory(relative: String): Path = path(relative).also { Files.createDirectories(it); path(relative) }

    fun write(relative: String, bytes: ByteArray) {
        val destination = path(relative)
        Files.createDirectories(destination.parent)
        path(relative)
        val temporary = Files.createTempFile(destination.parent, ".extension-", ".tmp")
        try {
            sync(temporary, bytes)
            path(relative)
            atomicMove(temporary, destination)
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        fun sync(path: Path, bytes: ByteArray) {
            Files.newByteChannel(path, StandardOpenOption.WRITE, StandardOpenOption.SYNC).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }
        fun atomicMove(from: Path, to: Path) { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    }
}

data class AbilityExtensionInstallResult(val receipt: AbilityExtensionInstallReceipt, val installedPath: String, val backupPath: String?)

/** Low-level trusted-host commit primitive. MCP must go through the service's UI-only approval boundary. */
class AbilityExtensionInstaller @JvmOverloads constructor(
    directory: Path,
    private val move: (Path, Path) -> Unit = { from, to -> ExtensionPaths.atomicMove(from, to) },
) {
    private val paths = ExtensionPaths(directory)
    val directory: Path get() = paths.root

    fun existingHash(id: String): String? {
        require(AbilityPrograms.validId(id)) { "Invalid extension id." }
        val file = paths.path("$id.jar")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= AbilityExtensionArtifacts.MAX_ARTIFACT_BYTES) { "Existing extension is not a bounded regular JAR." }
        return AbilityExtensionArtifacts.hash(Files.readAllBytes(file))
    }

    fun backupArtifact(id: String, artifactHash: String): Path {
        require(AbilityPrograms.validId(id) && artifactHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid rollback identity." }
        val artifactPath = paths.path("backups/$id/$artifactHash.jar")
        val artifact = AbilityExtensionArtifacts.inspect(artifactPath)
        require(artifact.manifest.id == id && artifact.artifactHash == artifactHash) { "Rollback artifact identity mismatch." }
        val receiptPath = paths.path("backups/$id/$artifactHash.approved.json")
        require(Files.isRegularFile(receiptPath, NOFOLLOW_LINKS) && Files.size(receiptPath) <= 65536) { "Rollback requires its preserved approval receipt." }
        val receipt = WorldsmithJson.decode<AbilityExtensionInstallReceipt>(Files.readString(receiptPath))
        require(receipt.apiVersion == 1 && receipt.id == id && receipt.sourceHash == artifact.manifest.sourceHash && receipt.artifactHash == artifactHash) { "Rollback approval receipt mismatch." }
        return artifactPath
    }

    fun backups(id: String): List<AbilityExtensionArtifact> {
        require(AbilityPrograms.validId(id)) { "Invalid extension id." }
        val directory = paths.path("backups/$id")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Invalid backup directory." }
        val files = Files.list(directory).use { it.limit(129).toList() }
        require(files.size <= 128) { "Rollback history exceeds the bounded 64-artifact capacity." }
        return files.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.jar")) }.sorted().map {
            AbilityExtensionArtifacts.inspect(backupArtifact(id, it.fileName.toString().removeSuffix(".jar")))
        }
    }

    @Synchronized fun install(candidate: Path, expected: AbilityExtensionArtifact, expectedExistingHash: String?, approvedAtMillis: Long): AbilityExtensionInstallResult {
        val verified = AbilityExtensionArtifacts.inspect(candidate)
        require(verified == expected) { "Candidate artifact changed after approval was requested." }
        val id = verified.manifest.id
        val currentHash = existingHash(id)
        require(currentHash == expectedExistingHash) { "Installed extension changed since the approval request; review and confirm again." }
        val target = paths.path("$id.jar")
        val receiptTarget = paths.path("$id.approved.json")
        val oldJar = if (currentHash == null) null else Files.readAllBytes(target)
        val oldReceipt = if (Files.exists(receiptTarget, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(receiptTarget, NOFOLLOW_LINKS) && Files.size(receiptTarget) <= 65536) { "Invalid existing approval receipt." }
            Files.readAllBytes(receiptTarget)
        } else null
        val backup = currentHash?.let { hash ->
            val relative = "backups/$id/$hash.jar"
            immutableBackup(relative, requireNotNull(oldJar))
            if (oldReceipt != null) immutableBackup("backups/$id/$hash.approved.json", oldReceipt)
            paths.path(relative).toString()
        }
        val receipt = AbilityExtensionInstallReceipt(id = id, sourceHash = verified.manifest.sourceHash, artifactHash = verified.artifactHash, approvedAtMillis = approvedAtMillis)
        val receiptBytes = WorldsmithJson.encode(receipt).toByteArray(Charsets.UTF_8)
        val candidateBytes = Files.readAllBytes(candidate)
        require(AbilityExtensionArtifacts.hash(candidateBytes) == verified.artifactHash) { "Candidate changed during installation preparation." }
        val jarTemporary = Files.createTempFile(paths.root, ".extension-jar-", ".tmp")
        val receiptTemporary = Files.createTempFile(paths.root, ".extension-receipt-", ".tmp")
        var jarCommitted = false
        var receiptCommitted = false
        try {
            ExtensionPaths.sync(jarTemporary, candidateBytes)
            ExtensionPaths.sync(receiptTemporary, receiptBytes)
            require(existingHash(id) == expectedExistingHash) { "Installed extension changed before commit." }
            paths.path("$id.jar"); paths.path("$id.approved.json")
            move(jarTemporary, target); jarCommitted = true
            move(receiptTemporary, receiptTarget); receiptCommitted = true
            require(existingHash(id) == verified.artifactHash && Files.readAllBytes(receiptTarget).contentEquals(receiptBytes)) { "Installed artifact/receipt verification failed." }
            return AbilityExtensionInstallResult(receipt, target.toString(), backup)
        } catch (failure: Exception) {
            if (jarCommitted) {
                try {
                    if (oldJar == null) Files.deleteIfExists(paths.path("$id.jar")) else paths.write("$id.jar", oldJar)
                    if (receiptCommitted) {
                        if (oldReceipt == null) Files.deleteIfExists(paths.path("$id.approved.json")) else paths.write("$id.approved.json", oldReceipt)
                    }
                } catch (rollback: Exception) {
                    throw IllegalStateException("Install failed and automatic rollback failed; preserved backup is $backup. Active hash/receipt must be checked before startup. ${rollback.message}", failure)
                }
            }
            throw failure
        } finally {
            Files.deleteIfExists(jarTemporary)
            Files.deleteIfExists(receiptTemporary)
        }
    }

    private fun immutableBackup(relative: String, bytes: ByteArray) {
        val file = paths.path(relative)
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= AbilityExtensionArtifacts.MAX_ARTIFACT_BYTES) { "Invalid existing rollback backup; it was preserved." }
            val existing = Files.readAllBytes(file)
            if (!existing.contentEquals(bytes) && relative.endsWith(".approved.json")) {
                val prior = WorldsmithJson.decode<AbilityExtensionInstallReceipt>(existing.toString(Charsets.UTF_8))
                val next = WorldsmithJson.decode<AbilityExtensionInstallReceipt>(bytes.toString(Charsets.UTF_8))
                require(prior.copy(approvedAtMillis = 0) == next.copy(approvedAtMillis = 0)) { "Existing rollback approval differs; it was preserved." }
            } else require(existing.contentEquals(bytes)) { "Existing rollback backup differs; it was preserved." }
        } else paths.write(relative, bytes)
    }
}
