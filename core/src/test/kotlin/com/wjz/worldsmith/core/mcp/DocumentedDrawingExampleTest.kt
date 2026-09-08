package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import kotlinx.serialization.json.*
import java.nio.file.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DocumentedDrawingExampleTest {
    @TempDir lateinit var root:Path
    @Test fun `published Java and full MCP example execute without a client-side compiler`() = replay("structure-agent")
    @Test fun `authored workbench source projects and shared components replay end to end`() = replay("authoring-workbench")
    private fun replay(example:String) {
        val path=Path.of(System.getProperty("worldsmith.projectRoot"),"docs/examples/$example/workflow.json")
        val document=Json.parseToJsonElement(Files.readString(path)).jsonObject
        val steps=document.getValue("steps").jsonArray
        val variables=mutableMapOf<String,JsonElement>()
        fun substitute(value:JsonElement):JsonElement = when(value) {
            is JsonObject -> JsonObject(value.mapValues {substitute(it.value)})
            is JsonArray -> JsonArray(value.map(::substitute))
            is JsonPrimitive -> if(value.isString&&value.content.startsWith("$"))variables.getValue(value.content.substring(1))else value
            else -> value
        }
        fun pointer(value:JsonElement,path:String):JsonElement=path.trimStart('/').split('/').fold(value) { parent,key->if(parent is JsonArray)parent[key.toInt()]else parent.jsonObject.getValue(key) }
        val runtime=DrawingRuntime(Path.of(System.getProperty("worldsmith.workerRuntime")),Path.of(System.getProperty("java.home")),listOf("--limit-modules=java.base,java.compiler,java.logging,java.xml,java.desktop"))
        DrawingHost(root.resolve("jobs"),runtime,4903).use { host ->
            val tools=WorldsmithMcpTools(root.resolve("packs"),drawings=host,publicationHost=PublicationHost { _,_->PublicationStatus("PUBLISHED") })
            var imageCount=0
            for(raw in steps) {
                val step=raw.jsonObject;val name=step.getValue("tool").jsonPrimitive.content;val args=substitute(step.getValue("arguments")).jsonObject
                fun call()=StructureTestWorld.call(tools,name,args).also { assertFalse(it.isError,"$name: ${it.structuredContent}") }
                var result=call()
                if(step["waitUntil"]!=null) {
                    val deadline=System.nanoTime()+60_000_000_000L
                    while(result.structuredContent.getValue("stage").jsonPrimitive.content!="SUCCEEDED") {
                        val stage=result.structuredContent.getValue("stage").jsonPrimitive.content
                        assertTrue(stage in setOf("WAITING_APPROVAL","QUEUED","COMPILING","DRAWING","VALIDATING"),result.toString())
                        if(stage=="WAITING_APPROVAL")host.approve(args.getValue("sessionId").jsonPrimitive.content)
                        assertTrue(System.nanoTime()<deadline,"Example job deadline")
                        Thread.sleep(50);result=call()
                    }
                }
                imageCount+=result.images.size
                if(name=="worldsmith_preflight_structure")assertTrue(result.structuredContent.getValue("valid").jsonPrimitive.boolean,"Repair before expanding the next building: ${result.structuredContent}")
                step["capture"]?.jsonObject?.forEach { (key,p)->variables[key]=pointer(result.structuredContent,p.jsonPrimitive.content) }
                if(name==WorldsmithWorkflow.FINISH_TOOL)assertTrue(result.structuredContent.getValue("complete").jsonPrimitive.boolean)
            }
            assertEquals(document["expectedImages"]?.jsonPrimitive?.int ?: 5,imageCount)
        }
    }
}
