package com.wjz.worldsmith.core.ability.debug

import com.wjz.worldsmith.core.ability.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections

@Serializable
data class AbilitySimulationRequest @JvmOverloads constructor(
    val inputs: Map<String, AbilityValue>,
    val program: AbilityProgramDefinition? = null,
    val library: AbilityLibrary? = null,
    val programId: String? = null,
    val initialState: Map<String, AbilityValue> = emptyMap(),
    val ticks: Int = 200,
    val operationsPerTick: Int = 128,
    val responses: List<AbilitySimulationResponse> = emptyList(),
    val events: List<AbilitySimulationEvent> = emptyList(),
    val traceLimit: Int = 2048,
)

/** Strictly ordered host fixtures, never default success. Optional arguments/tick add exact expectations. */
@Serializable
data class AbilitySimulationResponse @JvmOverloads constructor(
    val capability: String,
    val result: AbilityValue = AbilityValue.NullValue,
    val error: String? = null,
    val arguments: List<AbilityValue>? = null,
    val tick: Int? = null,
)

@Serializable
data class AbilitySimulationEvent @JvmOverloads constructor(val tick: Int, val event: String, val payload: Map<String, AbilityValue> = emptyMap())

@Serializable
enum class AbilitySimulationStatus { COMPLETED, HOST_INPUT_REQUIRED, EXPECTATIONS_UNMET, FAILED, TICK_LIMIT, COMPILE_ERROR }

@Serializable
data class AbilitySimulationDiagnostic(val code: String, val message: String, val line: Int? = null, val column: Int? = null)

@Serializable
data class AbilitySimulationBudget(
    val requestedTicks: Int,
    val ticksExecuted: Int,
    val operationsPerTick: Int,
    val operationsExecuted: Int,
    val programMaxTicks: Int,
    val programMaxOperations: Int,
    val tickLimitReached: Boolean,
    val traceEntries: Int,
    val traceBytes: Int,
    val droppedTraceEntries: Int,
    val hostCallsExecuted: Int,
    val hostCallReportBytes: Int,
    val droppedHostCallReports: Int,
)

@Serializable
data class AbilityHostCallReport(
    val index: Int,
    val tick: Int,
    val capability: String,
    val arguments: List<AbilityTraceValue>,
    val responseIndex: Int? = null,
    val result: AbilityTraceValue? = null,
    val error: String? = null,
    val location: AbilityTraceLocation? = null,
)

@Serializable
data class AbilityHostExpectationIssue(
    val code: String,
    val message: String,
    val callIndex: Int? = null,
    val responseIndex: Int? = null,
    val capability: String? = null,
)

@Serializable
data class AbilitySimulationEventReport(val index: Int, val tick: Int, val event: String, val accepted: Boolean, val reason: String? = null)

@Serializable
data class AbilitySimulationResult(
    val status: AbilitySimulationStatus,
    val programId: String,
    val simulationOnly: Boolean = true,
    val minecraftExecuted: Boolean = false,
    val explanation: String = "Core AbilityScript execution with explicit synthetic host responses. This is not Minecraft execution, collision verification, or proof that an effect occurred in a world.",
    val diagnostics: List<AbilitySimulationDiagnostic>,
    val budget: AbilitySimulationBudget,
    val trace: List<AbilityTraceEntry>,
    val hostCalls: List<AbilityHostCallReport>,
    val hostExpectations: List<AbilityHostExpectationIssue>,
    val events: List<AbilitySimulationEventReport>,
    val finalState: Map<String, AbilityValue>,
    val stateRevision: Long,
    val idle: Boolean,
    val stopped: Boolean,
    val usedCapabilities: Map<String, Int>,
    val capabilityCalls: Map<String, Int>,
)

/** Bounded, deterministic Core-only execution. No native world host is ever called. */
object AbilitySimulator {
    const val MAX_RESPONSES = 256
    const val MAX_EVENTS = 128
    const val MAX_TRACE_ENTRIES = 2048
    const val MAX_TRACE_BYTES = 262144
    const val MAX_FIXTURE_BYTES = 262144
    private val json = Json { encodeDefaults = true }

