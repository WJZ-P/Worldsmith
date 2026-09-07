package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import com.wjz.worldsmith.core.draw.DrawStructure
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Explicitly approved authoring jobs. No calls from pack loading or chunk generation. */
class DrawingHost @JvmOverloads constructor(
    root: Path,
    private val runtime: DrawingRuntime? = null,
    private val dataVersion: Int = 0,
    private val requestApproval: Consumer<String> = Consumer {},
    private val limits: DrawingExecutionLimits = DrawingExecutionLimits(),
    /**
     * Runs a session's source without asking the host first.
     *
     * Deciding here rather than inside the approval listener keeps the choice
     * off the UI thread and avoids taking a second lock while [submit] holds
     * this one. Still not exposed over MCP: only the embedder sets it.
     */
    private val autoApprove: Boolean = false,
) : AutoCloseable {
    val root: Path = root.toAbsolutePath().normalize()
    private val jobs = linkedMapOf<String,DrawingJob>()
    private val approved = mutableSetOf<String>()
    private val requestedApproval = mutableSetOf<String>()
    private val processes = ConcurrentHashMap<String,Process>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r,"worldsmith-drawing-jobs").apply { isDaemon=true } }
    private var closed = false
    private val recoveryProblems = mutableListOf<DrawingDiagnostic>()
    val recoveryDiagnostics: List<DrawingDiagnostic> get() = recoveryProblems.toList()
    val available: Boolean get() = runtime != null

    init {
        require(limits.compileSeconds > 0 && limits.drawSeconds > 0 && limits.heapMiB in 64..1024)
        Files.createDirectories(this.root.resolve("jobs")); Files.createDirectories(this.root.resolve("artifacts")); Files.createDirectories(this.root.resolve("sources"))
        Files.list(this.root.resolve("jobs")).use { paths ->
            val records=paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
            require(records.size<=256) { "Drawing history exceeds 256 records; all files were preserved. Archive completed records before reopening the host" }
            records.forEach { p -> runCatching {
                require(!Files.isSymbolicLink(p) && Files.size(p)<=8*1024*1024) { "Invalid job record path/size" }
                val job=WorldsmithJson.decode<DrawingJob>(Files.readString(p))
                require(job.id.matches(Regex("[a-f0-9]{32}")) && job.id+".json"==p.fileName.toString())
                require(job.sessionId.matches(Regex("[a-f0-9]{32}")) && job.revision>0)
                validate(job.request)
                require(job.inputHash==inputHash(job.request)) { "Job input hash mismatch" }
                require(job.drawingIds.size<=8 && job.drawingIds.all { it.matches(Regex("[a-f0-9]{64}")) })
                val restored=if(job.stage in TERMINAL)job else job.copy(stage=DrawingJobStage.INTERRUPTED,message="Client/bridge restarted; resume the session and explicitly retry")
                jobs[job.id]=restored; save(restored)
            }.onFailure { failure -> recoveryProblems+=DrawingDiagnostic(file=p.fileName.toString(),message="Preserved unreadable job record: ${failure.message}; repair or archive it before submitting new revisions") } }
        }
    }

    @Synchronized fun submit(sessionId: String, input: DrawingRequest): DrawingJob {
        check(!closed && available) { "Drawing worker is not available on this host" }
        check(recoveryProblems.isEmpty()) { recoveryProblems.joinToString("; ") { "${it.file}: ${it.message}" } }
        require(sessionId.matches(Regex("[a-f0-9]{32}"))) { "Invalid drawing session" }
        validate(input)
        val request=input.copy(sources=input.sources.toSortedMap(),parameters=input.parameters.toSortedMap())
        val hash=inputHash(request)
        jobs.values.firstOrNull { it.sessionId==sessionId && it.request.requestId==request.requestId }?.let {
            require(it.inputHash==hash) { "requestId was already used for different source/input" }; return it
        }
        check(jobs.size < 256) { "Drawing history capacity reached; preserve/export drafts before cleaning the authoring directory" }
        val revision=(jobs.values.filter { it.sessionId==sessionId && it.request.name==request.name }.maxOfOrNull { it.revision } ?: 0)+1
        val ready=autoApprove||sessionId in approved
        val job=DrawingJob(UUID.randomUUID().toString().replace("-",""),sessionId,revision,hash,request,
            if(ready) DrawingJobStage.QUEUED else DrawingJobStage.WAITING_APPROVAL,
            message=if(ready) "Queued" else "Confirm this world's source-execution session inside Minecraft")
        jobs[job.id]=job; save(job)
        if(ready)executor.submit { run(job.id) }
        else if(requestedApproval.add(sessionId))requestApproval.accept(sessionId)
        return job
    }

    /** Host UI only. Deliberately not exposed by MCP. Approval is never persisted. */
    @Synchronized fun approve(sessionId: String) {
        if(closed)return
        approved+=sessionId
        jobs.values.filter { it.sessionId==sessionId && it.stage==DrawingJobStage.WAITING_APPROVAL }.toList().forEach {
            update(it.id) { j->j.copy(stage=DrawingJobStage.QUEUED,message="Queued after session confirmation") }
            executor.submit { run(it.id) }
        }
    }
    @Synchronized fun deny(sessionId: String) {
        requestedApproval.remove(sessionId)
        jobs.values.filter { it.sessionId==sessionId && it.stage==DrawingJobStage.WAITING_APPROVAL }.toList().forEach { cancel(sessionId,it.id) }
    }
    @Synchronized fun get(sessionId: String,id: String): DrawingJob {
        val job=jobs[id] ?: error("Unknown drawing job"); require(job.sessionId==sessionId) { "Job belongs to another session" }; return job
    }
    @Synchronized fun list(sessionId: String): List<DrawingJob> = jobs.values.filter { it.sessionId==sessionId }.toList()
    @Synchronized fun cancel(sessionId: String,id: String): DrawingJob {
        val job=get(sessionId,id)
        if(job.stage !in TERMINAL) {
            update(id) { it.copy(stage=DrawingJobStage.CANCELLED,message="Cancelled by caller") }
            processes[id]?.let(::stop)
        }
        return jobs.getValue(id)
    }
    @Synchronized private fun update(id: String, change: (DrawingJob)->DrawingJob): DrawingJob {
        val next=change(jobs.getValue(id)); jobs[id]=next; save(next); return next
    }
    @Synchronized private fun stage(id: String, value: DrawingJobStage) {
        check(!closed && jobs.getValue(id).stage !in TERMINAL) { "Job was interrupted/cancelled" }
        update(id) { it.copy(stage=value,message=value.name.lowercase()) }
    }

    fun artifact(sessionId: String,id: String,allowPrevious: Boolean=false): DrawingArtifact {
        require(sessionId.matches(Regex("[a-f0-9]{32}"))) { "Invalid drawing session" }
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid drawing id" }
        val artifact=WorldsmithJson.decode<DrawingArtifact>(Files.readString(root.resolve("owners/$sessionId/$id.json")))
        require(artifact.id==id && artifact.sessionId==sessionId) { "Drawing belongs to another session" }
        require(artifact.id==DrawSnapshotCodec.hash(WorldsmithJson.encode(artifact.copy(id="",sessionId="",jobId="",revision=0)).toByteArray(StandardCharsets.UTF_8))) { "Drawing metadata hash mismatch" }
        synchronized(this) {
            // Identical content can be produced by several revisions. A cancelled/failed
            // revision must not revoke a previous successful owner's frozen result.
            val owner=jobs.values.filter { it.sessionId==sessionId && it.stage==DrawingJobStage.SUCCEEDED && id in it.drawingIds }.maxByOrNull { it.revision }
            require(owner!=null) { "Drawing build did not complete successfully" }
            val latest=jobs.values.filter { it.sessionId==sessionId && it.request.name==artifact.name }.maxByOrNull { it.revision }
            require(allowPrevious || latest?.id==owner.id && latest.stage==DrawingJobStage.SUCCEEDED) {
                "Drawing is from an older revision; finish the newest build or explicitly set allowPreviousRevision=true"
            }
            return artifact.copy(jobId=owner.id,revision=owner.revision)
        }
    }
    fun bytes(artifact: DrawingArtifact): ByteArray {
        val path=root.resolve("artifacts/${artifact.id}.wsdraw")
        require(Files.size(path)<=DrawSnapshotCodec.MAX_BYTES && !Files.isSymbolicLink(path))
        return Files.readAllBytes(path).also { require(DrawSnapshotCodec.hash(it)==artifact.dataHash) { "Drawing content hash mismatch" } }
    }
    fun drawing(artifact: DrawingArtifact): DrawStructure = DrawSnapshotCodec.decode(bytes(artifact))
    fun source(artifact: DrawingArtifact): DrawingSourceRecord = WorldsmithJson.decode(Files.readString(root.resolve("sources/${artifact.sourceHash}.json")))

    private fun run(id: String) {
        val job=synchronized(this) { jobs.getValue(id) }
        val log=StringBuilder()
        try {
            stage(id,DrawingJobStage.COMPILING)
            val worker=runtime ?: error("Worker unavailable")
            val directory=root.resolve("work/$id"); Files.createDirectories(directory.resolve("sources"))
            for((name,source) in job.request.sources) {
                val file=directory.resolve("sources").resolve(name).normalize(); require(file.startsWith(directory.resolve("sources")))
                Files.createDirectories(file.parent); Files.writeString(file,source,StandardCharsets.UTF_8)
            }
            val properties=Properties().apply {
                setProperty("sdk",worker.directory.resolve("draw-sdk.jar").toString()); setProperty("entryClass",job.request.entryClass)
                setProperty("seeds",job.request.seeds.joinToString(",")); job.request.parameters.forEach { (k,v)->setProperty("param.$k",v) }
            }
            Files.newBufferedWriter(directory.resolve("request.properties"),StandardCharsets.UTF_8).use { properties.store(it,null) }
            val compile=process(id,"compile",directory,limits.compileSeconds,log)
            val diagnostics=readDiagnostics(directory)
            if(compile!=0) {
                throw BuildFailure("Java compilation failed (exit $compile)",diagnostics.ifEmpty { listOf(DrawingDiagnostic(message=log.toString())) })
            }
            stage(id,DrawingJobStage.DRAWING)
            val generated=process(id,"generate",directory,limits.drawSeconds,log)
            check(generated==0 && Files.isRegularFile(directory.resolve("done")) && Files.readString(directory.resolve("done")).trim()==job.request.seeds.size.toString()) {
                "Drawing worker exited without complete results (exit $generated)"
            }
            stage(id,DrawingJobStage.VALIDATING)
            val sourceBase=DrawingSourceRecord("",job.request.entryClass,job.request.sources)
            val source=sourceBase.copy(hash=DrawSnapshotCodec.hash(WorldsmithJson.encode(sourceBase).toByteArray(StandardCharsets.UTF_8)))
            atomic(root.resolve("sources/${source.hash}.json"),WorldsmithJson.encode(source).toByteArray(StandardCharsets.UTF_8))
            val ids=job.request.seeds.mapIndexed { variant,seed ->
                val path=directory.resolve("result-$variant.wsdraw")
                require(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root.toRealPath()) && Files.size(path)<=DrawSnapshotCodec.MAX_BYTES) { "Invalid worker result path or size" }
                val raw=Files.readAllBytes(path); val drawing=DrawSnapshotCodec.decode(raw)
                // Re-encode untrusted worker bytes into the canonical format before caching them.
                val data=DrawSnapshotCodec.encode(drawing); val dataHash=DrawSnapshotCodec.hash(data)
                val draft=DrawingArtifact("",dataHash,source.hash,seed,job.request.parameters,dataVersion,job.sessionId,job.id,job.request.name,job.revision)
                val artifact=draft.copy(id=DrawSnapshotCodec.hash(WorldsmithJson.encode(draft.copy(sessionId="",jobId="",revision=0)).toByteArray(StandardCharsets.UTF_8)))
                atomic(root.resolve("artifacts/${artifact.id}.wsdraw"),data)
                atomic(root.resolve("owners/${job.sessionId}/${artifact.id}.json"),WorldsmithJson.encode(artifact).toByteArray(StandardCharsets.UTF_8)); artifact.id
            }
            synchronized(this) { if(jobs.getValue(id).stage !in TERMINAL) update(id) { it.copy(stage=DrawingJobStage.SUCCEEDED,drawingIds=ids,diagnostics=diagnostics,message="Frozen drawing ready",log=log.toString()) } }
        } catch(e: Exception) {
            synchronized(this) { if(jobs.getValue(id).stage !in TERMINAL) update(id) { it.copy(stage=DrawingJobStage.FAILED,message=e.message ?: "Drawing failed",
                diagnostics=if(e is BuildFailure)e.details else listOf(DrawingDiagnostic(message=e.message ?: e.javaClass.simpleName)),log=log.toString()) } }
        } finally { processes.remove(id)?.let(::stop) }
    }

    private fun process(id: String,phase: String,directory: Path,seconds: Long,log: StringBuilder): Int {
        val runtime=runtime ?: error("Worker unavailable")
        val windows=System.getProperty("os.name").lowercase().contains("win")
        val javaBin=runtime.javaHome.resolve(if(windows) "bin/javaw.exe" else "bin/java")
        val cp=listOf("draw-worker.jar","draw-sdk.jar","ecj.jar").joinToString(java.io.File.pathSeparator) { runtime.directory.resolve(it).toString() }
        val command=listOf(javaBin.toString(),"-Xmx${limits.heapMiB}m","-Duser.home=$directory","-Djava.io.tmpdir=$directory") + runtime.jvmArguments +
            listOf("-cp",cp,"com.wjz.worldsmith.worker.DrawWorkerMain",phase,directory.toString())
        val builder=ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        val original=builder.environment().toMap(); builder.environment().clear()
        listOf("SystemRoot","WINDIR","LANG","LC_ALL").forEach { k->original[k]?.let { builder.environment()[k]=it } }
        builder.environment()["TEMP"]=directory.toString(); builder.environment()["TMP"]=directory.toString()
        val process=synchronized(this) {
            check(!closed && jobs.getValue(id).stage !in TERMINAL) { "Job cancelled" }
            builder.start().also { processes[id]=it }
        }
        val pump=Thread.ofVirtual().start {
            runCatching { process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer=CharArray(2048)
                while(true) { val n=reader.read(buffer); if(n<0)break; synchronized(log) { if(log.length<65536)log.append(buffer,0,minOf(n,65536-log.length)) } }
            } }
        }
        if(!process.waitFor(seconds,TimeUnit.SECONDS)) { stop(process); pump.join(2000); error("$phase exceeded ${seconds}s deadline") }
        pump.join(2000); processes.remove(id,process); return process.exitValue()
    }
    private fun readDiagnostics(directory: Path): List<DrawingDiagnostic> {
        val path=directory.resolve("diagnostics.properties"); if(!Files.isRegularFile(path)||Files.size(path)>1024*1024)return emptyList()
        val p=Properties(); Files.newBufferedReader(path,StandardCharsets.UTF_8).use(p::load)
        return (0 until (p.getProperty("count")?.toIntOrNull() ?: 0).coerceIn(0,100)).map { i ->
            val key="diagnostic.$i."; DrawingDiagnostic(p.getProperty(key+"file",""),p.getProperty(key+"line","-1").toLong(),p.getProperty(key+"column","-1").toLong(),p.getProperty(key+"severity","ERROR"),p.getProperty(key+"message","").take(8192))
        }
    }
    @Synchronized override fun close() {
        closed=true; processes.values.forEach(::stop); executor.shutdownNow(); approved.clear()
        jobs.values.filter { it.stage !in TERMINAL }.toList().forEach { update(it.id) { j->j.copy(stage=DrawingJobStage.INTERRUPTED,message="Bridge stopped; explicit retry required") } }
    }
    private fun save(job: DrawingJob) = atomic(root.resolve("jobs/${job.id}.json"),WorldsmithJson.encode(job).toByteArray(StandardCharsets.UTF_8))
    private class BuildFailure(message: String,val details: List<DrawingDiagnostic>): RuntimeException(message)
    companion object {
        private val TERMINAL=setOf(DrawingJobStage.SUCCEEDED,DrawingJobStage.FAILED,DrawingJobStage.CANCELLED,DrawingJobStage.INTERRUPTED)
        private fun inputHash(r:DrawingRequest)=DrawSnapshotCodec.hash(WorldsmithJson.encode(r.copy(sources=r.sources.toSortedMap(),parameters=r.parameters.toSortedMap())).toByteArray(StandardCharsets.UTF_8))
        private fun validate(r: DrawingRequest) {
            require(r.name.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}")) && r.requestId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid build name/requestId" }
            require(r.entryClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) && !r.entryClass.startsWith("java.") && !r.entryClass.startsWith("com.wjz.worldsmith.")) { "Invalid author entry class" }
            require(r.sources.size in 1..16 && r.sources.keys.all { it.matches(Regex("([A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\\.java")) }) { "Use 1..16 relative Java source files" }
            require(r.sources.values.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() }<=1024*1024) { "Source exceeds 1 MiB" }
            require(r.seeds.size in 1..8 && r.parameters.size<=64 && r.parameters.all { it.key.length in 1..128 && it.value.length<=4096 }) { "Invalid seeds/parameters" }
        }
        private fun stop(process: Process) {
            val children=process.toHandle().descendants().toList(); children.forEach { it.destroyForcibly() }; process.destroyForcibly()
        }
        @JvmStatic fun atomic(path: Path,bytes: ByteArray) {
            Files.createDirectories(path.parent); val temp=Files.createTempFile(path.parent,".pending-",".tmp")
            try { Files.write(temp,bytes); try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
                catch(e: AtomicMoveNotSupportedException) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temp) }
        }
    }
}
