package com.wjz.worldsmith.core.mcp

import java.util.UUID

/** One ordered step of the guided flow, named by the tool that performs it. */
data class WorkflowStep(
    val order: Int,
    val tool: String,
    val instruction: String,
)

/** What one guided run has achieved so far. */
data class WorkflowSession(
    val id: String,
    val prompt: String,
    val packId: String? = null,
    val finished: Boolean = false,
)

/**
 * The procedure an outside agent follows to build one world.
 *
 * An agent that only sees a list of tools has to guess the order, guess when it
 * is allowed to stop, and guess what "done" means. All three guesses are wrong
 * often enough to matter, so the flow is stated once here: [BEGIN_TOOL] hands
 * out the whole procedure along with a session to track it, and [FINISH_TOOL]
 * is the only thing that may declare the run over.
 *
 * The tool names are constants because the procedure and the tool registry
 * would otherwise be two lists that have to agree by hand.
 */
object WorldsmithWorkflow {
    const val BEGIN_TOOL: String = "worldsmith_begin_world"
    const val TEMPLATE_TOOL: String = "worldsmith_get_pack_template"
    const val WRITE_TOOL: String = "worldsmith_write_pack"
    const val FINISH_TOOL: String = "worldsmith_finish_world"

    /**
     * Read by the agent before it designs anything.
     *
     * It says plainly what `complete` does and does not mean, because the agent
     * repeats that to a player who cannot see any of this.
     */
    const val OVERVIEW: String =
        "You are designing one Minecraft world from the player's description. Work through `procedure` in " +
            "order and do not stop until $FINISH_TOOL answers complete=true.\n\n" +
            "Design both the terrain and the biomes yourself. The player's prompt is the only standard for " +
            "land/ocean balance, scale, relief, height, caves, rivers, lakes, ocean depth, biome count and biome distribution. " +
            "`terrainContract` defines the semantic terrain and hydrology controls; `designContract` holds the biome " +
            "rules; and `climatePlacement` describes optional semantic presets plus exact raw axes. " +
            "Worldsmith validates what you send and " +
            "reports exactly what is wrong, so a rejected pack is a repair job rather than a restart: change " +
            "only what the diagnostics name and send the whole document again.\n\n" +
            "complete=true means exactly this much: the pack is saved in Worldsmith's pack directory, reads " +
            "back from disk, passes every Worldsmith validator and has been selected for Minecraft's " +
            "world-creation screen. Claim nothing beyond that to the player - not that a world was already " +
            "created or played. When you are done, tell them the pack name, how many biomes it has and where it " +
            "was saved."

    val PROCEDURE: List<WorkflowStep> = listOf(
        WorkflowStep(
            order = 1,
            tool = TEMPLATE_TOOL,
            instruction =
                "Call it once. It returns the built-in pack as a field-shape example. Copy its schema, not its " +
                    "biome count, climate partition or theme; those come only from the player's prompt.",
        ),
        WorkflowStep(
            order = 2,
            tool = WRITE_TOOL,
            instruction =
                "Send the whole pack with this sessionId. Preserve the template's technical terrain envelope, " +
                    "but replace its shape with a procedural intent chosen from the player's prompt; design the " +
                    "biomes and features to match it. A reply carrying " +
                    "error diagnostics means nothing was saved, so repair those exact problems and call it again.",
        ),
        WorkflowStep(
            order = 3,
            tool = FINISH_TOOL,
            instruction =
                "Call it with this sessionId. It re-reads the pack from disk and re-validates it. Stop when it " +
                    "answers complete=true; while it answers false, do what `nextTool` says and call it again.",
        ),
    )
}

/**
 * Remembers guided runs so [WorldsmithWorkflow.FINISH_TOOL] can answer honestly.
 *
 * Without this the finish tool could only ever return true, which would make it
 * a decoration rather than a completion signal. Sessions live in memory and die
 * with the bridge, which is the truthful lifetime: the bridge only exists while
 * the game does, and a pack half-written before a restart is not resumable.
 *
 * The map is bounded so a bridge left running for a long session cannot grow
 * without limit; finished runs are discarded before unfinished ones.
 */
class WorkflowSessions @JvmOverloads constructor(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1" }
    }

    private val sessions = LinkedHashMap<String, WorkflowSession>()

    @Synchronized
    fun begin(prompt: String): WorkflowSession {
        evictDownTo(maxSessions - 1)
        val session = WorkflowSession(idFactory(), prompt)
        sessions[session.id] = session
        return session
    }

    @Synchronized
    fun find(id: String): WorkflowSession? = sessions[id]

    /** Returns null when the id is unknown, which the caller reports rather than throws. */
    @Synchronized
    fun recordPack(id: String, packId: String): WorkflowSession? = update(id) { it.copy(packId = packId) }

    @Synchronized
    fun finish(id: String): WorkflowSession? = update(id) { it.copy(finished = true) }

    @Synchronized
    fun size(): Int = sessions.size

    private fun update(id: String, change: (WorkflowSession) -> WorkflowSession): WorkflowSession? {
        val current = sessions[id] ?: return null
        val updated = change(current)
        sessions[id] = updated
        return updated
    }

    private fun evictDownTo(target: Int) {
        while (sessions.size > target) {
            val victim = sessions.entries.firstOrNull { it.value.finished }?.key ?: sessions.keys.first()
            sessions.remove(victim)
        }
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 8
    }
}