    @JvmStatic @JvmOverloads
    fun run(request: AbilitySimulationRequest, registry: AbilityCapabilityRegistry = AbilityCapabilities.standard()): AbilitySimulationResult {
        val definition = validateRequest(request, registry)
        val inputs = AbilityValues.stateCopy(request.inputs)
        val initialState = AbilityValues.stateCopy(request.initialState)
        val responses = request.responses.map { it.copy(arguments = it.arguments?.let { values -> Collections.unmodifiableList(ArrayList(values)) }) }
        val scheduled = request.events.mapIndexed { index, event -> index to event.copy(payload = AbilityValues.stateCopy(event.payload)) }
            .sortedWith(compareBy<Pair<Int, AbilitySimulationEvent>> { it.second.tick }.thenBy { it.first })
        val recorder = Recorder(request.traceLimit)
        val calls = mutableListOf<AbilityHostCallReport>()
        val issues = mutableListOf<AbilityHostExpectationIssue>()
        val eventReports = mutableListOf<AbilitySimulationEventReport>()
        val diagnostics = mutableListOf<AbilitySimulationDiagnostic>()
        val compiled = try { AbilityCompiler.compile(definition, registry) }
        catch (e: IllegalArgumentException) {
            return AbilitySimulationResult(AbilitySimulationStatus.COMPILE_ERROR, definition.id.take(64),
                diagnostics = listOf(diagnostic("COMPILE_ERROR", e.message ?: "Invalid source.")),
                budget = AbilitySimulationBudget(request.ticks, 0, request.operationsPerTick, 0, definition.maxTicks, definition.maxOperations, false, 0, 0, 0, 0, 0, 0),
                trace = emptyList(), hostCalls = emptyList(), hostExpectations = emptyList(), events = emptyList(),
                finalState = initialState, stateRevision = 0, idle = true, stopped = true, usedCapabilities = emptyMap(), capabilityCalls = emptyMap())
        }
        var now = 0
        var responseCursor = 0
        var hostInputRequired = false
        var hostCallsExecuted = 0
        fun recordCall(report: AbilityHostCallReport) {
            if (recorder.recordHostCall(report)) calls += report
        }
        val host = AbilityHost { capability, arguments ->
            val index = hostCallsExecuted++
            val response = responses.getOrNull(responseCursor)
            val mismatch = when {
                response == null -> "No explicit host response for '$capability'."
                response.capability != capability -> "Expected response ${responseCursor} for '${response.capability}', but program called '$capability'."
                response.tick != null && response.tick != now -> "Response $responseCursor expected tick ${response.tick}, but '$capability' ran at tick $now."
                response.arguments != null && response.arguments != arguments -> "Arguments for '$capability' do not match response $responseCursor."
                else -> null
            }
            if (mismatch != null) {
                hostInputRequired = true
                recordCall(AbilityHostCallReport(index, now, capability, arguments.map(AbilityTraceViews::value), error = mismatch, location = recorder.location))
                issues += AbilityHostExpectationIssue(if (response == null) "MISSING_RESPONSE" else "RESPONSE_MISMATCH", mismatch, index,
                    if (response == null) null else responseCursor, capability)
                throw IllegalArgumentException(mismatch)
            }
            val supplied = requireNotNull(response)
            val responseIndex = responseCursor++
            recordCall(AbilityHostCallReport(index, now, capability, arguments.map(AbilityTraceViews::value), responseIndex,
                if (supplied.error == null) AbilityTraceViews.value(supplied.result) else null, supplied.error, recorder.location))
            if (supplied.error != null) throw IllegalArgumentException("Fixture host failure: ${supplied.error}")
            supplied.result
        }
        val machine = AbilityMachine(compiled, host, inputs, initialState, recorder)
        var eventCursor = 0
        var ticksExecuted = 0
        var operations = 0
        for (tick in 0 until request.ticks) {
            now = tick
            while (eventCursor < scheduled.size && scheduled[eventCursor].second.tick == tick) {
                val (index, event) = scheduled[eventCursor++]
                val accepted = machine.emit(event.event, event.payload)
                eventReports += AbilitySimulationEventReport(index, tick, event.event, accepted,
                    if (accepted) null else "Event has no handler, invalid payload, stopped instance, or full 32-fiber queue.")
            }
            val result = machine.tick(tick.toLong(), request.operationsPerTick)
            ticksExecuted++
            operations += result.operations
            if (machine.isStopped()) break
            if (machine.isIdle() && eventCursor >= scheduled.size) break
        }
        val reachedTickLimit = !machine.isStopped() && (!machine.isIdle() || eventCursor < scheduled.size) && ticksExecuted == request.ticks
        machine.failure()?.let { diagnostics += diagnostic(if (hostInputRequired) "HOST_INPUT_REQUIRED" else "RUNTIME_FAILURE", it) }
        if (reachedTickLimit) diagnostics += AbilitySimulationDiagnostic("SIMULATION_TICK_LIMIT", "Simulation stopped after ${request.ticks} ticks; pending continuations or scheduled events remain.")
        for (index in responseCursor until responses.size) issues += AbilityHostExpectationIssue("UNUSED_RESPONSE", "Explicit response $index for '${responses[index].capability}' was not consumed.", responseIndex = index, capability = responses[index].capability)
        while (eventCursor < scheduled.size) {
            val (index, event) = scheduled[eventCursor++]
            eventReports += AbilitySimulationEventReport(index, event.tick, event.event, false, "Simulation ended before this scheduled event was delivered.")
        }
        val status = when {
            hostInputRequired -> AbilitySimulationStatus.HOST_INPUT_REQUIRED
            machine.failure() != null -> AbilitySimulationStatus.FAILED
            reachedTickLimit -> AbilitySimulationStatus.TICK_LIMIT
            issues.isNotEmpty() || eventReports.any { !it.accepted } -> AbilitySimulationStatus.EXPECTATIONS_UNMET
            else -> AbilitySimulationStatus.COMPLETED
        }
        return AbilitySimulationResult(status, definition.id, diagnostics = frozen(diagnostics),
            budget = AbilitySimulationBudget(request.ticks, ticksExecuted, request.operationsPerTick, operations,
                definition.maxTicks, definition.maxOperations, reachedTickLimit, recorder.entries.size, recorder.bytes, recorder.dropped,
                hostCallsExecuted, recorder.hostCallBytes, recorder.droppedHostCalls),
            trace = frozen(recorder.entries), hostCalls = frozen(calls), hostExpectations = frozen(issues), events = frozen(eventReports),
            finalState = machine.snapshotState(), stateRevision = machine.stateRevision(), idle = machine.isIdle(), stopped = machine.isStopped(),
            usedCapabilities = compiled.usedCapabilities, capabilityCalls = Collections.unmodifiableMap(LinkedHashMap(recorder.capabilityCalls)))
    }

