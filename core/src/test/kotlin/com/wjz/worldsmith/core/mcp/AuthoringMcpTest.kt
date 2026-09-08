package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*

class AuthoringMcpTest {
    @TempDir lateinit var root:Path
    private fun runtime()=DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),Path.of(System.getProperty("java.home")))
    @Test fun `source projects authored drafts error overlays and atomic plan edits share one MCP workflow`() {
        DrawingHost(root.resolve("jobs"),runtime(),4903).use {host->
            val sessions=WorkflowSessions(directory=root.resolve("sessions"));val tools=WorldsmithMcpTools(root.resolve("packs"),drawings=host,sessions=sessions)
            fun call(name:String,a:JsonObject=JsonObject(emptyMap()))=StructureTestWorld.call(tools,name,a).also {assertFalse(it.isError,"$name: ${it.text}")}
            val begin=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","Open stone courts");put("detail","summary")}).structuredContent
            assertEquals("summary",begin.getValue("detail").jsonPrimitive.content);assertFalse("howToDesign" in begin)
            assertTrue("representative" in begin.getValue("designGuide").jsonPrimitive.content)
            val toc=call(WorldsmithWorkflow.CONTRACT_TOOL,buildJsonObject {put("id","draw");put("detail","index")}).structuredContent
            val section=toc.getValue("sections").jsonArray[1].jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(call(WorldsmithWorkflow.CONTRACT_TOOL,buildJsonObject {put("id","draw");put("section",section)}).text.startsWith("##"))
            val sid=begin.getValue("sessionId").jsonPrimitive.content;host.approve(sid)
            fun source(code:String,revision:Long)=call("worldsmith_put_drawing_source",buildJsonObject {
                put("sessionId",sid);put("name","buildings");put("expectedRevision",revision)
                putJsonObject("changes"){put("Hall.java",code);if(revision==0L)put("Common.java",AuthoringUpgradeTest.COMMON)}
                if(revision==0L)put("targets",WorldsmithJson.format.encodeToJsonElement(mapOf("hall" to DrawingTarget("Hall",listOf("Hall.java","Common.java")))))
            }).structuredContent
            val project=source(AuthoringUpgradeTest.HALL,0).getValue("projectId").jsonPrimitive.content
            fun build(revision:Int):JsonObject {
                val initial=call("worldsmith_build_drawing",buildJsonObject {put("sessionId",sid);put("name","hall");put("requestId","revision_$revision");put("sourceRef",WorldsmithJson.format.encodeToJsonElement(DrawingSourceRef(project,revision.toLong(),"hall")))}).structuredContent
                val job=initial.getValue("jobId").jsonPrimitive.content;val until=System.nanoTime()+60_000_000_000
                while(System.nanoTime()<until) {
                    val result=call("worldsmith_get_drawing_job",buildJsonObject {put("sessionId",sid);put("jobId",job)}).structuredContent
                    assertNotEquals("FAILED",result.getValue("stage").jsonPrimitive.content,result.toString())
                    if(result.getValue("stage").jsonPrimitive.content=="SUCCEEDED"&&result.getValue("checks").jsonArray.isNotEmpty())return result
                    Thread.sleep(30)
                }
                error("Authored job deadline")
            }
            val good=build(1);val first=good.getValue("drawingIds").jsonArray.first().jsonPrimitive.content
            fun definition(id:String)=buildJsonObject {put("id","hall");putJsonObject("blueprint"){put("id","hall");putJsonObject("authored"){put("variants",JsonArray(listOf(JsonPrimitive(id))))}};putJsonObject("placement"){put("biomes",JsonArray(listOf(JsonPrimitive("frostash_flats"))))}}
            val preflight=call("worldsmith_preflight_structure",buildJsonObject {put("sessionId",sid);put("structure",definition(first))});assertTrue(preflight.structuredContent.getValue("valid").jsonPrimitive.boolean,preflight.toString())
            source(AuthoringUpgradeTest.HALL.replace("15);","1);"),1);val dark=build(2).getValue("drawingIds").jsonArray.first().jsonPrimitive.content
            val put=call("worldsmith_put_structure",buildJsonObject {put("sessionId",sid);put("structure",definition(dark))})
            assertFalse(put.structuredContent.getValue("checks").jsonObject.getValue("valid").jsonPrimitive.boolean)
            assertNotNull(sessions.find(sid)!!.structures["hall"])
            val preview=call("worldsmith_preview_structure",buildJsonObject {
                put("sessionId",sid);put("blueprint",definition(dark).getValue("blueprint"));put("views",JsonArray(listOf(JsonPrimitive("front"),JsonPrimitive("isometric"))))
                put("hideComponents",JsonArray(listOf(JsonPrimitive("roof"))));put("overlays",JsonArray(listOf(JsonPrimitive("lighting"),JsonPrimitive("access"),JsonPrimitive("errors"))))
            })
            assertEquals(2,preview.images.size);assertFalse(preview.structuredContent.getValue("valid").jsonPrimitive.boolean)
            Files.write(Path.of(System.getProperty("worldsmith.projectRoot"),"build/structure-efficiency-preview.png"),java.util.Base64.getDecoder().decode(preview.images.last().data))
            assertTrue(preview.structuredContent.getValue("diagnostics").jsonArray.any {it.jsonObject["position"]!=null})
            val raw=call("worldsmith_preview_drawing",buildJsonObject {put("sessionId",sid);put("drawingId",dark);put("hideComponents",JsonArray(listOf(JsonPrimitive("roof"))))})
            val visual=call("worldsmith_preview_drawing",buildJsonObject {
                put("sessionId",sid);put("drawingId",dark);put("renderMode","clay")
                put("views",McpJson.encode(listOf("isometric_back","left","right","top")))
                put("frame",raw.structuredContent.getValue("frame"))
            })
            assertEquals(4,visual.images.size);assertEquals("isometric_back",visual.structuredContent.getValue("view").jsonPrimitive.content)
            assertEquals(raw.structuredContent.getValue("frame"),visual.structuredContent.getValue("frame"))
            val overlay=call("worldsmith_preview_drawing",buildJsonObject {put("sessionId",sid);put("drawingId",dark);put("hideComponents",JsonArray(listOf(JsonPrimitive("roof"))));put("overlays",JsonArray(listOf(JsonPrimitive("lighting"))))})
            assertNotEquals(raw.images.first().data,overlay.images.first().data)
            val repeated=call("worldsmith_preflight_structure",buildJsonObject {put("sessionId",sid);put("structure",definition(dark))}).structuredContent.getValue("checks").jsonObject.getValue("repeatedDiagnostics").jsonPrimitive.int
            assertEquals(1,repeated,"Read-only inspection must not manufacture a repair loop")
            source(AuthoringUpgradeTest.HALL,2);val fixed=build(3);assertTrue(fixed.getValue("compileCacheHit").jsonPrimitive.boolean);assertEquals(first,fixed.getValue("drawingIds").jsonArray.first().jsonPrimitive.content)
            val revision=sessions.find(sid)!!.revision
            val committed=call("worldsmith_put_architecture_draft",buildJsonObject {put("sessionId",sid);put("expectedRevision",revision);put("structures",JsonArray(listOf(definition(first))));put("architecture",WorldsmithJson.format.encodeToJsonElement(StructureTestWorld.fixture().architecture!!))})
            assertEquals(revision+1,committed.structuredContent.getValue("revision").jsonPrimitive.long)
            val read=call("worldsmith_get_drawing_source",buildJsonObject {put("sessionId",sid);put("projectId",project);put("revision",1);put("files",JsonArray(listOf(JsonPrimitive("Hall.java"))))})
            assertEquals(AuthoringUpgradeTest.HALL,read.structuredContent.getValue("sources").jsonObject.getValue("Hall.java").jsonPrimitive.content)
            assertEquals(3,call("worldsmith_authoring_stats",buildJsonObject {put("sessionId",sid)}).structuredContent.getValue("jobs").jsonPrimitive.int)
            call("worldsmith_archive_session",buildJsonObject {put("sessionId",sid)});assertTrue(sessions.all().isEmpty())
            call("worldsmith_resume_session",buildJsonObject {put("sessionId",sid)});assertFalse(sessions.find(sid)!!.archived)
        }
    }
}
