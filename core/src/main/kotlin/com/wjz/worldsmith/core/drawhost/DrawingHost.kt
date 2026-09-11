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
    private val writer:(Path,ByteArray)->Unit = DurableFiles::write,
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
    val sourceStore=DrawingSourceStore(this.root.resolve("source-projects"))
    private val compileCache=runtime?.let {DrawingCompileCache(this.root.resolve("compile-cache"),it)}
    private val snapshotCache=DrawingSnapshotCache()
    private val storageFaults=ConcurrentHashMap<String,String>()
    val automaticSourceExecution:Boolean get()=autoApprove
    @Volatile var completionChecks:((DrawingJob)->List<com.wjz.worldsmith.core.structure.StructureCheckReport>)?=null

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
                save(restored); jobs[job.id]=restored
            }.onFailure { failure -> recoveryProblems+=DrawingDiagnostic(file=p.fileName.toString(),message="Preserved unreadable job record: ${failure.message}; repair or archive it before submitting new revisions") } }
        }
    }

    @Synchronized fun submit(sessionId: String, input: DrawingRequest): DrawingJob {
        check(!closed && available) { "Drawing worker is not available on this host" }
        check(recoveryProblems.isEmpty()) { recoveryProblems.joinToString("; ") { "${it.file}: ${it.message}" } }
        require(sessionId.matches(Regex("[a-f0-9]{32}"))) { "Invalid drawing session" }
        val resolved=input.sourceRef?.let {ref->
            require(input.sources.isEmpty() && input.entryClass.isEmpty()) {"Choose inline sources OR a sourceRef"}
            val source=sourceStore.resolve(sessionId,ref)
            input.copy(entryClass=source.target.entryClass,sources=source.sources)
        } ?: input
        validate(resolved)
        val request=resolved.copy(sources=resolved.sources.toSortedMap(),parameters=resolved.parameters.toSortedMap())
        val hash=inputHash(request)
        jobs.values.firstOrNull { it.sessionId==sessionId && it.request.requestId==request.requestId }?.let {
            require(it.inputHash==hash) { "requestId was already used for different source/input" }; return it
        }
        check(jobs.size < 256) { "Drawing history capacity reached; preserve/export drafts before cleaning the authoring directory" }
        val revision=(jobs.values.filter { it.sessionId==sessionId && it.request.name==request.name }.maxOfOrNull { it.revision } ?: 0)+1
        val ready=autoApprove||sessionId in approved
        val job=DrawingJob(UUID.randomUUID().toString().replace("-",""),sessionId,revision,hash,request,
            if(ready) DrawingJobStage.QUEUED else DrawingJobStage.WAITING_APPROVAL,
            message=if(ready) "Queued" else "Confirm this world's source-execution session inside Minecraft",
            createdAtMillis=System.currentTimeMillis(),sourceBytes=request.sources.values.sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()})
        save(job); jobs[job.id]=job
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
        val job=jobs[id] ?: archived(sessionId).firstOrNull {it.id==id} ?: error("Unknown drawing job")
        require(job.sessionId==sessionId) { "Job belongs to another session" }
        return storageFaults[id]?.let {job.copy(stage=DrawingJobStage.FAILED,message=it,persistenceCommitted=false)} ?: job
    }
    @Synchronized fun list(sessionId: String): List<DrawingJob> = (jobs.values.filter { it.sessionId==sessionId }+archived(sessionId)).distinctBy {it.id}.sortedBy {it.createdAtMillis}
    /** UI polling snapshot: current in-memory jobs only, including observed persistence faults; no archive I/O. */
    @Synchronized fun liveJobs(sessionId: String): List<DrawingJob> = jobs.values.filter { it.sessionId == sessionId }.map { job ->
        storageFaults[job.id]?.let { job.copy(stage=DrawingJobStage.FAILED,message=it,persistenceCommitted=false) } ?: job
    }
    @Synchronized fun cancel(sessionId: String,id: String): DrawingJob {
        val job=get(sessionId,id)
        if(job.stage !in TERMINAL) {
            update(id) { it.copy(stage=DrawingJobStage.CANCELLED,message="Cancelled by caller") }
            processes[id]?.let(::stop)
        }
        return jobs.getValue(id)
    }
    @Synchronized private fun update(id: String, change: (DrawingJob)->DrawingJob): DrawingJob {
        val next=change(jobs.getValue(id)); save(next); jobs[id]=next; storageFaults.remove(id);return next
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
            val history=list(sessionId)
            val owner=history.filter { it.stage==DrawingJobStage.SUCCEEDED && id in it.drawingIds }.maxByOrNull { it.revision }
            require(owner!=null) { "Drawing build did not complete successfully" }
            val latest=history.filter { it.request.name==artifact.name }.maxByOrNull { it.revision }
            require(allowPrevious || latest?.id==owner.id && latest.stage==DrawingJobStage.SUCCEEDED && sourceStore.isCurrent(sessionId,owner.request)) {
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
    fun drawing(artifact: DrawingArtifact): DrawStructure {
        val p=root.resolve("artifacts/${artifact.id}.wsdraw")
        val key="${artifact.dataHash}:${Files.size(p)}:${Files.getLastModifiedTime(p)}"
        return snapshotCache.get(key) {DrawSnapshotCodec.decode(bytes(artifact))}
    }
    fun semantics(artifact:DrawingArtifact):kotlinx.serialization.json.JsonObject? = artifact.semanticsHash?.let {hash->
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val p=root.resolve("semantics/$hash.json");require(Files.size(p)<=1024*1024&&!Files.isSymbolicLink(p))
        val raw=Files.readAllBytes(p);require(DrawSnapshotCodec.hash(raw)==hash) {"Authored metadata hash mismatch"}
        kotlinx.serialization.json.Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
    }
    @Synchronized fun statistics(session:String,id:String):List<DrawingStatistics> {
        val job=get(session,id);if(job.statistics.isNotEmpty()||job.stage!=DrawingJobStage.SUCCEEDED)return job.statistics
        val stats=job.drawingIds.map {drawingId->val a=artifact(session,drawingId,true);statistics(a,drawing(a))}
        if(id in jobs)update(id){it.copy(statistics=stats)}
        return stats
    }
    private fun statistics(a:DrawingArtifact,d:DrawStructure)=DrawingStatistics(a.id,a.dataHash,d.bounds().width(),d.bounds().height(),d.bounds().depth(),com.wjz.worldsmith.core.structure.BuildPos(d.bounds().min().x(),d.bounds().min().y(),d.bounds().min().z()),d.voxels().size,d.nonAirCells(),a.semanticsHash!=null)
    private fun archived(session:String):List<DrawingJob> {
        require(session.matches(Regex("[a-f0-9]{32}")))
        val dir=root.resolve("archive/$session");if(!Files.isDirectory(dir))return emptyList()
        return Files.list(dir).use {it.filter {p->p.fileName.toString().matches(Regex("[a-f0-9]{32}\\.json"))}.map {p->
            require(Files.size(p)<=8*1024*1024);WorldsmithJson.decode<DrawingJob>(Files.readString(p)).also {j->require(j.sessionId==session)}
        }.toList()}
    }
    @Synchronized fun archive(session:String) {
        val records=jobs.values.filter {it.sessionId==session};require(records.all {it.stage in TERMINAL}) {"Finish or cancel running drawing jobs before archiving"}
        for(job in records){val source=root.resolve("jobs/${job.id}.json");val target=root.resolve("archive/$session/${job.id}.json")
            writer(target,Files.readAllBytes(source));Files.delete(source);jobs.remove(job.id)
        }
    }
    fun source(artifact: DrawingArtifact): DrawingSourceRecord = WorldsmithJson.decode(Files.readString(root.resolve("sources/${artifact.sourceHash}.json")))

    private fun run(id: String) {
        val job=synchronized(this) { jobs.getValue(id) }
        val log=StringBuilder();val times=linkedMapOf<String,Long>();var cacheHit=false
        fun <T> timed(name:String,action:()->T):T {val start=System.nanoTime();try{return action()}finally{times[name]=(System.nanoTime()-start)/1_000_000}}
        try {
            stage(id,DrawingJobStage.COMPILING)
            synchronized(this){update(id){it.copy(startedAtMillis=System.currentTimeMillis())}}
            times["queue"]=maxOf(0,System.currentTimeMillis()-job.createdAtMillis)
            val worker=runtime ?: error("Worker unavailable")
            val directory=root.resolve("work/$id"); Files.createDirectories(directory.resolve("sources"))
            for((name,source) in job.request.sources) {
                val file=directory.resolve("sources").resolve(name).normalize(); require(file.startsWith(directory.resolve("sources")))
                Files.createDirectories(file.parent); Files.writeString(file,source,StandardCharsets.UTF_8)
            }
            val properties=Properties().apply {
                setProperty("sdk",worker.directory.resolve("draw-sdk.jar").toString());setProperty("authoring",worker.directory.resolve("authoring-sdk.jar").toString()); setProperty("entryClass",job.request.entryClass)
                setProperty("seeds",job.request.seeds.joinToString(",")); job.request.parameters.forEach { (k,v)->setProperty("param.$k",v) }
            }
            Files.newBufferedWriter(directory.resolve("request.properties"),StandardCharsets.UTF_8).use { properties.store(it,null) }
            val cache=requireNotNull(compileCache)
            val key=cache.key(job.request)
            cacheHit=timed("cacheLookup"){cache.restore(key,directory.resolve("classes"))}
            val compile=if(cacheHit)0 else timed("compile"){process(id,"compile",directory,limits.compileSeconds,log)}
            val diagnostics=if(cacheHit)emptyList()else readDiagnostics(directory)
            if(compile!=0) {
                throw BuildFailure("Java compilation failed (exit $compile)",diagnostics.ifEmpty { listOf(DrawingDiagnostic(message=log.toString())) })
            }
            if(!cacheHit)runCatching {cache.store(key,directory.resolve("classes"))}.onFailure {log.append("Cache write skipped: ${it.message}\n")}
            stage(id,DrawingJobStage.DRAWING)
            val generated=timed("execution"){process(id,"generate",directory,limits.drawSeconds,log)}
            check(generated==0 && Files.isRegularFile(directory.resolve("done")) && Files.readString(directory.resolve("done")).trim()==job.request.seeds.size.toString()) {
                "Drawing worker exited without complete results (exit $generated)"
            }
            stage(id,DrawingJobStage.VALIDATING)
            val frozenStart=System.nanoTime();val statistics=mutableListOf<DrawingStatistics>()
            val sourceBase=DrawingSourceRecord("",job.request.entryClass,job.request.sources)
            val source=sourceBase.copy(hash=DrawSnapshotCodec.hash(WorldsmithJson.encode(sourceBase).toByteArray(StandardCharsets.UTF_8)))
            atomic(root.resolve("sources/${source.hash}.json"),WorldsmithJson.encode(source).toByteArray(StandardCharsets.UTF_8))
            val ids=job.request.seeds.mapIndexed { variant,seed ->
                val path=directory.resolve("result-$variant.wsdraw")
                require(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root.toRealPath()) && Files.size(path)<=DrawSnapshotCodec.MAX_BYTES) { "Invalid worker result path or size" }
                val raw=Files.readAllBytes(path); val drawing=DrawSnapshotCodec.decode(raw)
                // Re-encode untrusted worker bytes into the canonical format before caching them.
                val data=DrawSnapshotCodec.encode(drawing); val dataHash=DrawSnapshotCodec.hash(data)
                val semanticFile=directory.resolve("result-$variant.authoring.json")
                val semanticHash=if(Files.exists(semanticFile)){
                    require(Files.size(semanticFile)<=1024*1024&&!Files.isSymbolicLink(semanticFile));val json=Files.readAllBytes(semanticFile)
                    require(kotlinx.serialization.json.Json.parseToJsonElement(json.toString(Charsets.UTF_8)) is kotlinx.serialization.json.JsonObject)
                    val hash=DrawSnapshotCodec.hash(json);writer(root.resolve("semantics/$hash.json"),json);hash
                } else null
                val draft=DrawingArtifact("",dataHash,source.hash,seed,job.request.parameters,dataVersion,job.sessionId,job.id,job.request.name,job.revision,
                    semanticsHash=semanticHash,authoringSdkVersion=if(semanticHash!=null)"authoring-1" else null)
                val artifact=draft.copy(id=DrawSnapshotCodec.hash(WorldsmithJson.encode(draft.copy(sessionId="",jobId="",revision=0)).toByteArray(StandardCharsets.UTF_8)))
                atomic(root.resolve("artifacts/${artifact.id}.wsdraw"),data)
                atomic(root.resolve("owners/${job.sessionId}/${artifact.id}.json"),WorldsmithJson.encode(artifact).toByteArray(StandardCharsets.UTF_8)); statistics+=statistics(artifact,drawing);artifact.id
            }
            times["freeze"]=(System.nanoTime()-frozenStart)/1_000_000
            synchronized(this) { if(jobs.getValue(id).stage !in TERMINAL) update(id) { it.copy(stage=DrawingJobStage.SUCCEEDED,drawingIds=ids,diagnostics=diagnostics,message="Frozen drawing ready; authoring checks are reported separately",log=log.toString(),completedAtMillis=System.currentTimeMillis(),timingsMillis=times,compileCacheHit=cacheHit,statistics=statistics) } }
            val complete=synchronized(this){jobs.getValue(id)}
            if(complete.stage==DrawingJobStage.SUCCEEDED)completionChecks?.let {inspect->
                val reports=inspect(complete)
                synchronized(this){update(id){it.copy(checks=reports,timingsMillis=it.timingsMillis+reports.flatMap {r->r.stages.entries}.groupBy {e->e.key}.mapValues {e->e.value.sumOf {v->v.value.elapsedMillis}})}}
            }
        } catch(e: Exception) {
            synchronized(this) { if(jobs.getValue(id).stage !in TERMINAL)runCatching {
                update(id) { it.copy(stage=DrawingJobStage.FAILED,message=e.message ?: "Drawing failed",completedAtMillis=System.currentTimeMillis(),timingsMillis=times,compileCacheHit=cacheHit,
                    diagnostics=if(e is BuildFailure)e.details else listOf(DrawingDiagnostic(message=e.message ?: e.javaClass.simpleName)),log=log.toString()) }
            }.onFailure {storageFaults[id]="PERSISTENCE_ERROR: ${it.message}; disk record retained, explicit retry required"} }

        } finally { processes.remove(id)?.let(::stop) }
    }

    private fun process(id: String,phase: String,directory: Path,seconds: Long,log: StringBuilder): Int {
        val runtime=runtime ?: error("Worker unavailable")
        val windows=System.getProperty("os.name").lowercase().contains("win")
        val javaBin=runtime.javaHome.resolve(if(windows) "bin/javaw.exe" else "bin/java")
        val cp=listOf("draw-worker.jar","draw-sdk.jar","authoring-sdk.jar","ecj.jar").joinToString(java.io.File.pathSeparator) { runtime.directory.resolve(it).toString() }
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
        jobs.values.filter { it.stage !in TERMINAL }.toList().forEach { job->runCatching {update(job.id) { j->j.copy(stage=DrawingJobStage.INTERRUPTED,message="Bridge stopped; explicit retry required") }}.onFailure {storageFaults[job.id]="PERSISTENCE_ERROR: ${it.message}"} }
    }
    private fun save(job: DrawingJob) = writer(root.resolve("jobs/${job.id}.json"),WorldsmithJson.encode(job).toByteArray(StandardCharsets.UTF_8))
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
        @JvmStatic fun atomic(path:Path,bytes:ByteArray)=DurableFiles.write(path,bytes)
    }
}
