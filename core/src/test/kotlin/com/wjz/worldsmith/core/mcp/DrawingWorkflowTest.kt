package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DrawingWorkflowTest {
    @TempDir lateinit var root:Path
    private fun runtime()=DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),Path.of(System.getProperty("java.home")),listOf("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"))
    private fun body(result:JsonObject)=result.getValue("structuredContent").jsonObject
    private fun rpc(endpoint:URI,name:String,args:JsonObject=JsonObject(emptyMap())):JsonObject {
        val request=buildJsonObject {
            put("jsonrpc","2.0");put("id",1);put("method","tools/call")
            putJsonObject("params") {put("name",name);put("arguments",args)}
        }
        val response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,response.statusCode(),response.body())
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject
    }
    private fun await(endpoint:URI,session:String,job:String):JsonObject {
        val deadline=System.nanoTime()+60_000_000_000L
        while(System.nanoTime()<deadline) {
            val result=rpc(endpoint,"worldsmith_get_drawing_job",buildJsonObject {put("sessionId",session);put("jobId",job)})
            assertFalse(result.get("isError")?.jsonPrimitive?.boolean == true,result.toString())
            val state=body(result);val stage=state.getValue("stage").jsonPrimitive.content
            assertNotEquals("FAILED",stage,state.toString())
            if(stage=="SUCCEEDED")return state
            Thread.sleep(50)
        }
        error("Worker did not finish")
    }
    @Test fun `MCP builds previews freezes resumes and requires current native publication`() {
        val drafts=root.resolve("drafts");val jobs=root.resolve("jobs");val packs=root.resolve("packs")
        val sessions=WorkflowSessions(directory=drafts)
        var nativeStage="WAITING_NATIVE_CONTEXT";var mutateDuringFinish=false;var session="";var drawingId="";var savedPath:Path?=null
        val publication=PublicationHost { _,_->
            if(mutateDuringFinish)sessions.invalidate(session)
            PublicationStatus(nativeStage,"Native host test receipt")
        }
        DrawingHost(jobs,runtime(),4903).use { host ->
            val tools=WorldsmithMcpTools(packs,sessions=sessions,drawings=host,publicationHost=publication)
            McpHttpServer(tools.all(),"test").use { server ->
                val endpoint=server.start(0)
                session=body(rpc(endpoint,WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","A world of monumental stone colonnades")})).getValue("sessionId").jsonPrimitive.content
                StructureTestWorld.install(tools,session)
                val args=buildJsonObject {
                    put("sessionId",session);put("name","hall");put("requestId","hall-r1");put("entryClass","Hall")
                    putJsonObject("sources") {put("Hall.java",SOURCE)};put("seeds",JsonArray(listOf(JsonPrimitive(41))))
                }
                val job=body(rpc(endpoint,"worldsmith_build_drawing",args));assertEquals("WAITING_APPROVAL",job.getValue("stage").jsonPrimitive.content)
                val revision=sessions.find(session)!!.revision
                assertEquals(job,body(rpc(endpoint,"worldsmith_build_drawing",args)));assertEquals(revision,sessions.find(session)!!.revision)
                assertTrue(tools.all().none {"approve" in it.name},"MCP may not grant its own source approval")
                host.approve(session)
                val complete=await(endpoint,session,job.getValue("jobId").jsonPrimitive.content)
                drawingId=complete.getValue("drawingIds").jsonArray.single().jsonPrimitive.content
                assertEquals(80,complete.getValue("drawings").jsonArray.single().jsonObject.getValue("width").jsonPrimitive.int)
                val preview=rpc(endpoint,"worldsmith_preview_drawing",buildJsonObject {put("sessionId",session);put("drawingId",drawingId)})
                val image=preview.getValue("content").jsonArray.map {it.jsonObject}.single {it.getValue("type").jsonPrimitive.content=="image"}
                val png=Base64.getDecoder().decode(image.getValue("data").jsonPrimitive.content)
                assertTrue(ImageIO.read(png.inputStream()).width>100)
                val original=StructureTestWorld.fixture().structures.first()
                val updated=original.copy(blueprint=original.blueprint.copy(origin=BuildPos(0,0,0),build=emptyList(),drawing=StructureDrawingSource(listOf(drawingId)),ports=listOf(
                    StructurePort("north",BuildPos(0,4,-16),PortFacing.NORTH,"walk","wings",true),
                    StructurePort("east",BuildPos(39,4,0),PortFacing.EAST,"walk","wings",true))))
                fun put()=rpc(endpoint,WorldsmithWorkflow.STRUCTURE_TOOL,buildJsonObject {put("sessionId",session);put("structure",WorldsmithJson.format.encodeToJsonElement(updated))})
                assertFalse(put().get("isError")?.jsonPrimitive?.boolean == true)
                val current=sessions.find(session)!!.revision;put();assertEquals(current,sessions.find(session)!!.revision)
                val checked=rpc(endpoint,WorldsmithWorkflow.ARCHITECTURE_VALIDATE_TOOL,buildJsonObject {put("sessionId",session)})
                assertTrue(body(checked).getValue("valid").jsonPrimitive.boolean,checked.toString())
                val template=body(rpc(endpoint,WorldsmithWorkflow.TEMPLATE_TOOL))
                val writeArgs=buildJsonObject {put("sessionId",session);put("displayName","SDK round trip");listOf("terrain","biomes","features").forEach {put(it,template.getValue(it))}}
                val saved=rpc(endpoint,WorldsmithWorkflow.WRITE_TOOL,writeArgs);assertFalse(saved.get("isError")?.jsonPrimitive?.boolean == true,saved.toString())
                savedPath=Path.of(body(saved).getValue("path").jsonPrimitive.content)
                val pack=WorldsmithPackLoader.loadDirectory(savedPath!!)
                assertEquals(2,pack.manifest.formatVersion);assertEquals(pack.manifest.id,pack.computedId)
                assertEquals(80,pack.structures.drawingAssets.getValue(drawingId).bounds().width())
                assertTrue(Files.exists(savedPath!!.resolve("drawings/$drawingId.wsdraw")))
                fun finish()=rpc(endpoint,WorldsmithWorkflow.FINISH_TOOL,buildJsonObject {put("sessionId",session)})
                assertFalse(body(finish()).getValue("complete").jsonPrimitive.boolean)
                nativeStage="FAILED";val failed=finish();assertTrue(failed.get("isError")?.jsonPrimitive?.boolean == true);assertFalse(body(failed).getValue("complete").jsonPrimitive.boolean)
                nativeStage="PUBLISHED";mutateDuringFinish=true;assertFalse(body(finish()).getValue("complete").jsonPrimitive.boolean)
                mutateDuringFinish=false;rpc(endpoint,WorldsmithWorkflow.WRITE_TOOL,writeArgs)
                assertTrue(body(finish()).getValue("complete").jsonPrimitive.boolean);assertTrue(body(finish()).getValue("minecraftCompiled").jsonPrimitive.boolean)
                assertFalse(body(finish()).getValue("landmarkInstancesVerified").jsonPrimitive.boolean)
            }
        }
        DrawingHost(jobs,runtime(),4903).use { restored ->
            val resumedSessions=WorkflowSessions(directory=drafts)
            val tools=WorldsmithMcpTools(packs,sessions=resumedSessions,drawings=restored)
            val resumed=StructureTestWorld.call(tools,"worldsmith_resume_session",buildJsonObject {put("sessionId",session)})
            assertFalse(resumed.isError);assertNotNull(resumed.structuredContent["architecture"]);assertEquals(3,resumedSessions.find(session)!!.structures.size)
            assertEquals(SOURCE,restored.source(restored.artifact(session,drawingId)).files.getValue("Hall.java"))
            val retry=restored.submit(session,DrawingRequest("hall","retry","Hall",mapOf("Hall.java" to SOURCE),listOf(41)))
            assertEquals(DrawingJobStage.WAITING_APPROVAL,retry.stage)
            assertThrows(IllegalArgumentException::class.java) {restored.artifact(session,drawingId)}
            assertTrue(restored.drawing(restored.artifact(session,drawingId,true)).nonAirCells()>0)
            // Loading a published pack remains entirely data-only even with a newer unapproved draft.
            assertEquals(80,WorldsmithPackLoader.loadDirectory(savedPath!!).structures.drawingAssets.getValue(drawingId).bounds().width())
            assertEquals(2,restored.list(session).size)
        }
    }
    companion object {
        private val SOURCE="""
            import com.wjz.worldsmith.core.draw.*;
            public class Hall implements DrawProgram {
              public DrawStructure generate(DrawContext context) {
                var c=context.canvas(Box.of(-40,0,-16,39,39,15));
                c.pen("stone_bricks").fill(Box.of(-40,0,-16,39,3,15));
                c.pen("air").fill(Box.of(-40,4,-16,39,39,15));
                for(int x:new int[]{-38,35}) for(int z:new int[]{-14,11})
                  c.pen("stone_bricks").fill(Box.of(x,4,z,x+2,37,z+2));
                c.pen("dark_oak_planks").fill(Box.of(-40,38,-16,39,38,15));
                c.anchor("entry",new Vec3i(0,4,-16));
                return c.snapshot();
              }
            }
        """.trimIndent()
    }
}
