package com.wjz.worldsmith.core.mcp

import java.util.UUID
import com.wjz.worldsmith.core.structure.WorldStructureDefinition
import com.wjz.worldsmith.core.structure.StructureArchitecture
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.drawhost.DrawingHost

/** One ordered step of the guided flow, named by the tool that performs it. */
data class WorkflowStep(
    val order: Int,
    val tool: String,
    val instruction: String,
)

/** What one guided run has achieved so far. */
@Serializable data class WorkflowSession(
    val id: String,
    val prompt: String,
    val packId: String? = null,
    val finished: Boolean = false,
    val structures: Map<String, WorldStructureDefinition> = emptyMap(),
    val architecture: StructureArchitecture? = null,
    val revision: Long = 0,
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
    const val STYLE_LIST_TOOL: String = "worldsmith_list_styles"
    const val STYLE_GET_TOOL: String = "worldsmith_get_style"
    const val CONTRACT_TOOL: String = "worldsmith_get_contract"
    const val ANALYZE_TOOL: String = "worldsmith_analyze_biome_distribution"
    const val ARCHITECTURE_TOOL: String = "worldsmith_plan_architecture"
    const val ARCHITECTURE_VALIDATE_TOOL: String = "worldsmith_validate_architecture"
    const val STRUCTURE_TOOL: String = "worldsmith_put_structure"
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
            "Design terrain, biomes, features and world-specific architecture yourself. New guided worlds require at least two distinct building groups, " +
            "one independent structure, and at least one monumental theme-defining group. Read contract/architecture, plan required/optional members, " +
            "and light occupied interiors explicitly. Do not reuse a default style across players. The player's prompt is the standard " +
            "for land/ocean balance, scale, relief, height, caves, rivers, lakes, ocean depth, biome count and " +
            "biome distribution. `howToDesign` gives the order those decisions go in and the joins where two " +
            "documents have to agree; `contracts` holds the field vocabulary, one document each for terrain, " +
            "biome, feature, structure, draw and architecture planning, and $CONTRACT_TOOL hands any of them back if you need to re-read one; " +
            "`climatePlacement` describes optional semantic presets plus the exact raw axes. Worldsmith " +
            "validates what you send and " +
            "reports exactly what is wrong, so a rejected pack is a repair job rather than a restart: change " +
            "only what the diagnostics name and send the whole document again.\n\n" +
            "Use the Java Draw SDK as the primary geometry route: submit source through MCP, wait for the MC-side worker, " +
            "inspect returned model images, revise, then reference frozen drawing ids from structure metadata. The AI needs only MCP; " +
            "the player needs no Python, JDK or external compiler. One in-game confirmation is required per authoring session after restart. " +
            "SDK geometry, architecture composition and world deployment remain separate modules.\n\n" +
            "complete=true means the content-addressed pack reads back and passes Core checks, native structure export/readback, " +
            "Minecraft's full data-pack reload and preset activation in the current Create World context. WAITING_NATIVE_CONTEXT " +
            "requires the player to open Create World; report that action and pause rather than polling indefinitely. " +
            "A model preview is not a gameplay screenshot. A published plan is not a created/played world and landmarkInstancesVerified stays false. " +
            "Report the pack name, biome count and saved location only after the final native receipt."


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
            tool = STYLE_LIST_TOOL,
            instruction =
                "Call it once and read the one-line descriptions. A style says which values make a world feel " +
                    "like a particular kind of place, which is the one thing you cannot work out from the " +
                    "contracts alone. Pick the one that matches the player's prompt, or none if none does.",
        ),
        WorkflowStep(
            order = 3,
            tool = STYLE_GET_TOOL,
            instruction =
                "Read the style you picked. When none matched, read `general`: it is the method for deriving a " +
                    "world from an arbitrary prompt, and using it is the normal path rather than a failure.",
        ),
        WorkflowStep(
            order = 4,
            tool = ANALYZE_TOOL,
            instruction =
                "Send the terrain and biome documents you are about to write. A climate box says where a biome " +
                    "may be, never how much of the world that is, and the axes are bell-shaped noise, so a box " +
                    "that looks half the size of another is often a quarter of it. Fix anything the report calls " +
                    "never chosen or dominant, then run it again before writing.",
        ),
        WorkflowStep(
            order = 5,
            tool = ARCHITECTURE_TOOL,
            instruction = "Read contract/architecture. Derive multiple groups from this world's theme, each with a centerpiece, required and optional roles, distinct purpose/layout and discovery intent. Include a monumental LANDMARK group and independent structures. Submit the plan before authoring executable members.",
        ),
        WorkflowStep(
            order = 6,
            tool = "worldsmith_build_drawing",
            instruction = "Read contract/draw. Submit a complete Java 21 DrawProgram plus optional helper files, a stable build name, a new requestId and 1..8 seeds. Build each logical building independently. Required session approval happens inside Minecraft, never through an MCP approval tool.",
        ),
        WorkflowStep(
            order = 7,
            tool = "worldsmith_get_drawing_job",
            instruction = "Query the returned jobId. WAITING_APPROVAL needs a player action. Poll queued/active jobs at a reasonable interval; on errors inspect file/line/column diagnostics and retry with a new requestId. Failure never authorizes silently substituting an older drawing.",
        ),
        WorkflowStep(
            order = 8,
            tool = "worldsmith_preview_drawing",
            instruction = "Inspect isometric, front/back or slice model PNG content from each successful frozen drawing. Fix silhouette, openings, floors and distribution of lights by rebuilding. Preview uses simplified cube shapes/materials/light, not the Minecraft renderer.",
        ),
        WorkflowStep(
            order = 9,
            tool = STRUCTURE_TOOL,
            instruction = "Reference drawing.variants in the planned group definitions and standalone structures. Keep metadata in original drawing coordinates; use old JSON build operations only for compatibility. Declare lighting for every root and child blueprint: READABLE occupied spaces with actual light fixtures, or EXTERIOR_ONLY for open designs. Submit complete definitions one at a time. Required ports must produce the member counts promised by the plan.",
        ),
        WorkflowStep(
            order = 10,
            tool = ARCHITECTURE_VALIDATE_TOOL,
            instruction = "Check the whole architecture plan against the executable drafts. Repair missing members, landmark scale, unclassified structures and dark rooms. This checks all compiled variants; it is not a gameplay or aesthetic proof.",
        ),
        WorkflowStep(
            order = 11,
            tool = WRITE_TOOL,
            instruction =
                "Send the whole pack with this sessionId. Preserve the template's technical terrain envelope, " +
                    "but replace its shape with a procedural intent chosen from the player's prompt; design the " +
                    "biomes and features to match it. Include the planned architecture and all definitions, or omit structures to use the session drafts. " +
                    "Architecture policy is checked again before any files are saved. A reply carrying " +
                    "error diagnostics means nothing was saved, so repair those exact problems and call it again.",
        ),
        WorkflowStep(
            order = 12,
            tool = FINISH_TOOL,
            instruction =
                "Call it with this sessionId. It re-reads the pack from disk and re-validates it. Stop when it " +
                    "answers complete=true. Native pending phases need a later check; WAITING_NATIVE_CONTEXT needs the player to open Create World. Native failures require repair, not an automatic retry loop.",
        ),
    )
}

