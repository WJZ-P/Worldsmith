package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.ability.debug.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Debugging never changes content drafts or calls native effects on behalf of a simulation. */
class AbilitySimulationMcpService @JvmOverloads constructor(
    private val capabilities: AbilityCapabilityRegistry = AbilityCapabilities.standard(),
    private val runtime: AbilityRuntimeDebugHost = AbilityRuntimeDebugHost.UNAVAILABLE,
) {
    fun tools(): List<McpTool> = listOf(
        McpTool("worldsmith_get_ability_simulation_contract", "Read ability simulation and trace contract",
            "Read exact typed fixture examples, trace limits, source locations, and the distinction between Core simulation and live native inspection.",
            McpJson.schema(emptyMap(), emptyList()), true, handler = { McpToolResult.success(contract()) }),
        McpTool("worldsmith_simulate_ability", "Simulate an AbilityScript with explicit host fixtures",
            "Deterministic, bounded Core-only execution of program or library+programId. Supply self/target/origin and ordered responses for every non-pure query/effect. Missing or mismatched responses stop with HOST_INPUT_REQUIRED, never invented success. Returns line/column trace, budget usage, state and unmet expectations; does not execute Minecraft or change a draft.",
            simulationSchema(), true, handler = ::simulate),
        McpTool("worldsmith_inspect_ability_runtime", "Inspect live ability state or opt-in traces",
            "Read-only world observation for one actor UUID and optional native scope. snapshot reads bounded runtime state; start_trace/stop_trace only control opt-in debugging, not program execution; read_trace reads the bounded trace. The native host validates scope and dispatches to its server thread. Unavailable without a connected native runtime.",
            McpJson.schema(mapOf(
                "action" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("snapshot", "start_trace", "read_trace", "stop_trace").map(::JsonPrimitive))) },
                "actor" to buildJsonObject { put("type", "string"); put("format", "uuid"); put("maxLength", 36) },
                "scope" to buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 128); put("description", "Optional native host-defined dimension/runtime scope") },
                "limit" to integer(1, 256),
            ), listOf("action", "actor")), true, handler = ::inspectRuntime),
    )

    private fun simulate(arguments: JsonObject): McpToolResult {
        val raw = arguments.toString()
        require(raw.length <= 4 * 1024 * 1024 && raw.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Simulation request exceeds 4 MiB." }
        val request = McpJson.decode<AbilitySimulationRequest>(arguments)
        val result = AbilitySimulator.run(request, capabilities)
        return McpToolResult.success(McpJson.encode(result).jsonObject)
    }

    private fun inspectRuntime(arguments: JsonObject): McpToolResult {
        require(arguments.keys.all { it in setOf("action", "actor", "scope", "limit") }) { "Unknown runtime inspection argument." }
        val action = McpJson.string(arguments, "action")
        require(action in setOf("snapshot", "start_trace", "read_trace", "stop_trace")) { "Unknown runtime inspection action." }
        val actor = McpJson.string(arguments, "actor")
        require(actor.length == 36 && UUID.fromString(actor).toString().equals(actor, ignoreCase = true)) { "actor must be a canonical UUID." }
        arguments["scope"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length in 1..128) { "scope must contain 1..128 characters." } }
        arguments["limit"]?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.int in 1..256) { "limit must be 1..256." } }
        return McpToolResult.success(runtime.inspect(arguments))
    }

    fun contract(): JsonObject = buildJsonObject {
        put("simulationTool", "worldsmith_simulate_ability")
        put("runtimeTool", "worldsmith_inspect_ability_runtime")
        put("simulationOnly", true)
        put("minecraftExecuted", false)
        put("contract", javaClass.classLoader.getResourceAsStream("prompts/contract/ability_simulation.system.md")?.bufferedReader()?.use { it.readText() }
            ?: error("Missing ability simulation contract"))
        put("capabilities", AbilityAuthoringContract.capabilities(capabilities))
        put("inputSchema", simulationSchema())
        put("example", McpJson.encode(AbilitySimulationRequest(
            inputs = mapOf("self" to AbilityValues.entity("sim-caster"), "target" to AbilityValues.entity("sim-target"), "origin" to AbilityValues.vector(0.0, 64.0, 0.0)),
            program = AbilityProgramDefinition("debug_example", "Explicit fixture example", "on start { wait 2; state.hit = combat.damage(target, 3); }"),
            ticks = 4,
            responses = listOf(AbilitySimulationResponse("combat.damage", AbilityValues.bool(true), arguments = listOf(AbilityValues.entity("sim-target"), AbilityValues.number(3.0)), tick = 2)),
        )))
    }

    private fun simulationSchema(): JsonObject = McpJson.schema(mapOf(
        "inputs" to buildJsonObject { put("type", "object"); put("description", "Required self ENTITY, target ENTITY or null, origin VECTOR; optional args is any bounded AbilityValue (default null). AbilityValue uses kind tags in MCP: entity/id, vector/x/y/z, number/value, bool/value, text/value, list/values, map/values, null.") },
        "program" to buildJsonObject { put("type", "object"); put("description", "One AbilityProgramDefinition including actual source. Mutually exclusive with library.") },
        "library" to buildJsonObject { put("type", "object"); put("description", "AbilityLibrary with up to 64 programs; programId chooses the one simulated. Mutually exclusive with program. This does not validate unselected source programs.") },
        "programId" to buildJsonObject { put("type", "string"); put("maxLength", 64) },
        "initialState" to buildJsonObject { put("type", "object"); put("description", "Optional map of typed AbilityValues, obeying normal 64-key/64KiB/2048-node/depth-8 state limits.") },
        "ticks" to integer(1, 12000),
        "operationsPerTick" to integer(1, 8192),
        "traceLimit" to integer(0, AbilitySimulator.MAX_TRACE_ENTRIES),
        "responses" to buildJsonObject {
            put("type", "array"); put("maxItems", AbilitySimulator.MAX_RESPONSES)
            put("items", McpJson.schema(mapOf(
                "capability" to McpJson.type("string"), "result" to McpJson.type("object"), "error" to McpJson.type("string"),
                "arguments" to buildJsonObject { put("type", "array"); put("maxItems", 16); put("items", McpJson.type("object")) },
                "tick" to integer(0, 11999),
            ), listOf("capability")))
        },
        "events" to buildJsonObject {
            put("type", "array"); put("maxItems", AbilitySimulator.MAX_EVENTS)
            put("items", McpJson.schema(mapOf("tick" to integer(0, 11999), "event" to McpJson.type("string"), "payload" to McpJson.type("object")), listOf("tick", "event")))
        },
    ), listOf("inputs"))

    private fun integer(minimum: Int, maximum: Int): JsonObject = buildJsonObject { put("type", "integer"); put("minimum", minimum); put("maximum", maximum) }
}
