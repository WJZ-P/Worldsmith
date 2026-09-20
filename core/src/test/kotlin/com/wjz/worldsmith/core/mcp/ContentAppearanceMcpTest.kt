package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.examples.MaterialFamilyFactory
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64

class ContentAppearanceMcpTest {
    @TempDir lateinit var root:Path
    @Test fun `appearance preview resolves current draft ids and verified assets without mutation`() {
        val sessions=WorkflowSessions(directory=root.resolve("sessions"));val tools=WorldsmithMcpTools(root.resolve("packs"),sessions=sessions)
        fun call(name:String,a:JsonObject)=tools.all().single {it.name==name}.handler(a)
        val id=call(WorldsmithWorkflow.BEGIN_TOOL,buildJsonObject {put("prompt","A material workshop")}).structuredContent.getValue("sessionId").jsonPrimitive.content
        val family=MaterialFamilyFactory.create()
        family.assets.forEach {(_,bytes)->call("worldsmith_put_texture_asset",buildJsonObject {
            put("sessionId",id);put("expectedRevision",sessions.find(id)!!.revision);put("pngBase64",Base64.getEncoder().encodeToString(bytes))
        })}
        call("worldsmith_put_content_modules",buildJsonObject {put("sessionId",id);put("expectedRevision",sessions.find(id)!!.revision);put("modules",buildJsonObject {
            put("blocks",McpJson.encode(family.blocks));put("items",McpJson.encode(family.items))
        })})
        val before=sessions.find(id)!!
        for((kind,ids) in listOf("block" to listOf(MaterialFamilyFactory.WAYSTONE),"item" to family.items.items.map {it.id})) {
            val result=call("worldsmith_preview_content_appearance",buildJsonObject {put("sessionId",id);put("kind",kind);put("ids",McpJson.encode(ids))})
            assertFalse(result.isError,result.text);assertEquals(1,result.images.size);assertEquals(before.revision,result.structuredContent.getValue("revision").jsonPrimitive.long)
            assertFalse(result.structuredContent.getValue("draftUpdated").jsonPrimitive.boolean)
            assertFalse(result.structuredContent.getValue("preview").jsonObject.getValue("minecraftScreenshot").jsonPrimitive.boolean)
        }
        assertEquals(before,sessions.find(id))
        assertThrows(IllegalArgumentException::class.java){call("worldsmith_preview_content_appearance",buildJsonObject {put("sessionId",id);put("kind","block");put("ids",McpJson.encode(listOf("missing")))})}
        assertEquals(before,sessions.find(id))
    }
}
