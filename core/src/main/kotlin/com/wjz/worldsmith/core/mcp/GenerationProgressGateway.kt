package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.DrawingJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class GenerationSessionSummary(
    val sessionId: String,
    val title: String,
    val prompt: String,
    val mode: WorkflowMode,
    val revision: Long,
    val packId: String?,
    val finished: Boolean,
    val stage: String,
    val lastActivitySequence: Long,
    val lastTool: String?,
    val toolRunning: Boolean,
    val lastToolError: String?,
)

@Serializable
data class GenerationProgressSnapshot(
    val sessions: List<GenerationSessionSummary>,
    val defaultSessionId: String?,
    val selectedSessionId: String?,
    val view: GenerationProgressView?,
    val lastTool: String?,
    val toolRunning: Boolean,
    val lastToolError: String?,
)

/** Current-process activity only: reading progress never edits drafts or manufactures a resumed AI session. */
internal class GenerationProgressGateway(
    private val sessions: () -> List<WorkflowSession>,
    private val jobs: (String) -> List<DrawingJob>,
) {
    private data class Activity(val sequence: Long, val tool: String, val pending: Map<Long, String>, val runningCount: Int, val error: String? = null) {
        val running: Boolean get() = runningCount > 0
        // Saturation drops only old labels, not real work. Never substitute a completed tool for missing detail.
        val displayedTool: String? get() = if (running) pending.maxByOrNull { it.key }?.value else tool
    }
    private val activityLock = Any()
    private val activity = mutableMapOf<String, Activity>()
    private var sequence = 0L

    fun observe(tool: McpTool): McpTool {
        if (tool.readOnly && tool.name != "worldsmith_resume_session") return tool
        return tool.copy(handler = { arguments ->
            val requested = (arguments["sessionId"] as? JsonPrimitive)?.contentOrNull
            var observedId = requested?.takeIf { id -> sessions().any { it.id == id && !it.archived } }
            var ticket = observedId?.let { start(it, tool.name) }
            try {
                val result = tool.handler(arguments)
                if (observedId == null && !result.isError) {
                    val returned = (result.structuredContent["sessionId"] as? JsonPrimitive)?.contentOrNull ?: requested
                    observedId = returned?.takeIf { id -> sessions().any { it.id == id && !it.archived } }
                    ticket = observedId?.let { start(it, tool.name) }
                }
                val id = observedId; val started = ticket
                if (id != null && started != null) finish(id, started, if (result.isError) result.text.take(512) else null)
                result // Never retain tool results, images, binary resources or source payloads in this gateway.
            } catch (failure: Throwable) {
                val id = observedId; val started = ticket
                if (id != null && started != null) finish(id, started, (failure.message ?: failure.javaClass.simpleName).take(512))
                throw failure
            }
        })
    }

    fun snapshot(preferredSessionId: String?): GenerationProgressSnapshot {
        // The backing owners release their locks before projection; no archived files are queried.
        val live = sessions().filterNot { it.archived }.take(128)
        val observed = synchronized(activityLock) {
            activity.keys.retainAll(live.map { it.id }.toSet())
            activity.toMap()
        }
        val candidates = live.filter { observed.containsKey(it.id) }
        val worlds = candidates.filter { it.mode != WorkflowMode.STANDALONE }
        val default = (worlds.ifEmpty { candidates }).maxByOrNull { observed.getValue(it.id).sequence }?.id
        // Explicit selection is sticky, including when the session disappears. Never silently switch it.
        val selected = live.firstOrNull { it.id == (preferredSessionId ?: default) }
        val summaries = live.map { session ->
            val last = observed[session.id]
            val running = last?.running == true
            val title = (session.contentModules["theme"]?.get("title") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: session.prompt.lineSequence().firstOrNull().orEmpty()
            GenerationSessionSummary(session.id, title.take(160), session.prompt.take(512), session.mode, session.revision,
                session.packId, session.finished, when {
                    running -> "RUNNING_TOOL"
                    session.finished -> "HISTORICAL_NATIVE_RECEIPT"
                    session.packId != null -> "CORE_SAVED"
                    else -> "DRAFT"
                }, last?.sequence ?: 0, last?.displayedTool, running, last?.error)
        }.sortedWith(compareBy<GenerationSessionSummary> { it.mode == WorkflowMode.STANDALONE }.thenByDescending { it.lastActivitySequence }.thenBy { it.sessionId })
        val last = selected?.let { observed[it.id] }
        val view = selected?.let { GenerationProgressViews.inspect(it, jobs(it.id)) }
        return GenerationProgressSnapshot(java.util.List.copyOf(summaries), default, selected?.id, view,
            last?.displayedTool, last?.running == true, last?.error)
    }

    private fun start(id: String, tool: String): Long = synchronized(activityLock) {
        val current = activity[id]
        val pending = current?.pending.orEmpty().let { if (it.size < 256) it else it - it.keys.minOrNull()!! }
        val ticket = ++sequence
        val name = tool.take(128)
        activity[id] = Activity(ticket, name, pending + (ticket to name), (current?.runningCount ?: 0) + 1)
        ticket
    }

    private fun finish(id: String, ticket: Long, error: String?) = synchronized(activityLock) {
        val current = activity[id] ?: return@synchronized
        activity[id] = current.copy(pending = current.pending - ticket, runningCount = maxOf(0, current.runningCount - 1),
            error = if (current.sequence == ticket) error else current.error)
    }
}
