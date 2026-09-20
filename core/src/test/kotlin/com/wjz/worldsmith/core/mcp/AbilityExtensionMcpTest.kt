package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbilityExtensionMcpTest {
    @TempDir lateinit var root: Path

    @Test fun `catalog publishes honest unavailable extension workflow and no approval channel`() {
        val tools = WorldsmithMcpTools(root.resolve("packs")).all().filter { "ability_extension" in it.name }
        assertEquals(8, tools.size)
        assertFalse(tools.any { "approve" in it.name })
        tools.forEach { tool ->
            assertFalse("approve" in tool.inputSchema.getValue("properties").jsonObject)
            assertFalse("classpath" in tool.inputSchema.getValue("properties").jsonObject)
        }
        val contract = tools.single { it.name == "worldsmith_get_ability_extension_contract" }.handler(JsonObject(emptyMap())).structuredContent
        assertFalse(contract.getValue("available").jsonPrimitive.boolean)
        assertFalse(contract.getValue("sandboxed").jsonPrimitive.boolean)
        assertFalse(contract.getValue("approvalToolAvailable").jsonPrimitive.boolean)
        assertTrue(contract.getValue("contract").jsonPrimitive.content.contains("Class.forName(name, false, loader)"))
        assertTrue(contract.getValue("contract").jsonPrimitive.content.contains("Drawing/session approval does not apply"))
        val build = tools.single { it.name == "worldsmith_build_ability_extension" }
        val unavailable = build.handler(buildJsonObject { put("project", contract.getValue("example")) })
        assertTrue(unavailable.isError)
        assertFalse(unavailable.structuredContent.getValue("providerCodeExecuted").jsonPrimitive.boolean)
        assertFalse(unavailable.structuredContent.getValue("installed").jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java) { build.handler(buildJsonObject { put("project", contract.getValue("example")); put("approve", true) }) }
        val requestInstall = tools.single { it.name == "worldsmith_request_ability_extension_install" }
        assertThrows(IllegalArgumentException::class.java) { requestInstall.handler(buildJsonObject { put("jobId", "0".repeat(32)); put("approved", true) }) }
    }
}