    private fun validateRequest(request: AbilitySimulationRequest, registry: AbilityCapabilityRegistry): AbilityProgramDefinition {
        require((request.program != null) != (request.library != null)) { "Supply exactly one of program or library." }
        require(request.ticks in 1..12000) { "Simulation ticks must be 1..12000." }
        require(request.operationsPerTick in 1..8192) { "Simulation operationsPerTick must be 1..8192." }
        require(request.traceLimit in 0..MAX_TRACE_ENTRIES) { "Simulation traceLimit must be 0..$MAX_TRACE_ENTRIES." }
        require(request.responses.size <= MAX_RESPONSES) { "Simulation supports at most $MAX_RESPONSES explicit responses." }
        require(request.events.size <= MAX_EVENTS) { "Simulation supports at most $MAX_EVENTS scheduled events." }
        require(request.inputs.keys.containsAll(setOf("self", "target", "origin")) && request.inputs.keys.all { it in setOf("self", "target", "origin", "args") }) { "Simulation inputs require self, target and origin, with optional args." }
        require(request.inputs["self"] is AbilityValue.EntityValue) { "Simulation self must be an ENTITY handle." }
        require(request.inputs["target"] is AbilityValue.EntityValue || request.inputs["target"] == AbilityValue.NullValue) { "Simulation target must be ENTITY or null." }
        require(request.inputs["origin"] is AbilityValue.VectorValue) { "Simulation origin must be VECTOR." }
        var bytes = 0
        fun fixture(values: Map<String, AbilityValue>) {
            bytes += AbilityValues.encodeState(values).toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_FIXTURE_BYTES) { "Simulation fixtures exceed $MAX_FIXTURE_BYTES UTF-8 bytes." }
        }
        fixture(request.inputs); fixture(request.initialState)
        request.responses.forEach { response ->
            val spec = registry.lookup(response.capability)
            require(spec != null && !registry.isPure(response.capability)) { "Response '${response.capability}' must name a registered non-pure host capability." }
            require(response.tick == null || response.tick in 0 until request.ticks) { "Response tick must be within the requested simulation." }
            require(response.error == null || response.error.isNotBlank() && response.error.length <= 512) { "Fixture error must contain 1..512 characters." }
            require(response.error == null || response.result == AbilityValue.NullValue) { "A response supplies either a result or an error, not both." }
            require(response.arguments == null || response.arguments.size <= 16) { "Response argument expectation exceeds 16 values." }
            fixture(mapOf("result" to response.result))
            response.arguments?.forEach { fixture(mapOf("argument" to it)) }
            bytes += response.capability.toByteArray(Charsets.UTF_8).size + (response.error?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            require(bytes <= MAX_FIXTURE_BYTES) { "Simulation fixtures exceed $MAX_FIXTURE_BYTES UTF-8 bytes." }
        }
        request.events.forEach { event ->
            require(event.tick in 0 until request.ticks) { "Scheduled event tick must be within the requested simulation." }
            require(event.event.length in 1..128 && (event.event.first().isLetter() || event.event.first() == '_') && event.event.all { it.isLetterOrDigit() || it == '_' }) { "Invalid simulation event name." }
            bytes += event.event.toByteArray(Charsets.UTF_8).size
            fixture(event.payload)
        }
        return request.program?.also {
            require(request.programId == null || request.programId == it.id) { "programId does not match program.id." }
        } ?: run {
            val library = requireNotNull(request.library)
            require(library.schemaVersion == 1 && library.programs.size in 1..64) { "Simulation library must be schema 1 with 1..64 programs." }
            require(library.programs.map { it.id }.distinct().size == library.programs.size) { "Simulation library contains duplicate program ids." }
            require(library.programs.all { it.source.length <= 32768 }) { "Library source exceeds 32768 characters per program." }
            requireNotNull(library.programs.singleOrNull { it.id == request.programId }) { "programId must select one program from library." }
        }
    }