/** Atomically persisted guided drafts. Completed and unfinished records are retained;
 * capacity exhaustion is reported instead of silently evicting authoring work.
 * Drawing job recovery is separate and never restores source-execution approval. */
class WorkflowSessions @JvmOverloads constructor(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val directory: Path? = null,
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1" }
    }

    private val sessions = LinkedHashMap<String, WorkflowSession>()
    init {
        directory?.let { root ->
            Files.createDirectories(root)
            Files.list(root).use { files -> files.filter { it.fileName.toString().matches(Regex("[a-f0-9]{32}\\.json")) }.sorted().forEach { path ->
                require(sessions.size<128) { "Session store exceeds 128 records" }
                val s=WorldsmithJson.decode<WorkflowSession>(Files.readString(path))
                require(path.fileName.toString()==s.id+".json"); sessions[s.id]=s
            } }
        }
    }

    @Synchronized
    fun begin(prompt: String): WorkflowSession {
        require(sessions.values.count { !it.finished } < maxSessions && sessions.size<128) { "Active session capacity reached; resume existing drafts rather than discarding them" }
        val session = WorkflowSession(idFactory(), prompt)
        sessions[session.id] = session
        save(session)
        return session
    }

    @Synchronized
    fun find(id: String): WorkflowSession? = sessions[id]

    /** Returns null when the id is unknown, which the caller reports rather than throws. */
    @Synchronized
    fun recordPack(id: String, packId: String): WorkflowSession? = update(id) {
        it.copy(packId = packId, finished = it.finished && it.packId == packId)
    }

    @Synchronized
    fun putStructure(id: String, structure: WorldStructureDefinition): WorkflowSession? = update(id) {
        require(it.structures.size < 48 || structure.id in it.structures) { "Structure draft limit reached" }
        if(it.structures[structure.id]==structure)it else it.copy(structures = it.structures + (structure.id to structure), packId = null, finished = false, revision=it.revision+1)
    }

    @Synchronized
    fun planArchitecture(id: String, plan: StructureArchitecture): WorkflowSession? = update(id) {
        if(it.architecture==plan)it else it.copy(architecture = plan, packId = null, finished = false, revision=it.revision+1)
    }

    @Synchronized
    fun finish(id: String): WorkflowSession? = update(id) { it.copy(finished = true) }

    /** A slow validation/export may not commit over a concurrent edit or restored revision. */
    @Synchronized fun recordPackAtRevision(id:String,packId:String,revision:Long):WorkflowSession? {
        if(sessions[id]?.revision!=revision)return null
        return recordPack(id,packId)
    }
    @Synchronized fun finishAtRevision(id:String,packId:String,revision:Long):WorkflowSession? {
        val current=sessions[id] ?: return null
        if(current.revision!=revision || current.packId!=packId)return null
        return finish(id)
    }

    @Synchronized
    fun size(): Int = sessions.size
    @Synchronized fun all(): List<WorkflowSession> = sessions.values.toList()
    @Synchronized fun invalidate(id:String):WorkflowSession? = update(id) { it.copy(packId=null,finished=false,revision=it.revision+1) }

    private fun update(id: String, change: (WorkflowSession) -> WorkflowSession): WorkflowSession? {
        val current = sessions[id] ?: return null
        val updated = change(current)
        sessions[id] = updated
        save(updated)
        return updated
    }

    private fun save(session:WorkflowSession) { directory?.let { DrawingHost.atomic(it.resolve(session.id+".json"),WorldsmithJson.encode(session).toByteArray(Charsets.UTF_8)) } }

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 8
    }
}
