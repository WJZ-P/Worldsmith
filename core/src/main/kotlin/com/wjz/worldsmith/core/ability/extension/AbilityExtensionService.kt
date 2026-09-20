package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

fun interface AbilityExtensionProcessLauncher {
    fun launch(command: List<String>, directory: Path): Process
}

/**
 * Compile and statically probe candidates in bounded child processes. Installation approval is
 * deliberately a separate, non-MCP host-UI action; no provider code executes in this service.
 */
class AbilityExtensionService @JvmOverloads constructor(
    workDirectory: Path,
    private val runtime: AbilityExtensionRuntime? = null,
    installDirectory: Path? = null,
    private val requestApproval: Consumer<AbilityExtensionApproval> = Consumer { },
    private val limits: AbilityExtensionLimits = AbilityExtensionLimits(),
    private val launcher: AbilityExtensionProcessLauncher = AbilityExtensionProcessLauncher { command, directory ->
        ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
    },
) : AutoCloseable {
    private val paths = ExtensionPaths(workDirectory)
    private val installer = installDirectory?.let(::AbilityExtensionInstaller)
    private val jobs = linkedMapOf<String, AbilityExtensionJob>()
    private val projects = mutableMapOf<String, AbilityExtensionProject>()
    private val approvals = mutableMapOf<String, AbilityExtensionApproval>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "worldsmith-extension-builds").apply { isDaemon = true } }
    @Volatile private var closed = false
    val available: Boolean get() = runtime != null && !closed
    val installationAvailable: Boolean get() = installer != null && available
    val workDirectory: Path get() = paths.root

    init {
        require(limits.compileSeconds in 1..120 && limits.probeSeconds in 1..60 && limits.heapMiB in 64..512) { "Invalid extension process limits." }
        paths.directory("jobs"); paths.directory("sources"); paths.directory("artifacts"); paths.directory("work")
        runtime?.let { worker ->
            require(Files.isRegularFile(worker.worker.directory.resolve("ecj.jar"))) { "Bundled ECJ is missing from the configured DrawingRuntime." }
            require(worker.classpath.isNotEmpty() && (worker.classpath + worker.nativeCompilerClasspath).size <= 512) { "Configure a bounded SPI/probe classpath." }
            (worker.classpath + worker.nativeCompilerClasspath).forEach { require(Files.exists(it)) { "Missing configured extension classpath entry: $it" } }
            require(worker.worker.jvmArguments.size <= 64 && worker.worker.jvmArguments.all { it.length <= 4096 && '\n' !in it && '\r' !in it && '\u0000' !in it }) { "Invalid host-configured JVM arguments." }
        }
        val records = Files.list(paths.path("jobs")).use { stream -> stream.limit(65).sorted().toList() }
        require(records.size <= 64) { "Extension job history exceeds 64 records; files were preserved." }
        records.forEach { file ->
            require(file.fileName.toString().matches(Regex("[a-f0-9]{32}\\.json")) && Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 262144) { "Invalid extension job record; files were preserved." }
            val job = WorldsmithJson.decode<AbilityExtensionJob>(Files.readString(paths.path("jobs/${file.fileName}")))
            require(job.jobId + ".json" == file.fileName.toString() && job.sourceHash.matches(Regex("[a-f0-9]{64}"))) { "Extension job identity mismatch." }
            val recovered = when (job.stage) {
                AbilityExtensionStage.QUEUED, AbilityExtensionStage.COMPILING, AbilityExtensionStage.STATIC_PROBING -> job.copy(stage = AbilityExtensionStage.INTERRUPTED, message = "Host restarted; explicitly resubmit with a new requestId.")
                AbilityExtensionStage.WAITING_INSTALL_APPROVAL -> job.copy(stage = AbilityExtensionStage.READY, message = "Pending approval was not retained; request a fresh host-UI confirmation.")
                else -> job
            }
            jobs[job.jobId] = recovered
            if (recovered != job) save(recovered)
        }
    }

    @Synchronized fun submit(project: AbilityExtensionProject): AbilityExtensionJob {
        check(available) { "Ability extension compilation is unavailable on this host." }
        AbilityExtensionArtifacts.validate(project)
        val frozen = AbilityExtensionArtifacts.freeze(project)
        val sourceHash = AbilityExtensionArtifacts.sourceHash(frozen)
        jobs.values.firstOrNull { it.projectId == frozen.id && it.requestId == frozen.requestId }?.let {
            require(it.sourceHash == sourceHash) { "requestId is already bound to another source/declaration hash." }
            return it
        }
        check(jobs.size < 64) { "Extension build history capacity reached; preserve existing artifacts before pruning jobs." }
        val job = AbilityExtensionJob(UUID.randomUUID().toString().replace("-", ""), frozen.id, frozen.name, frozen.requestId, sourceHash,
            AbilityExtensionStage.QUEUED, message = "Queued for ECJ compilation with annotation processing disabled, then static ABI probe.", createdAtMillis = System.currentTimeMillis())
        val sourceRelative = "sources/$sourceHash.json"
        val sourceBytes = AbilityExtensionArtifacts.sourceBytes(frozen)
        val sourcePath = paths.path(sourceRelative)
        if (Files.exists(sourcePath, NOFOLLOW_LINKS)) require(Files.size(sourcePath) == sourceBytes.size.toLong() && Files.readAllBytes(sourcePath).contentEquals(sourceBytes)) { "Frozen source record was modified; it was preserved." }
        else paths.write(sourceRelative, sourceBytes)
        save(job); jobs[job.jobId] = job; projects[job.jobId] = frozen
        executor.submit { run(job.jobId) }
        return job
    }

    @Synchronized fun get(jobId: String): AbilityExtensionJob {
        require(jobId.matches(Regex("[a-f0-9]{32}"))) { "Invalid extension job id." }
        return requireNotNull(jobs[jobId]) { "Unknown extension job." }
    }

    @Synchronized fun list(): List<AbilityExtensionJob> = Collections.unmodifiableList(ArrayList(jobs.values))

    @Synchronized fun backups(id: String): List<AbilityExtensionArtifact> = requireNotNull(installer) { "Extension installer unavailable." }.backups(id)

    /** Select a preserved approved JAR, statically probe it again, then require a NEW UI approval. */
    @Synchronized fun prepareRollback(id: String, artifactHash: String, requestId: String): AbilityExtensionJob {
        check(installationAvailable) { "Extension rollback needs the native installer and configured compiler runtime." }
        val backup = requireNotNull(installer).backupArtifact(id, artifactHash)
        val artifact = AbilityExtensionArtifacts.inspect(backup)
        val project = AbilityExtensionArtifacts.readSource(backup).copy(requestId = requestId)
        AbilityExtensionArtifacts.validate(project)
        jobs.values.firstOrNull { it.projectId == id && it.requestId == requestId }?.let {
            require(it.artifactHash == artifactHash && it.sourceHash == artifact.manifest.sourceHash) { "Rollback requestId is already bound to another artifact." }
            return it
        }
        require(jobs.size < 64) { "Extension job history capacity reached." }
        val bytes = Files.readAllBytes(backup)
        storeFrozen("artifacts/$artifactHash.jar", bytes)
        storeFrozen("sources/${artifact.manifest.sourceHash}.json", AbilityExtensionArtifacts.sourceBytes(project))
        val job = AbilityExtensionJob(UUID.randomUUID().toString().replace("-", ""), id, project.name, requestId, artifact.manifest.sourceHash,
            AbilityExtensionStage.QUEUED, artifactHash = artifactHash, artifactBytes = artifact.bytes, manifest = artifact.manifest,
            message = "Rollback candidate selected; static ABI will be rechecked before a fresh installation approval.", createdAtMillis = System.currentTimeMillis())
        save(job); jobs[job.jobId] = job; projects[job.jobId] = AbilityExtensionArtifacts.freeze(project)
        executor.submit { run(job.jobId) }
        return job
    }

    @Synchronized fun requestInstall(jobId: String): AbilityExtensionJob {
        check(installationAvailable) { "Ability extension installation is unavailable; a native host with independent UI approval is required." }
        val job = get(jobId)
        if (job.stage == AbilityExtensionStage.WAITING_INSTALL_APPROVAL) return job
        require(job.stage == AbilityExtensionStage.READY || job.stage == AbilityExtensionStage.DENIED) { "Only a statically verified candidate can request installation." }
        val artifact = verifiedArtifact(job)
        val source = paths.path("sources/${job.sourceHash}.json")
        require(Files.isRegularFile(source, NOFOLLOW_LINKS) && Files.size(source) <= AbilityExtensionArtifacts.MAX_SOURCE_RECORD_BYTES) { "Frozen approval source is missing or oversized." }
        require(AbilityExtensionArtifacts.hash(Files.readAllBytes(source)) == job.sourceHash) { "Frozen approval source hash mismatch." }
        val approval = AbilityExtensionApproval(job.jobId, job.projectId, job.name, job.sourceHash, artifact.artifactHash,
            requireNotNull(installer).existingHash(job.projectId), artifact.manifest, source.toString(), artifactPath(job).toString())
        val waiting = job.copy(stage = AbilityExtensionStage.WAITING_INSTALL_APPROVAL, message = "Confirm this exact source/artifact hash in the host's independent extension-install UI. No provider code has executed.")
        save(waiting); jobs[jobId] = waiting; approvals[jobId] = approval
        try { requestApproval.accept(approval) }
        catch (failure: Exception) {
            approvals.remove(jobId)
            return update(jobId) { it.copy(stage = AbilityExtensionStage.READY, message = "Host approval UI was unavailable: ${(failure.message ?: "unknown error").take(384)}") }
        }
        return waiting
    }

    /** Host UI only; never exposed as a tool, request field, drawing approval, or persisted grant. */
    @Synchronized fun approveInstall(jobId: String, expectedSourceHash: String, expectedArtifactHash: String): AbilityExtensionJob {
        check(installationAvailable) { "Extension installer is unavailable." }
        val job = get(jobId)
        require(job.stage == AbilityExtensionStage.WAITING_INSTALL_APPROVAL) { "No pending independent installation approval." }
        val approval = requireNotNull(approvals[jobId]) { "Approval request expired; request a new confirmation." }
        require(approval.sourceHash == expectedSourceHash && approval.artifactHash == expectedArtifactHash) { "Approval hashes do not match the displayed candidate." }
        val artifact = verifiedArtifact(job)
        val frozenSource = paths.path("sources/${job.sourceHash}.json")
        require(Files.size(frozenSource) <= AbilityExtensionArtifacts.MAX_SOURCE_RECORD_BYTES && AbilityExtensionArtifacts.hash(Files.readAllBytes(frozenSource)) == expectedSourceHash) { "Source changed after the confirmation was displayed." }
        val installed = try { requireNotNull(installer).install(artifactPath(job), artifact, approval.existingArtifactHash, System.currentTimeMillis()) }
        catch (failure: Exception) {
            approvals.remove(jobId)
            return update(jobId) { it.copy(stage = AbilityExtensionStage.READY, message = "Installation failed; check the active artifact/receipt and preserved backup before retrying. ${(failure.message ?: "unknown error").take(512)}") }
        }
        approvals.remove(jobId)
        val result = job.copy(stage = AbilityExtensionStage.INSTALLED, message = "Approved JAR and receipt installed. Restart required; no running-world registration or provider execution occurred.",
            restartRequired = true, installedPath = installed.installedPath, backupPath = installed.backupPath, completedAtMillis = System.currentTimeMillis())
        // The independently verified JAR+receipt commit is authoritative even if the history write fails.
        val recorded = try { save(result); result } catch (failure: Exception) { result.copy(message = result.message + " Job-history persistence failed: ${(failure.message ?: "unknown error").take(256)}") }
        jobs[jobId] = recorded
        return recorded
    }

    @Synchronized fun denyInstall(jobId: String, expectedSourceHash: String, expectedArtifactHash: String): AbilityExtensionJob {
        val approval = requireNotNull(approvals[jobId]) { "No pending installation confirmation." }
        require(approval.sourceHash == expectedSourceHash && approval.artifactHash == expectedArtifactHash) { "Stale installation confirmation." }
        approvals.remove(jobId)
        return update(jobId) { it.copy(stage = AbilityExtensionStage.DENIED, message = "Installation declined in the host UI; candidate and source were preserved.") }
    }

    @Synchronized fun cancel(jobId: String): AbilityExtensionJob {
        val job = get(jobId)
        if (job.stage in terminal) return job
        approvals.remove(jobId)
        processes.remove(jobId)?.let(::stop)
        return update(jobId) { it.copy(stage = AbilityExtensionStage.CANCELLED, message = "Build/approval request cancelled; installed extensions were unchanged.") }
    }

    private fun run(jobId: String) {
        val log = StringBuilder()
        try {
            val runtime = requireNotNull(runtime)
            val project = synchronized(this) { requireNotNull(projects[jobId]) }
            val directory = paths.directory("work/$jobId")
            val classpath = (runtime.classpath + runtime.nativeCompilerClasspath).map { it.toAbsolutePath().normalize().toString() }.distinct().joinToString(java.io.File.pathSeparator)
            val before = get(jobId)
            val candidate = if (before.artifactHash != null) {
                verifiedArtifact(before)
                artifactPath(before)
            } else {
                stage(jobId, AbilityExtensionStage.COMPILING)
                val sources = paths.directory("work/$jobId/sources")
                val classes = paths.directory("work/$jobId/classes")
                project.sources.forEach { (name, source) -> paths.write("work/$jobId/sources/$name", source.toByteArray(Charsets.UTF_8)) }
                val compileArguments = listOf("-cp", runtime.worker.directory.resolve("ecj.jar").toAbsolutePath().toString(), "org.eclipse.jdt.internal.compiler.batch.Main",
                    "-proc:none", "-encoding", "UTF-8", "-source", "21", "-target", "21", "-classpath", classpath, "-d", classes.toString()) + project.sources.keys.sorted().map { sources.resolve(it).toString() }
                check(process(jobId, "compile", directory, compileArguments, limits.compileSeconds, log) == 0) { "ECJ compilation failed; see bounded compiler log." }
                val (bytes, _) = AbilityExtensionArtifacts.build(project, classes)
                val artifactHash = AbilityExtensionArtifacts.hash(bytes)
                val relative = "artifacts/$artifactHash.jar"
                val path = paths.path(relative)
                if (Files.exists(path, NOFOLLOW_LINKS)) require(Files.size(path) == bytes.size.toLong() && Files.readAllBytes(path).contentEquals(bytes)) { "Content-addressed artifact was modified; it was preserved." }
                else paths.write(relative, bytes)
                path
            }
            val artifact = AbilityExtensionArtifacts.inspect(candidate)
            val artifactHash = artifact.artifactHash
            val manifest = artifact.manifest
            require(artifact.manifest.sourceHash == AbilityExtensionArtifacts.sourceHash(project)) { "Artifact verification failed." }
            stage(jobId, AbilityExtensionStage.STATIC_PROBING)
            val resultPath = paths.path("work/$jobId/probe.json")
            val probeArguments = listOf("-cp", classpath, AbilityExtensionProbeMain::class.java.name, candidate.toString(), resultPath.toString())
            check(process(jobId, "static-probe", directory, probeArguments, limits.probeSeconds, log) == 0) { "Static ABI probe failed; constructors/spec()/invoke() were not run." }
            require(Files.isRegularFile(resultPath, NOFOLLOW_LINKS) && Files.size(resultPath) <= 65536) { "Static probe result is missing or oversized." }
            val probed = WorldsmithJson.decode<AbilityExtensionArtifact>(Files.readString(paths.path("work/$jobId/probe.json")))
            require(probed == artifact) { "Static probe identity does not match the hashed artifact." }
            synchronized(this) {
                if (!closed && jobs.getValue(jobId).stage !in terminal) update(jobId) { it.copy(stage = AbilityExtensionStage.READY, artifactHash = artifactHash,
                    artifactBytes = artifact.bytes, manifest = manifest, log = log.toString(), completedAtMillis = System.currentTimeMillis(),
                    message = "ECJ compilation and static ABI checks passed. Provider code, static initializers, constructors, spec() and invoke() were not executed. Request independent host-UI installation approval.") }
            }
        } catch (failure: Exception) {
            synchronized(this) {
                if (!closed && jobs[jobId]?.stage !in terminal) runCatching { update(jobId) { it.copy(stage = AbilityExtensionStage.FAILED,
                    message = (failure.message ?: failure.javaClass.simpleName).take(1024), log = log.toString(), completedAtMillis = System.currentTimeMillis()) } }
            }
        } finally { synchronized(this) { projects.remove(jobId) }; processes.remove(jobId)?.let(::stop) }
    }

    private fun process(jobId: String, phase: String, directory: Path, arguments: List<String>, seconds: Long, log: StringBuilder): Int {
        val runtime = requireNotNull(runtime)
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = runtime.worker.javaHome.resolve("bin").resolve(if (windows) "java.exe" else "java")
        require(Files.isRegularFile(java)) { "Configured Java runtime is unavailable." }
        val launcherArguments = listOf("-Xmx${limits.heapMiB}m", "-Dfile.encoding=UTF-8") + runtime.worker.jvmArguments + arguments
        val argumentFile = "work/$jobId/$phase.args"
        val encodedArguments = launcherArguments.joinToString("\n") { argument ->
            require('\n' !in argument && '\r' !in argument && '\u0000' !in argument) { "Invalid process argument." }
            "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        paths.write(argumentFile, encodedArguments.toByteArray(Charsets.UTF_8))
        val process = launcher.launch(listOf(java.toString(), "@" + paths.path(argumentFile)), directory)
        synchronized(this) {
            if (closed || jobs.getValue(jobId).stage in terminal) { stop(process); error("Extension build was cancelled.") }
            processes[jobId] = process
        }
        val pump = Thread({
            runCatching { process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                while (true) { val read = reader.read(buffer); if (read < 0) break; synchronized(log) { if (log.length < 65536) log.append(buffer, 0, minOf(read, 65536 - log.length)) } }
            } }
        }, "worldsmith-extension-$phase-log").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(seconds, TimeUnit.SECONDS)) { stop(process); "$phase exceeded ${seconds}s deadline; process was terminated." }
            pump.join(2000)
            return process.exitValue()
        } finally { processes.remove(jobId, process); if (process.isAlive) stop(process); pump.join(2000) }
    }

    @Synchronized private fun stage(jobId: String, stage: AbilityExtensionStage) {
        check(!closed && get(jobId).stage !in terminal) { "Extension build was cancelled/interrupted." }
        update(jobId) { it.copy(stage = stage, message = stage.name.lowercase()) }
    }

    private fun artifactPath(job: AbilityExtensionJob): Path {
        val hash = requireNotNull(job.artifactHash) { "Candidate has no compiled artifact." }
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return paths.path("artifacts/$hash.jar")
    }
    private fun verifiedArtifact(job: AbilityExtensionJob): AbilityExtensionArtifact = AbilityExtensionArtifacts.inspect(artifactPath(job)).also {
        require(it.artifactHash == job.artifactHash && it.manifest.sourceHash == job.sourceHash && it.manifest == job.manifest && it.manifest.id == job.projectId && it.manifest.name == job.name) { "Candidate identity changed after static verification." }
    }
    private fun storeFrozen(relative: String, bytes: ByteArray) {
        val existing = paths.path(relative)
        if (Files.exists(existing, NOFOLLOW_LINKS)) require(Files.isRegularFile(existing, NOFOLLOW_LINKS) && Files.size(existing) == bytes.size.toLong() && Files.readAllBytes(existing).contentEquals(bytes)) { "Content-addressed file was modified; it was preserved." }
        else paths.write(relative, bytes)
    }
    @Synchronized private fun update(jobId: String, transform: (AbilityExtensionJob) -> AbilityExtensionJob): AbilityExtensionJob {
        val next = transform(get(jobId)); save(next); jobs[jobId] = next; return next
    }
    private fun save(job: AbilityExtensionJob) = paths.write("jobs/${job.jobId}.json", WorldsmithJson.encode(job).toByteArray(Charsets.UTF_8))

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        approvals.clear()
        processes.values.forEach(::stop)
        executor.shutdownNow()
        jobs.values.filter { it.stage !in terminal && it.stage != AbilityExtensionStage.READY }.toList().forEach { job ->
            runCatching { update(job.jobId) { it.copy(stage = if (it.stage == AbilityExtensionStage.WAITING_INSTALL_APPROVAL) AbilityExtensionStage.READY else AbilityExtensionStage.INTERRUPTED,
                message = "Host stopped; approval grants were discarded. Request a fresh confirmation for ready candidates.") } }
        }
    }

    companion object {
        private val terminal = setOf(AbilityExtensionStage.INSTALLED, AbilityExtensionStage.DENIED, AbilityExtensionStage.FAILED, AbilityExtensionStage.CANCELLED, AbilityExtensionStage.INTERRUPTED)
        private fun stop(process: Process) {
            runCatching { process.toHandle().descendants().toList().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }
}