    private class Recorder(private val limit: Int) : AbilityTraceListener {
        val entries = mutableListOf<AbilityTraceEntry>()
        var location: AbilityTraceLocation? = null
        var bytes = 0
        var dropped = 0
        var hostCallBytes = 0
        var droppedHostCalls = 0
        val capabilityCalls = linkedMapOf<String, Int>()
        override fun onTrace(event: AbilityTraceEvent) {
            location = event.location
            if (event.kind == AbilityTraceKind.CAPABILITY_CALL && event.name != null) capabilityCalls[event.name] = (capabilityCalls[event.name] ?: 0) + 1
            if (entries.size >= limit) { dropped++; return }
            val entry = AbilityTraceViews.entry(event)
            val count = json.encodeToString(AbilityTraceEntry.serializer(), entry).toByteArray(Charsets.UTF_8).size
            if (count > MAX_TRACE_BYTES - bytes - hostCallBytes) { dropped++; return }
            entries += entry
            bytes += count
        }
        fun recordHostCall(report: AbilityHostCallReport): Boolean {
            val count = json.encodeToString(AbilityHostCallReport.serializer(), report).toByteArray(Charsets.UTF_8).size
            if (count > MAX_TRACE_BYTES - bytes - hostCallBytes) { droppedHostCalls++; return false }
            hostCallBytes += count
            return true
        }
    }

    private fun diagnostic(code: String, message: String): AbilitySimulationDiagnostic {
        val position = Regex("line (\\d+), column (\\d+)").find(message)
        return AbilitySimulationDiagnostic(code, message.take(512), position?.groupValues?.get(1)?.toIntOrNull(), position?.groupValues?.get(2)?.toIntOrNull())
    }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
