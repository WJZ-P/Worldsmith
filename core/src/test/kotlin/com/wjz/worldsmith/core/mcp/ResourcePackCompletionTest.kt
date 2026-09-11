package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Handler-level completion receipts; native callback is controlled, no game or drawing worker is launched. */
class ResourcePackCompletionTest {
    @TempDir lateinit var root:Path
    private fun call(tools:WorldsmithMcpTools,name:String,args:JsonObject=JsonObject(emptyMap()))=tools.all().single {it.name==name}.handler(args)
    private fun setup(native:PublicationHost):Triple<WorldsmithMcpTools,WorkflowSessions,String> {
        val sessions=WorkflowSessions(directory=root.resolve("drafts"))
        val tools=WorldsmithMcpTools(root.resolve("packs"),sessions=sessions,publicationHost=native)
        val id=call(tools,WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","Archive completion fixture")})
            .structuredContent.getValue("sessionId").jsonPrimitive.content
        StructureTestWorld.install(tools,id)
        return Triple(tools,sessions,id)
    }
    private fun write(tools:WorldsmithMcpTools,sessions:WorkflowSessions,id:String,filename:String?=null):McpToolResult {
        val template=call(tools,WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        return call(tools,WorldsmithWorkflow.WRITE_TOOL,buildJsonObject {
            put("sessionId",id);put("expectedRevision",sessions.find(id)!!.revision);put("displayName","Archive completion")
            listOf("terrain","biomes","features","theme").forEach {put(it,template.getValue(it))}
            filename?.let {put("resourcePackFilename",it)}
        })
    }

    @Test fun `write automatically exports and finish reuses archive while native completion stays separate`() {
        val nativeCalls=AtomicInteger()
        val (tools,sessions,id)=setup(PublicationHost {_,_->nativeCalls.incrementAndGet();PublicationStatus("WAITING_NATIVE_CONTEXT","Explicit native action is still needed")})
        val written=write(tools,sessions,id)
        assertFalse(written.isError,written.text)
        assertTrue(written.structuredContent.getValue("valid").jsonPrimitive.boolean)
        assertTrue(written.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
        val archive=written.structuredContent.getValue("resourcePack").jsonObject
        val path=Path.of(archive.getValue("path").jsonPrimitive.content)
        val packId=written.structuredContent.getValue("id").jsonPrimitive.content
        assertEquals("$packId.wspack",path.fileName.toString())
        assertEquals(packId,WorldsmithResourceArchive.read(path).info.bundleId)
        assertEquals(0,nativeCalls.get())
        val saved=sessions.find(id)!!
        val finished=call(tools,WorldsmithWorkflow.FINISH_TOOL,written.structuredContent.getValue("nextArguments").jsonObject)
        assertFalse(finished.isError,finished.text)
        assertFalse(finished.structuredContent.getValue("complete").jsonPrimitive.boolean)
        assertTrue(finished.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
        assertEquals("WAITING_NATIVE_CONTEXT",finished.structuredContent.getValue("stage").jsonPrimitive.content)
        val reused=finished.structuredContent.getValue("resourcePack").jsonObject
        assertEquals(archive.getValue("path"),reused.getValue("path"))
        assertEquals(archive.getValue("archiveInfo"),reused.getValue("archiveInfo"))
        assertTrue(reused.getValue("reusedExisting").jsonPrimitive.boolean)
        assertEquals(saved,sessions.find(id))
        assertEquals(1,nativeCalls.get())
    }

    @Test fun `archive conflict preserves frozen session and old file with export only retry before native`() {
        val nativeCalls=AtomicInteger()
        val (tools,sessions,id)=setup(PublicationHost {_,_->nativeCalls.incrementAndGet();PublicationStatus("PUBLISHED")})
        val occupied=root.resolve("resource-packs/exports/occupied.wspack")
        val original="Existing user file must remain unchanged".toByteArray()
        Files.write(occupied,original)
        val failed=write(tools,sessions,id,"occupied.wspack")
        assertTrue(failed.isError)
        assertTrue(failed.structuredContent.getValue("valid").jsonPrimitive.boolean,"Core bundle was saved before export failed")
        assertFalse(failed.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
        assertEquals("RESOURCE_PACK_EXPORT_FAILED",failed.structuredContent.getValue("resourcePackError").jsonObject.getValue("code").jsonPrimitive.content)
        val saved=sessions.find(id)!!
        assertNotNull(saved.packId);assertNull(saved.lastWriteFailure,"An archive collision is not a module-validation failure")
        assertArrayEquals(original,Files.readAllBytes(occupied))
        val failedFinish=call(tools,WorldsmithWorkflow.FINISH_TOOL,buildJsonObject {put("sessionId",id);put("resourcePackFilename","occupied.wspack")})
        assertTrue(failedFinish.isError)
        assertFalse(failedFinish.structuredContent.getValue("complete").jsonPrimitive.boolean)
        assertEquals(0,nativeCalls.get(),"Native publication must not hide a missing archive")
        assertEquals(saved,sessions.find(id))
        val retry=failedFinish.structuredContent.getValue("nextArguments").jsonObject
        assertEquals(saved.packId,retry.getValue("id").jsonPrimitive.content)
        val exported=call(tools,"worldsmith_export_resource_pack",retry)
        assertFalse(exported.isError,exported.text)
        val finished=call(tools,WorldsmithWorkflow.FINISH_TOOL,failedFinish.structuredContent.getValue("continueArguments").jsonObject)
        assertFalse(finished.isError,finished.text)
        assertTrue(finished.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
        assertTrue(finished.structuredContent.getValue("complete").jsonPrimitive.boolean)
        assertEquals(exported.structuredContent.getValue("path"),finished.structuredContent.getValue("resourcePack").jsonObject.getValue("path"))
        assertEquals(saved.revision,sessions.find(id)!!.revision)
        assertEquals(1,nativeCalls.get())
        assertArrayEquals(original,Files.readAllBytes(occupied))
    }
}
