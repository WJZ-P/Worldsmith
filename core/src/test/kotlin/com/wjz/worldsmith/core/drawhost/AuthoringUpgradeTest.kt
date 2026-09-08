package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.authoring.*
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.mcp.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*

class AuthoringUpgradeTest {
    @TempDir lateinit var root:Path
    private val sid="c".repeat(32)
    private fun runtime()=DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),Path.of(System.getProperty("java.home")),listOf("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"))
    private fun wait(host:DrawingHost,id:String):DrawingJob {
        val until=System.nanoTime()+60_000_000_000
        while(System.nanoTime()<until){val j=host.get(sid,id);if(j.stage in listOf(DrawingJobStage.SUCCEEDED,DrawingJobStage.FAILED))return j;Thread.sleep(30)}
        error("Drawing did not finish")
    }
    private fun build(host:DrawingHost,name:String,id:String,ref:DrawingSourceRef,parameters:Map<String,String> = emptyMap()):DrawingJob {
        val result=wait(host,host.submit(sid,DrawingRequest(name,id,parameters=parameters,sourceRef=ref)).id)
        assertEquals(DrawingJobStage.SUCCEEDED,result.stage,result.toString());return result
    }
    @Test fun `source revisions reuse compilation and only invalidate dependent targets`() {
        DrawingHost(root,runtime(),4903).use {host->
            host.approve(sid)
            val targets=mapOf("hall" to DrawingTarget("Hall",listOf("Hall.java","Common.java")),"marker" to DrawingTarget("Marker",listOf("Marker.java")))
            val v1=host.sourceStore.put(sid,"world",mapOf("Hall.java" to HALL,"Common.java" to COMMON,"Marker.java" to MARKER),targets,0)
            val ref=DrawingSourceRef(v1.projectId,1,"hall")
            val cold=build(host,"hall_a","a1",ref,mapOf("width" to "5"));val warm=build(host,"hall_b","b1",ref,mapOf("width" to "6"))
            assertFalse(cold.compileCacheHit);assertTrue(warm.compileCacheHit);assertNotEquals(cold.drawingIds,warm.drawingIds)
            assertTrue(cold.timingsMillis.containsKey("compile"));assertTrue(warm.timingsMillis.containsKey("execution"));assertFalse(warm.timingsMillis.containsKey("compile"))
            val marker=build(host,"marker","m1",DrawingSourceRef(v1.projectId,1,"marker"))
            val v2=host.sourceStore.put(sid,"world",mapOf("Common.java" to COMMON.replace("stone_bricks","deepslate_bricks")),null,1)
            assertEquals(COMMON,host.sourceStore.files(host.sourceStore.get(sid,v1.projectId,1),listOf("Common.java")).getValue("Common.java"))
            assertThrows(IllegalArgumentException::class.java){host.artifact(sid,cold.drawingIds.first())}
            assertNotNull(host.artifact(sid,marker.drawingIds.first()))
            val changed=build(host,"hall_a","a2",DrawingSourceRef(v2.projectId,2,"hall"));assertFalse(changed.compileCacheHit)
            val same=build(host,"marker","m2",DrawingSourceRef(v2.projectId,2,"marker"));assertTrue(same.compileCacheHit)
            assertEquals(marker.drawingIds,same.drawingIds)
            assertThrows(IllegalArgumentException::class.java){host.sourceStore.put(sid,"world",mapOf("Common.java" to COMMON),null,1)}
            assertNotNull(host.semantics(host.artifact(sid,changed.drawingIds.first())))
            val cacheFile=Files.walk(root.resolve("compile-cache")).use {it.filter {p->p.fileName.toString()=="Marker.class"}.findFirst().orElseThrow()}
            Files.write(cacheFile,byteArrayOf(1,2,3));val repaired=build(host,"marker","m3",DrawingSourceRef(v2.projectId,2,"marker"));assertFalse(repaired.compileCacheHit);assertEquals(same.drawingIds,repaired.drawingIds)
            val report=buildJsonObject {
                put("scope","Controlled cold/warm, dependency invalidation and corrupt-cache recovery; not total AI latency")
                put("jobs",WorldsmithJson.format.encodeToJsonElement(host.list(sid).map {j->buildJsonObject {put("name",j.request.name);put("revision",j.revision);put("cacheHit",j.compileCacheHit);put("milliseconds",WorldsmithJson.format.encodeToJsonElement(j.timingsMillis))}}))
            }
            val output=Path.of(System.getProperty("worldsmith.projectRoot"),"build/structure-efficiency-benchmark.json");Files.createDirectories(output.parent);Files.writeString(output,report.toString())
            host.archive(sid);assertEquals(6,host.list(sid).size);assertNotNull(host.artifact(sid,repaired.drawingIds.first()))
        }
        DrawingHost(root,runtime(),4903).use {host->assertEquals(6,host.list(sid).size);assertEquals(2,host.sourceStore.get(sid,DrawSnapshotCodec.hash("$sid:world".toByteArray()).take(32)).revision)}
    }
    @Test fun `component transforms preserve geometry rooms entrances fixtures and protected regions`() {
        val child=AuthoringContext(DrawContext(3,emptyMap(),DrawLimits.DEFAULT));child.canvas(Box.of(-2,0,-2,2,5,2));child.origin(Vec3i(0,0,0))
        child.room("room",Box.of(-1,1,-1,1,3,1),BlockStateRef.of("stone"));child.entrance("door",Vec3i(0,1,1),"SOUTH",BlockStateRef.of("stone"),2)
        child.lightFixture("lamp",Vec3i(0,3,0),BlockStateRef.of("glowstone"),15);child.support(Vec3i(0,0,0));child.protect(Box.of(-1,0,-1,1,3,1))
        val frozen=child.snapshot();val t=GridTransform(1,true,Vec3i(9,4,9))
        val parent=AuthoringContext(DrawContext(3,emptyMap(),DrawLimits.DEFAULT));parent.canvas(Box.of(0,0,0,20,12,20));parent.instance("upper",frozen,t)
        val result=parent.snapshot();val json=Json.parseToJsonElement(result.sidecar().toString(Charsets.UTF_8)).jsonObject.getValue("semantics").jsonObject
        assertEquals(buildJsonObject {put("x",9);put("y",7);put("z",9)},json.getValue("sources").jsonArray.single().jsonObject.getValue("at"))
        val door=json.getValue("ports").jsonArray.single().jsonObject;assertEquals("upper_door",door.getValue("id").jsonPrimitive.content);assertEquals("WEST",door.getValue("facing").jsonPrimitive.content)
        assertEquals(t.apply(frozen.components().getValue("room")),result.components().getValue("upper/room"))
        assertArrayEquals(result.sidecar(),parent.snapshot().sidecar())
        assertTrue(result.drawing().voxels().any {it.position()==Vec3i(9,7,9)&&it.block().state().id()=="minecraft:glowstone"})
    }
    @Test fun `persistent session transactions do not publish memory state when disk commit fails`() {
        var fail=false
        val sessions=WorkflowSessions(directory=root.resolve("sessions"),writer={p,bytes->if(fail)throw java.io.IOException("injected disk failure")else DurableFiles.write(p,bytes)})
        val s=sessions.begin("test");val b=WorldStructureDefinition("x",StructureBlueprint(id="x"),StructurePlacement(listOf("biome")))
        fail=true;assertThrows(java.io.IOException::class.java){sessions.putArchitectureDraft(s.id,0,null,listOf(b))};assertEquals(s,sessions.find(s.id))
        fail=false;assertEquals(1,sessions.putArchitectureDraft(s.id,0,null,listOf(b))!!.revision)
        assertThrows(IllegalArgumentException::class.java){sessions.putArchitectureDraft(s.id,0,null,listOf(b))}
        sessions.archive(s.id);assertTrue(sessions.all().isEmpty());assertTrue(sessions.find(s.id)!!.archived)
        assertFalse(sessions.resume(s.id)!!.archived);assertEquals(1,sessions.find(s.id)!!.structures.size)
    }
    @Test fun `fixed camera and diagnostic overlays change real preview pixels`() {
        val canvas=DrawCanvas.sized(8,8,8);canvas.pen("stone").fill(Box.of(0,0,0,7,0,7));val drawing=canvas.snapshot()
        val normal=DrawPreview.png(drawing,"isometric",null,drawing.bounds(),emptyList())
        val marked=DrawPreview.png(drawing,"isometric",null,drawing.bounds(),listOf(DrawPreview.Marker(Vec3i(2,1,2),0xff0055,"dark")))
        val zoomed=DrawPreview.png(drawing,"isometric",null,Box.of(-8,-8,-8,15,15,15),emptyList())
        assertFalse(normal.contentEquals(marked));assertFalse(normal.contentEquals(zoomed))
    }
    @Test fun `cache eviction only removes rebuildable entries and oversize snapshots are not retained`() {
        val cacheRoot=root.resolve("cache");val classes=root.resolve("classes");Files.createDirectories(classes);Files.write(classes.resolve("Example.class"),byteArrayOf(1,2,3))
        val cache=DrawingCompileCache(cacheRoot,runtime(),1,1024*1024)
        val first="a".repeat(64);val second="b".repeat(64)
        cache.store(first,classes);Files.setLastModifiedTime(cacheRoot.resolve(first),java.nio.file.attribute.FileTime.fromMillis(1))
        cache.store(second,classes);assertFalse(Files.exists(cacheRoot.resolve(first)));assertTrue(cache.restore(second,root.resolve("restored")))
        assertTrue(Files.exists(classes.resolve("Example.class")))
        val snapshots=DrawingSnapshotCache(1024);val c=DrawCanvas.sized(3,3,3);c.pen("stone").fill(Box.sized(3,3,3));var loads=0
        repeat(2){snapshots.get("large"){loads++;c.snapshot()}};assertEquals(2,loads)
    }
    @Test fun `publication of a job waits for a durable terminal record`() {
        DrawingHost(root,runtime(),4903,writer={p,bytes->
            if(p.parent.fileName.toString()=="jobs"&&bytes.toString(Charsets.UTF_8).contains("\"stage\": \"SUCCEEDED\""))throw java.io.IOException("injected terminal commit failure")
            DurableFiles.write(p,bytes)
        }).use {host->host.approve(sid);val job=wait(host,host.submit(sid,DrawingRequest("marker","x","Marker",mapOf("Marker.java" to MARKER))).id)
            assertEquals(DrawingJobStage.FAILED,job.stage);assertTrue(job.message.contains("commit failure"))
            val disk=WorldsmithJson.decode<DrawingJob>(Files.readString(root.resolve("jobs/${job.id}.json")));assertEquals(DrawingJobStage.FAILED,disk.stage)
        }
    }
    companion object {
        const val COMMON="public final class Common { public static String material(){return \"stone_bricks\";} }"
        const val MARKER="import com.wjz.worldsmith.core.draw.*; public class Marker implements DrawProgram {public DrawStructure generate(DrawContext c){var a=c.canvas(Box.sized(2,2,2));a.pen(\"stone\").set(0,0,0);return a.snapshot();}}"
        val HALL="""
            import com.wjz.worldsmith.core.draw.*;
            import com.wjz.worldsmith.authoring.*;
            public class Hall implements StructureProgram {
              public AuthoredStructure generate(AuthoringContext a) {
                int w=Integer.parseInt(a.parameters().getOrDefault("width","5"));
                var c=a.canvas(Box.of(-w,0,-4,w,8,4));a.origin(new Vec3i(0,0,0));
                c.pen(Common.material()).fill(Box.of(-w,0,-4,w,0,4));
                a.material("foundation",BlockStateRef.of(Common.material()));
                a.room("nave",Box.of(-2,1,-2,2,4,2),BlockStateRef.of(Common.material()));
                a.entrance("south",new Vec3i(0,1,2),"SOUTH",BlockStateRef.of(Common.material()),3);
                a.lightFixture("lamp",new Vec3i(0,4,0),BlockStateRef.of("glowstone"),15);
                c.pen("dark_oak_planks").fill(Box.of(-w,6,-4,w,6,4));a.component("roof",Box.of(-w,6,-4,w,6,4));
                return a.snapshot();
              }
            }
        """.trimIndent()
    }
}
