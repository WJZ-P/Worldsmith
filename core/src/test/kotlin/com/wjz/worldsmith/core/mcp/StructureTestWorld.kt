package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertFalse

/** Coherent small world policy fixture; never a production building/theme catalog. */
object StructureTestWorld {
    fun fixture():StructureLibrary = WorldsmithJson.decode(requireNotNull(javaClass.getResourceAsStream("/worldsmith/architecture-fixture.json")).bufferedReader().use { it.readText() })
    fun call(tools:WorldsmithMcpTools,name:String,args:JsonObject=JsonObject(emptyMap()))=tools.all().single {it.name==name}.handler(args)
    /** Ordinary write fixtures explicitly opt into the current theme + shared-CAS contract.
     * Tests for missing/stale fields must call the raw handler without this helper. */
    fun writeArgs(tools:WorldsmithMcpTools, args:JsonObject):JsonObject {
        val fields=args.toMutableMap()
        if("theme" !in fields) fields["theme"]=call(tools,WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent.getValue("theme")
        val session=args["sessionId"]?.jsonPrimitive?.contentOrNull
        if(session!=null && "expectedRevision" !in fields) {
            val draft=runCatching {call(tools,"worldsmith_get_content_draft",buildJsonObject {put("sessionId",session)})}.getOrNull()
            draft?.structuredContent?.get("revision")?.let {fields["expectedRevision"]=it}
        }
        return JsonObject(fields)
    }
    fun install(tools:WorldsmithMcpTools,session:String) {
        val fixture=fixture()
        fixture.structures.forEach { definition ->
            val result=call(tools,WorldsmithWorkflow.STRUCTURE_TOOL,buildJsonObject {put("sessionId",session);put("structure",WorldsmithJson.format.encodeToJsonElement(definition))})
            assertFalse(result.isError,result.text)
        }
        val current=call(tools,"worldsmith_resume_session",buildJsonObject {put("sessionId",session)}).structuredContent
        val drafts=WorldsmithJson.format.decodeFromJsonElement<StructureLibrary>(current.getValue("structures"))
        val known=fixture.structures.map {it.id}.toSet()
        val plan=fixture.architecture!!.copy(standalone=fixture.architecture.standalone+drafts.structures.filter {it.id !in known}.map {StandaloneStructureDesign(it.id,"Independent test structure","Shares the test world's requested architecture")})
        val result=call(tools,WorldsmithWorkflow.ARCHITECTURE_TOOL,buildJsonObject {put("sessionId",session);put("architecture",WorldsmithJson.format.encodeToJsonElement(plan))})
        assertFalse(result.isError,result.text)
    }
}
