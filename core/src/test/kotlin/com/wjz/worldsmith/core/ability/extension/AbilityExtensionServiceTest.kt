package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.drawhost.DrawingRuntime
import com.wjz.worldsmith.core.mcp.AbilityExtensionMcpService
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class AbilityExtensionServiceTest {
    @TempDir lateinit var root: Path
    private fun runtime() = AbilityExtensionRuntime(
        DrawingRuntime(Path.of(requireNotNull(System.getProperty("worldsmith.workerRuntime"))), Path.of(System.getProperty("java.home"))),
        AbilityExtensionRuntime.currentClasspath(),
    )
    private fun await(service: AbilityExtensionService, job: AbilityExtensionJob): AbilityExtensionJob {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline) {
            val current = service.get(job.jobId)
            if (current.stage !in setOf(AbilityExtensionStage.QUEUED, AbilityExtensionStage.COMPILING, AbilityExtensionStage.STATIC_PROBING)) return current
            Thread.sleep(25)
        }
        return fail("Timed out waiting for controlled extension build: ${service.get(job.jobId)}")
    }
    private fun candidate(project: AbilityExtensionProject = AbilityExtensionMcpService.example(), marker: Byte = 1): Pair<Path, AbilityExtensionArtifact> {
        // Filesystem transaction tests do not claim ABI validation; their class payload is never loaded.
        val directory = Files.createTempDirectory(root, "artifact-fixture-")
        val classes = Files.createDirectories(directory.resolve("classes/example"))
        Files.write(classes.resolve("DoubleProvider.class"), byteArrayOf(marker))
        val (bytes, _) = AbilityExtensionArtifacts.build(project, directory.resolve("classes"))
        val path = directory.resolve("candidate.jar")
        Files.write(path, bytes)
        return path to AbilityExtensionArtifacts.inspect(path)
    }

    @Test fun `real ECJ static probe installation upgrade and rollback never initialize provider code`() {
        val approvals = mutableListOf<AbilityExtensionApproval>()
        val marker = root.resolve("provider-initialized.txt")
        val escapedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val base = AbilityExtensionMcpService.example()
        val source = base.sources.getValue("example/DoubleProvider.java").replace("public DoubleProvider() {}", """
            static { try { java.nio.file.Files.writeString(java.nio.file.Path.of("$escapedMarker"), "initialized"); } catch (Exception e) { throw new RuntimeException(e); } }
            public DoubleProvider() { throw new IllegalStateException("Constructor must not run during static probe/install"); }
        """.trimIndent())
        val firstProject = base.copy(sources = mapOf("example/DoubleProvider.java" to source))
        val installRoot = root.resolve("installed")
        val workRoot = root.resolve("work")
        AbilityExtensionService(workRoot, runtime(), installRoot, Consumer { approvals += it }).use { service ->
            val first = await(service, service.submit(firstProject))
            assertEquals(AbilityExtensionStage.READY, first.stage, first.message + "\n" + first.log)
            assertTrue(first.log.contains("STATIC_ABI_VERIFIED"))
            assertFalse(first.providerCodeExecuted)
            assertFalse(Files.exists(marker))
            val again = await(service, service.submit(firstProject.copy(requestId = "same-source-another-request")))
            assertEquals(first.sourceHash, again.sourceHash)
            assertEquals(first.artifactHash, again.artifactHash, "Request ids and compiler work paths do not change deterministic artifact bytes")
            service.requestInstall(first.jobId)
            assertEquals(1, approvals.size)
            assertEquals(first.sourceHash, approvals.single().sourceHash)
            assertEquals(first.artifactHash, approvals.single().artifactHash)
            assertNull(approvals.single().existingArtifactHash)
            assertFalse(Files.exists(installRoot.resolve("${first.projectId}.jar")))
            assertThrows(IllegalArgumentException::class.java) { service.approveInstall(first.jobId, "0".repeat(64), first.artifactHash!!) }
            val installed = service.approveInstall(first.jobId, first.sourceHash, first.artifactHash!!)
            assertEquals(AbilityExtensionStage.INSTALLED, installed.stage, installed.message)
            assertTrue(installed.restartRequired)
            assertFalse(installed.providerCodeExecuted)
            assertFalse(Files.exists(marker), "Even a confirmed install only copies bytes; startup is the first permitted initialization")
            val receipt = WorldsmithJson.decode<AbilityExtensionInstallReceipt>(Files.readString(installRoot.resolve("${first.projectId}.approved.json")))
            assertEquals(first.artifactHash, receipt.artifactHash)
            assertEquals(first.sourceHash, receipt.sourceHash)
            assertEquals(first.artifactHash, AbilityExtensionArtifacts.inspect(Path.of(installed.installedPath!!)).artifactHash)
            assertThrows(IllegalArgumentException::class.java) { service.approveInstall(first.jobId, first.sourceHash, first.artifactHash!!) }

            val secondProject = firstProject.copy(requestId = "v2", sources = firstProject.sources.mapValues { it.value.replace("getValue() * 2", "getValue() * 3") })
            val second = await(service, service.submit(secondProject))
            assertEquals(AbilityExtensionStage.READY, second.stage, second.message + "\n" + second.log)
            assertNotEquals(first.artifactHash, second.artifactHash)
            service.requestInstall(second.jobId)
            assertEquals(first.artifactHash, approvals.last().existingArtifactHash)
            val upgraded = service.approveInstall(second.jobId, second.sourceHash, second.artifactHash!!)
            assertEquals(AbilityExtensionStage.INSTALLED, upgraded.stage, upgraded.message)
            assertNotNull(upgraded.backupPath)
            assertEquals(first.artifactHash, AbilityExtensionArtifacts.inspect(Path.of(upgraded.backupPath!!)).artifactHash)
            assertEquals(listOf(first.artifactHash), service.backups(first.projectId).map { it.artifactHash })

            val rollback = await(service, service.prepareRollback(first.projectId, first.artifactHash!!, "rollback-v1"))
            assertEquals(AbilityExtensionStage.READY, rollback.stage, rollback.message + "\n" + rollback.log)
            assertEquals(first.artifactHash, rollback.artifactHash)
            assertTrue(rollback.log.contains("STATIC_ABI_VERIFIED"))
            assertEquals(second.artifactHash, AbilityExtensionArtifacts.inspect(installRoot.resolve("${first.projectId}.jar")).artifactHash)
            service.requestInstall(rollback.jobId)
            val restored = service.approveInstall(rollback.jobId, rollback.sourceHash, rollback.artifactHash!!)
            assertEquals(AbilityExtensionStage.INSTALLED, restored.stage, restored.message)
            assertEquals(first.artifactHash, AbilityExtensionArtifacts.inspect(installRoot.resolve("${first.projectId}.jar")).artifactHash)
            assertFalse(Files.exists(marker))
        }
    }

    @Test fun `compiler and static ABI failures remain failed candidates`() {
        AbilityExtensionService(root.resolve("compile-failure"), runtime()).use { service ->
            val example = AbilityExtensionMcpService.example()
            val syntax = await(service, service.submit(example.copy(sources = mapOf("example/DoubleProvider.java" to "this is not Java"))))
            assertEquals(AbilityExtensionStage.FAILED, syntax.stage)
            assertTrue(syntax.message.contains("compilation failed"))
            assertTrue(syntax.log.isNotBlank())
            assertTrue(syntax.log.length <= 65536)
            val wrongAbi = await(service, service.submit(example.copy(requestId = "wrong-abi", sources = mapOf("example/DoubleProvider.java" to "package example; public final class DoubleProvider { public DoubleProvider() {} }"))))
            assertEquals(AbilityExtensionStage.FAILED, wrongAbi.stage)
            assertTrue(wrongAbi.message.contains("Static ABI probe failed"))
            assertTrue(wrongAbi.log.contains("public concrete AbilityExtension"))
            assertNull(wrongAbi.artifactHash)
        }
    }

    @Test fun `project validation bounds sources paths metadata and protected providers before subprocess work`() {
        val example = AbilityExtensionMcpService.example()
        val bad = listOf(
            example.copy(id = "../escape"), example.copy(entryClasses = listOf("java.lang.Evil"), declaredSpecs = mapOf("java.lang.Evil" to example.declaredSpecs.values.single())),
            example.copy(sources = mapOf("../outside.java" to "x")), example.copy(sources = mapOf("C:/outside.java" to "x")),
            example.copy(sources = mapOf("example\\DoubleProvider.java" to "x")),
            example.copy(sources = mapOf("example/A.java" to "x", "example/a.java" to "x")),
            example.copy(sources = mapOf("example/DoubleProvider.java" to "x".repeat(AbilityExtensionArtifacts.MAX_SOURCE_BYTES + 1))),
            example.copy(declaredSpecs = emptyMap()), example.copy(entryClasses = List(17) { "example.Provider$it" }),
            example.copy(declaredSpecs = mapOf("example.DoubleProvider" to AbilityCapabilitySpec("combat.damage", arguments = emptyList()))),
        )
        bad.forEach { project -> assertThrows(IllegalArgumentException::class.java) { AbilityExtensionArtifacts.validate(project) } }
        assertEquals(AbilityExtensionArtifacts.sourceHash(example), AbilityExtensionArtifacts.sourceHash(example.copy(requestId = "different")))
        assertNotEquals(AbilityExtensionArtifacts.sourceHash(example), AbilityExtensionArtifacts.sourceHash(example.copy(name = "Changed metadata")))
    }

    @Test fun `timeouts terminate the bounded compiler process and produce no ready artifact`() {
        val process = HangingProcess()
        AbilityExtensionService(root.resolve("timeout"), runtime(), limits = AbilityExtensionLimits(1, 1, 64),
            launcher = AbilityExtensionProcessLauncher { _, _ -> process }).use { service ->
            val job = await(service, service.submit(AbilityExtensionMcpService.example()))
            assertEquals(AbilityExtensionStage.FAILED, job.stage)
            assertTrue(job.message.contains("compile exceeded 1s deadline"), job.message)
            assertTrue(process.killed)
            assertNull(job.artifactHash)
            val arguments = Files.readString(service.workDirectory.resolve("work/${job.jobId}/compile.args"))
            assertTrue(arguments.contains("org.eclipse.jdt.internal.compiler.batch.Main"))
            assertTrue(arguments.contains("-proc:none"))
            assertFalse(arguments.contains("javac"))
        }
    }

    @Test fun `second atomic commit failure restores old jar receipt and keeps verified backup`() {
        val (firstPath, first) = candidate()
        val secondProject = AbilityExtensionMcpService.example().let { it.copy(sources = it.sources.mapValues { entry -> entry.value + "\n// revision two" }) }
        val (secondPath, second) = candidate(secondProject, 2)
        val installed = root.resolve("atomic-install")
        AbilityExtensionInstaller(installed).install(firstPath, first, null, 1)
        val oldJar = Files.readAllBytes(installed.resolve("${first.manifest.id}.jar"))
        val oldReceipt = Files.readAllBytes(installed.resolve("${first.manifest.id}.approved.json"))
        val failing = AbilityExtensionInstaller(installed) { from, to ->
            if (to.fileName.toString().endsWith(".approved.json")) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected receipt failure")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        assertThrows(AtomicMoveNotSupportedException::class.java) { failing.install(secondPath, second, first.artifactHash, 2) }
        assertArrayEquals(oldJar, Files.readAllBytes(installed.resolve("${first.manifest.id}.jar")))
        assertArrayEquals(oldReceipt, Files.readAllBytes(installed.resolve("${first.manifest.id}.approved.json")))
        assertEquals(first.artifactHash, AbilityExtensionArtifacts.inspect(failing.backupArtifact(first.manifest.id, first.artifactHash)).artifactHash)
    }

    @Test fun `first atomic failure never falls back to non-atomic overwrite and stale predecessor is rejected`() {
        val (path, artifact) = candidate()
        val directory = root.resolve("first-move")
        val failing = AbilityExtensionInstaller(directory) { from, to -> throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected unsupported volume") }
        assertThrows(AtomicMoveNotSupportedException::class.java) { failing.install(path, artifact, null, 1) }
        assertFalse(Files.exists(directory.resolve("${artifact.manifest.id}.jar")))
        assertFalse(Files.exists(directory.resolve("${artifact.manifest.id}.approved.json")))
        val actual = AbilityExtensionInstaller(directory)
        actual.install(path, artifact, null, 1)
        assertThrows(IllegalArgumentException::class.java) { actual.install(path, artifact, null, 2) }
        assertEquals(artifact.artifactHash, actual.existingHash(artifact.manifest.id))
    }

    @Test fun `candidate source hash archive paths and archive size are checked before install`() {
        val (path, artifact) = candidate()
        Files.write(path, Files.readAllBytes(path) + byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionInstaller(root.resolve("tampered")).install(path, artifact, null, 1) }
        val oversized = root.resolve("oversized.jar")
        Files.write(oversized, ByteArray(AbilityExtensionArtifacts.MAX_ARTIFACT_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionArtifacts.inspect(oversized) }
        val traversal = root.resolve("traversal.jar")
        JarOutputStream(Files.newOutputStream(traversal)).use { jar ->
            for (name in listOf("../outside.class", AbilityExtensionArtifacts.MANIFEST, AbilityExtensionArtifacts.SOURCES, AbilityExtensionArtifacts.SERVICE)) {
                jar.putNextEntry(JarEntry(name)); jar.write(byteArrayOf(1)); jar.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionArtifacts.inspect(traversal) }
        assertFalse(Files.exists(root.resolve("outside.class")))
        val (originalPath, _) = candidate()
        val corrupt = root.resolve("wrong-source-hash.jar")
        java.util.zip.ZipFile(originalPath.toFile()).use { zip ->
            JarOutputStream(Files.newOutputStream(corrupt)).use { jar -> zip.entries().asSequence().forEach { entry ->
                jar.putNextEntry(JarEntry(entry.name))
                val bytes = zip.getInputStream(entry).readAllBytes()
                jar.write(if (entry.name == AbilityExtensionArtifacts.MANIFEST) bytes.toString(Charsets.UTF_8).replace(artifact.manifest.sourceHash, "0".repeat(64)).toByteArray() else bytes)
                jar.closeEntry()
            } }
        }
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionArtifacts.inspect(corrupt) }
    }

    @Test fun `symbolic roots and installed targets are rejected without touching outside data`() {
        val outside = Files.createDirectory(root.resolve("outside"))
        val link = root.resolve("linked-root")
        try { Files.createSymbolicLink(link, outside) } catch (failure: Exception) { assumeTrue(false, "Symbolic links unavailable: ${failure.message}") }
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionInstaller(link) }
        val (candidate, artifact) = candidate()
        val directory = Files.createDirectory(root.resolve("linked-target"))
        val outsideFile = Files.write(outside.resolve("untouched.jar"), byteArrayOf(9, 8, 7))
        Files.createSymbolicLink(directory.resolve("${artifact.manifest.id}.jar"), outsideFile)
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionInstaller(directory).install(candidate, artifact, null, 1) }
        assertArrayEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(outsideFile))
    }

    @Test fun `unavailable runtime never queues or installs and limits are explicit`() {
        AbilityExtensionService(root.resolve("unavailable")).use { service ->
            assertFalse(service.available)
            assertFalse(service.installationAvailable)
            assertThrows(IllegalStateException::class.java) { service.submit(AbilityExtensionMcpService.example()) }
            assertTrue(service.list().isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { AbilityExtensionService(root.resolve("invalid-limit"), limits = AbilityExtensionLimits(0, 1, 64)) }
    }

    private class HangingProcess : Process() {
        var killed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = if (killed) 1 else throw IllegalThreadStateException()
        override fun destroy() { killed = true }
        override fun destroyForcibly(): Process { killed = true; return this }
        override fun isAlive(): Boolean = !killed
    }
}
