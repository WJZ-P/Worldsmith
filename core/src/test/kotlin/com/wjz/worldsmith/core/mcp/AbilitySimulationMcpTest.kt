package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.debug.AbilityRuntimeDebugHost
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbilitySimulationMcpTest {
    @TempDir lateinit var root: Path

    @Test fun `catalog contract example runs read-only simulation with explicit kind-tagged fixtures`() {
        val tools = WorldsmithMcpTools(root.resolve("packs")).all().associateBy { it.name }
        val contract = tools.getValue("worldsmith_get_ability_simulation_contract").handler(JsonObject(emptyMap()))
        assertFalse(contract.isError)
        val document = contract.structuredContent
        assertTrue(document.getValue("contract").jsonPrimitive.content.contains("HOST_INPUT_REQUIRED"))
        assertTrue(document.getValue("contract").jsonPrimitive.content.contains("not damage applied in a game"))
        val example = document.getValue("example").jsonObject
        assertEquals("entity", example.getValue("inputs").jsonObject.getValue("self").jsonObject.getValue("kind").jsonPrimitive.content)
        val tool = tools.getValue("worldsmith_simulate_ability")
        assertTrue(tool.readOnly)
        val simulated = tool.handler(example)
        assertFalse(simulated.isError)
        val result = simulated.structuredContent
        assertEquals("COMPLETED", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("simulationOnly").jsonPrimitive.boolean)
        assertFalse(result.getValue("minecraftExecuted").jsonPrimitive.boolean)
        assertTrue(result.getValue("finalState").jsonObject.getValue("hit").jsonObject.getValue("value").jsonPrimitive.boolean)
        assertTrue(result.getValue("trace").jsonArray.isNotEmpty())
        val missing = tool.handler(JsonObject(example - "responses"))
        assertEquals("HOST_INPUT_REQUIRED", missing.structuredContent.getValue("status").jsonPrimitive.content)
        assertFalse(missing.structuredContent.getValue("minecraftExecuted").jsonPrimitive.boolean)
    }

    @Test fun `offline inspection says unavailable and native bridge receives only validated arguments`() {
        val args = buildJsonObject { put("action", "snapshot"); put("actor", "00000000-0000-0000-0000-000000000001"); put("scope", "minecraft:overworld"); put("limit", 12) }
        val offline = AbilitySimulationMcpService().tools().single { it.name == "worldsmith_inspect_ability_runtime" }.handler(args)
        assertFalse(offline.structuredContent.getValue("available").jsonPrimitive.boolean)
        assertFalse(offline.structuredContent.getValue("minecraftExecuted").jsonPrimitive.boolean)
        var delivered: JsonObject? = null
        val host = AbilityRuntimeDebugHost { a -> delivered = a; buildJsonObject { put("available", true); put("source", "native-test-host") } }
        val tools = WorldsmithMcpTools(root.resolve("native-packs"), abilityDebugHost = host).all()
        val tool = tools.single { it.name == "worldsmith_inspect_ability_runtime" }
        val result = tool.handler(args)
        assertEquals(args, delivered)
        assertEquals("native-test-host", result.structuredContent.getValue("source").jsonPrimitive.content)
        for (invalid in listOf(args + ("actor" to JsonPrimitive("not-a-uuid")), args + ("action" to JsonPrimitive("execute")), args + ("limit" to JsonPrimitive(257)), args + ("unexpected" to JsonPrimitive(true)))) {
            delivered = null
            assertThrows(IllegalArgumentException::class.java) { tool.handler(JsonObject(invalid)) }
            assertNull(delivered)
        }
    }
}
