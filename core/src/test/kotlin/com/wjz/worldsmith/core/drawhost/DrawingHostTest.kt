package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.function.Consumer

class DrawingHostTest {
    @TempDir lateinit var root:Path
    private val sid="a".repeat(32)
    private fun runtime(home:Path=Path.of(System.getProperty("java.home")))=DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),home,
        listOf("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"))
    private fun request(id:String,body:String=BODY)=DrawingRequest("hall",id,"Builder",mapOf("Builder.java" to """
        import com.wjz.worldsmith.core.draw.*;
        public class Builder implements DrawProgram {
          public DrawStructure generate(DrawContext context) throws Exception {
            if(javax.tools.ToolProvider.getSystemJavaCompiler()!=null)throw new AssertionError("System compiler should be absent");
            $body
          }
        }
    """.trimIndent()),listOf(17,18),mapOf("test" to "yes"))
    private fun await(host:DrawingHost,id:String):DrawingJob {
        val deadline=System.nanoTime()+60_000_000_000L
        while(System.nanoTime()<deadline) { val j=host.get(sid,id);if(j.stage in setOf(DrawingJobStage.SUCCEEDED,DrawingJobStage.FAILED,DrawingJobStage.CANCELLED,DrawingJobStage.INTERRUPTED))return j;Thread.sleep(40) }
        error("Drawing job timed out in test")
    }
    @Test fun `bundled compiler works without system compiler on Java 25 and 26`() {
        val homes=mutableListOf(Path.of(System.getProperty("java.home")))
        System.getProperty("worldsmith.java26Home")?.let { homes.add(Path.of(it)) }
        homes.forEachIndexed { i,home ->
            DrawingHost(root.resolve("vm$i"),runtime(home),4903).use { host->
                val queued=host.submit(sid,request("build"));assertEquals(DrawingJobStage.WAITING_APPROVAL,queued.stage)
                assertEquals(queued.id,host.submit(sid,request("build")).id)
                host.approve(sid);val done=await(host,queued.id);assertEquals(DrawingJobStage.SUCCEEDED,done.stage,done.message+done.log+done.diagnostics)
                val artifact=host.artifact(sid,done.drawingIds.first());val drawing=host.drawing(artifact)
                assertEquals(80,drawing.bounds().width());assertEquals(1,drawing.anchors().size)
                assertTrue(drawing.voxels().any { it.block().state().isAir() });assertTrue(drawing.voxels().any { it.block().orientation().mirrorX() })
                assertArrayEquals(host.bytes(artifact),DrawSnapshotCodec.encode(drawing))
                assertTrue(DrawPreview.png(drawing,"front",null).size>100)
                assertEquals(done.drawingIds,host.get(sid,queued.id).drawingIds)
            }
        }
    }
    @Test fun `failure cancellation and restart keep good artifacts`() {
        val dir=root.resolve("host")
        var saved=""
        DrawingHost(dir,runtime(),4903,Consumer {},DrawingExecutionLimits(30,2,128)).use { host->
            host.approve(sid)
            val good=await(host,host.submit(sid,request("good")).id);assertEquals(DrawingJobStage.SUCCEEDED,good.stage,good.message);saved=good.drawingIds.first()
            val broken=await(host,host.submit(sid,request("syntax","not Java;")).id)
            assertEquals(DrawingJobStage.FAILED,broken.stage);assertTrue(broken.diagnostics.any { it.line>0 && it.column>0 })
            val exit=await(host,host.submit(sid,request("exit","System.exit(9); return null;")).id);assertEquals(DrawingJobStage.FAILED,exit.stage)
            val loop=await(host,host.submit(sid,request("loop","while(true) { Thread.onSpinWait(); }")).id);assertEquals(DrawingJobStage.FAILED,loop.stage)
            val oom=await(host,host.submit(sid,request("oom","var bytes=new byte[Integer.MAX_VALUE]; return null;")).id);assertEquals(DrawingJobStage.FAILED,oom.stage)
            val corrupt=await(host,host.submit(sid,request("corrupt","""
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                  try { java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"result-0.wsdraw"),new byte[]{1,2,3}); }
                  catch(Exception e) { throw new RuntimeException(e); }
                }));
                $BODY
            """.trimIndent())).id);assertEquals(DrawingJobStage.FAILED,corrupt.stage)
            val cancel=host.submit(sid,request("cancel","Thread.sleep(120000); return null;"));host.cancel(sid,cancel.id);assertEquals(DrawingJobStage.CANCELLED,host.get(sid,cancel.id).stage)
            assertThrows(IllegalArgumentException::class.java) { host.artifact(sid,saved) }
            assertTrue(host.drawing(host.artifact(sid,saved,true)).voxels().isNotEmpty())
        }
        DrawingHost(dir,runtime(),4903).use { host->
            assertTrue(host.drawing(host.artifact(sid,saved,true)).voxels().isNotEmpty())
            assertEquals(DrawingJobStage.WAITING_APPROVAL,host.submit(sid,request("after_restart")).stage)
            val a=host.artifact(sid,saved,true);Files.write(dir.resolve("artifacts/${a.id}.wsdraw"),byteArrayOf(1,2,3))
            assertThrows(IllegalArgumentException::class.java) { host.bytes(a) }
        }
    }
    @Test fun `codec rejects corrupted runs and keeps exact transforms`() {
        val c=DrawCanvas(Box.of(-4,-1,-4,80,5,5));c.pen("stone").fill(Box.of(0,0,0,70,0,2));c.pen("air").set(1,1,1)
        val bytes=DrawSnapshotCodec.encode(c.snapshot());assertEquals(c.snapshot().voxels(),DrawSnapshotCodec.decode(bytes).voxels())
        assertThrows(java.io.IOException::class.java) { DrawSnapshotCodec.decode(bytes.copyOf(bytes.size/2)) }
    }
    @Test fun `interrupted and corrupt history is preserved and reported instead of silently dropped`() {
        val dir=root.resolve("recovery");var id=""
        DrawingHost(dir,runtime(),4903).use { host -> id=host.submit(sid,request("pending")).id }
        DrawingHost(dir,runtime(),4903).use { host ->
            assertEquals(DrawingJobStage.INTERRUPTED,host.get(sid,id).stage)
            assertEquals(1,host.list(sid).size);assertTrue(host.recoveryDiagnostics.isEmpty())
        }
        val corrupt=dir.resolve("jobs/broken.json");Files.writeString(corrupt,"not json")
        DrawingHost(dir,runtime(),4903).use { host ->
            assertEquals(1,host.list(sid).size);assertEquals(1,host.recoveryDiagnostics.size)
            assertEquals("not json",Files.readString(corrupt))
            assertThrows(IllegalStateException::class.java) {host.submit(sid,request("retry"))}
        }
    }
    companion object {
        private val BODY="""
            var c=context.canvas(Box.of(-40,0,-16,39,39,15));
            c.pen("stone_bricks").fill(Box.of(-40,0,-16,39,3,15));
            c.pen("air").fill(Box.of(-39,4,-15,38,38,14));
            c.pen("stone_brick_stairs[facing=north,half=bottom]").translate(0,4,0).rotateY(1).mirrorX().set(1,0,0);
            c.anchor("entry",new Vec3i(0,4,-16));
            return c.snapshot();
        """.trimIndent()
    }
}
