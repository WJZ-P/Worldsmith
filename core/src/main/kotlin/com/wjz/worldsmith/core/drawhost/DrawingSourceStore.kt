package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import java.nio.file.*

@Serializable data class DrawingTarget(val entryClass:String,val files:List<String>)
@Serializable data class DrawingSourceRef(val projectId:String,val revision:Long,val target:String)
@Serializable data class DrawingSourceRevision(val projectId:String,val sessionId:String,val name:String,val revision:Long,val files:Map<String,String>,val targets:Map<String,DrawingTarget>)
data class ResolvedDrawingSource(val revision:DrawingSourceRevision,val target:DrawingTarget,val sources:Map<String,String>)

/** Immutable project revisions reference deduplicated file blobs. A build pins one exact revision. */
class DrawingSourceStore(private val root:Path) {
    @Synchronized fun put(session:String,name:String,changes:Map<String,String?>,targets:Map<String,DrawingTarget>?,expectedRevision:Long):DrawingSourceRevision {
        session(session);require(name.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}"))) {"Invalid source project name"}
        val id=DrawSnapshotCodec.hash("$session:$name".toByteArray()).take(32);val dir=root.resolve("projects/$id");val head=dir.resolve("head.json")
        val previous=if(Files.exists(head))read(head)else null
        require((previous?.revision ?: 0)==expectedRevision) {"SOURCE_REVISION_CONFLICT: expected $expectedRevision, current ${previous?.revision ?: 0}"}
        val files=previous?.files.orEmpty().toMutableMap()
        for((file,text)in changes){require(FILE.matches(file)) {"Use relative Java source paths"};if(text==null)files.remove(file)else {
            val bytes=text.toByteArray(Charsets.UTF_8);require(bytes.size<=1024*1024) {"Source file exceeds 1 MiB"}
            val hash=DrawSnapshotCodec.hash(bytes);val blob=root.resolve("blobs/$hash.java")
            if(!Files.exists(blob)||Files.isSymbolicLink(blob)||Files.size(blob)>1024*1024||DrawSnapshotCodec.hash(Files.readAllBytes(blob))!=hash)DurableFiles.write(blob,bytes)
            files[file]=hash
        }}
        require(files.size in 1..64 && files.values.sumOf {Files.size(root.resolve("blobs/$it.java"))}<=4*1024*1024) {"Project supports 1..64 files, at most 4 MiB"}
        val nextTargets=targets ?: previous?.targets ?: emptyMap()
        require(nextTargets.size in 1..32) {"Declare 1..32 source targets"}
        for((key,target)in nextTargets){require(key.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}")))
            require(target.entryClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))&&!target.entryClass.startsWith("java.")&&!target.entryClass.startsWith("com.wjz.worldsmith.")) {"Invalid target entry class"}
            require(target.files.size in 1..16 && target.files.distinct().size==target.files.size && target.files.all {it in files}) {"Each target lists 1..16 existing source files"}
            require(target.files.sumOf {Files.size(root.resolve("blobs/${files.getValue(it)}.java"))}<=1024*1024) {"Target sources exceed 1 MiB"}
        }
        if(previous!=null && files==previous.files && nextTargets==previous.targets)return previous
        val revision=DrawingSourceRevision(id,session,name,expectedRevision+1,files.toSortedMap(),nextTargets.toSortedMap())
        val bytes=WorldsmithJson.encode(revision).toByteArray(Charsets.UTF_8)
        DurableFiles.write(dir.resolve("${revision.revision}.json"),bytes);DurableFiles.write(head,bytes)
        return revision
    }
    @Synchronized fun get(session:String,id:String,revision:Long?=null):DrawingSourceRevision {
        session(session);require(id.matches(Regex("[a-f0-9]{32}")));require(revision==null||revision>0)
        val head=read(root.resolve("projects/$id/head.json"));require(head.sessionId==session&&head.projectId==id) {"Source project belongs to another session"}
        require(revision==null||revision<=head.revision) {"Source revision is not committed"}
        return (if(revision==null)head else read(root.resolve("projects/$id/$revision.json"))).also {require(it.sessionId==session&&it.projectId==id) {"Source project belongs to another session"}}
    }
    fun files(revision:DrawingSourceRevision,selected:List<String> = revision.files.keys.toList()):Map<String,String> = selected.associateWith { name->
        val hash=revision.files[name] ?: error("Unknown source file '$name'")
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val p=root.resolve("blobs/$hash.java");require(Files.size(p)<=1024*1024&&!Files.isSymbolicLink(p))
        val bytes=Files.readAllBytes(p);require(DrawSnapshotCodec.hash(bytes)==hash) {"Source blob hash mismatch"};bytes.toString(Charsets.UTF_8)
    }
    fun resolve(session:String,ref:DrawingSourceRef):ResolvedDrawingSource {val r=get(session,ref.projectId,ref.revision);val target=r.targets[ref.target] ?: error("Unknown build target");return ResolvedDrawingSource(r,target,files(r,target.files))}
    fun isCurrent(session:String,request:DrawingRequest):Boolean {
        val ref=request.sourceRef ?: return true
        return runCatching {val current=get(session,ref.projectId);val target=current.targets[ref.target] ?: return false
            target.entryClass==request.entryClass && files(current,target.files)==request.sources
        }.getOrDefault(false)
    }
    private fun read(path:Path):DrawingSourceRevision {require(Files.size(path)<=1024*1024&&!Files.isSymbolicLink(path));return WorldsmithJson.decode(Files.readString(path))}
    private fun session(id:String){require(id.matches(Regex("[a-f0-9]{32}"))) {"Invalid source session"}}
    companion object {private val FILE=Regex("([A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\\.java")}
}
