package com.wjz.worldsmith.core.mcp

import java.util.UUID
import com.wjz.worldsmith.core.structure.WorldStructureDefinition
import com.wjz.worldsmith.core.structure.StructureArchitecture
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.structure.StructureInteraction
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.drawhost.DrawingHost
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.content.ExistingWorldContentModules

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
    val archived:Boolean=false,
    val contentModules: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    val contentAssets: Map<String, com.wjz.worldsmith.core.content.ContentAsset> = emptyMap(),
    val mode: WorkflowMode = WorkflowMode.WORLDGEN_ONLY,
    val designPlan: WorldDesignPlan? = null,
    val lastWriteFailure: PackValidationReceipt? = null,
)

/** Sessions store definitions, not a module envelope; derive the minimum module schema when assembling one. */
internal fun WorkflowSession.structureLibrary(): StructureLibrary {
    val definitions = structures.values.toList()
    val hasBossSpawner = definitions.any { structure -> (listOf(structure.blueprint) + structure.assembly?.pieces.orEmpty().values)
        .any { blueprint -> blueprint.interactions.any { it is StructureInteraction.BossSpawner } } }
    return StructureLibrary(schemaVersion = if (hasBossSpawner) 2 else 1, structures = definitions, architecture = architecture)
}

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
            "Start with one persistent WorldTheme: premise, player role, world rules, conflict and narrative beats linked to actual content. " +
            "Use worldsmith_get_content_contract for theme/blocks/creatures, then author real PNG textures and typed content modules. " +
            "worldsmith_put_content_modules and texture tools use expectedRevision from worldsmith_get_content_draft; all edits share architecture's revision. " +
            "Custom blocks use worldsmith:content/<id>, fixed native profiles and immutable world slots. Creatures use grounded native hosts, cuboid rigs and bounded server behaviors. " +
            "A bounded linear quests module can link narrative beats to kill and item-delivery objectives; narrative beats alone are not executable quests. Achievements remain a future module. New bundles use format 5; formats 3 and 4 remain read-only. " +
            "Design terrain, biomes, features and world-specific architecture yourself. New guided worlds require at least two distinct building groups, " +
            "one independent structure, and at least one monumental theme-defining group. Read contract/architecture, plan required/optional members, " +
            "and light occupied interiors explicitly. Use designGuide to translate the theme into form and playable spaces; numerical gates are not design targets. " +
            "Do not reuse a default style across players. The player's prompt is the standard " +
            "for land/ocean balance, scale, relief, height, caves, rivers, lakes, ocean depth, biome count and " +
            "biome distribution. Full-mode `howToDesign` gives the order those decisions go in and the joins where two " +
            "documents have to agree; `contracts` holds the field vocabulary, one document each for terrain, " +
            "biome, feature, structure, draw and architecture planning (indexes in summary mode). Use $CONTRACT_TOOL for full text or a named section; " +
            "`climatePlacement` describes optional semantic presets plus the exact raw axes. Worldsmith " +
            "validates what you send and " +
            "reports exactly what is wrong, so a rejected pack is a repair job rather than a restart: change " +
            "only what the diagnostics name and send the whole document again.\n\n" +
            "Use the Java Draw SDK as the primary geometry route: submit source through MCP, wait for the MC-side worker, " +
            "inspect returned model images, revise, then reference frozen drawing ids from structure metadata. The AI needs only MCP; " +
            "the player needs no Python, JDK or external compiler. Source confirmation follows the host setting; WAITING_APPROVAL needs a player action. " +
            "SDK geometry, architecture composition and world deployment remain separate modules.\n\n" +
            "complete=true means the content-addressed pack reads back and passes Core checks, native structure export/readback, " +
            "Minecraft's full data-pack reload and preset activation in the current Create World context. WAITING_NATIVE_CONTEXT " +
            "requires the player to open Create World; report that action and pause rather than polling indefinitely. " +
            "A model preview is not a gameplay screenshot. A published plan is not a created/played world and landmarkInstancesVerified stays false. " +
            "Report the pack name, biome count and saved location only after the final native receipt."


    val PROCEDURE: List<WorkflowStep> = (listOf(
        WorkflowStep(0,"worldsmith_get_content_framework","Read installed modules and capacity/lifecycle boundaries; no planned module may be silently treated as implemented."),
        WorkflowStep(0,"worldsmith_get_content_contract","Read theme, blocks, creatures, items and quests contracts. Establish one shared premise/player role/rules/conflict and linked narrative beats before designing content."),
        WorkflowStep(0,"worldsmith_put_content_modules","Commit complete theme and initial content drafts at expectedRevision. Use create_pixel_texture or put_texture_asset for actual PNGs, inspect their previews and bind the returned hash to custom blocks/creature rigs. Record each returned revision. Draft links may be repaired incrementally; all links must resolve at publication."),
    ) + listOf(
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
            instruction = "Read contract/architecture including creative-brief and form-function-and-family. Translate this theme into silhouette, structural/material language and spatial experience in existing plan fields. Differentiate groups by function, plan, section and massing, not only names/colours. Compose a focal LANDMARK, supporting places and independent structures. Submit the plan before geometry.",
        ),
        WorkflowStep(
            order = 6,
            tool = "worldsmith_build_drawing",
            instruction = "Read contract/draw section authoring-workbench and architecture section visual-quality-loop. Register source targets and prefer StructureProgram (DrawProgram remains compatible). Develop one representative main building through clay massing, facade rhythm/depth, usable interiors, integrated lighting and selective detail; inspect between passes before expanding families. Reuse craft components, not one resized hall for every function. Confirmation follows the host setting.",
        ),
        WorkflowStep(
            order = 7,
            tool = "worldsmith_get_drawing_job",
            instruction = "Query the returned jobId. WAITING_APPROVAL needs a player action. Poll queued/active jobs at a reasonable interval; on errors inspect file/line/column diagnostics and retry with a new requestId. Failure never authorizes silently substituting an older drawing.",
        ),
        WorkflowStep(
            order = 8,
            tool = "worldsmith_preview_drawing",
            instruction = "Inspect actual PNG content: opposite isometrics/top in clay for massing, all elevations in material mode, then occupied-storey cutaways. Note the largest visible flaw, make a specific repair and compare the SAME returned frame/view/mode. Review worldsmith_preview_assembly for group hierarchy, spacing and approach too. Preview approximates block shapes/colours; machine checks and image availability alone never prove visual quality or in-game appearance.",
        ),
        WorkflowStep(
            order = 9,
            tool = STRUCTURE_TOOL,
            instruction = "Run worldsmith_preflight_structure and repair spatial diagnostics; model images remain available on semantic failure. Reference authored.variants to import geometry-linked semantic data, or drawing.variants for legacy manual metadata. Use worldsmith_put_architecture_draft for atomic related plan/member changes. Declare lighting for every root and child blueprint: READABLE occupied spaces with actual light fixtures, or EXTERIOR_ONLY for open designs. Submit complete definitions one at a time. Required ports must produce the member counts promised by the plan.",
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
                "Read the current shared draft revision and send sessionId plus expectedRevision. Supply theme/terrain/biomes/features inline or use committed content drafts; blocks/creatures/items and verified PNG assets are frozen with the same bundle. Preserve the template's technical terrain envelope, " +
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
    )).mapIndexed { index, step -> step.copy(order=index+1) }

    fun overview(mode: WorkflowMode, summary: Boolean): String = when (mode) {
        WorkflowMode.STANDALONE -> "This is a focused artifact session. Build, inspect and export the requested drawing or creature; do not manufacture terrain, architecture groups, items or quests merely to satisfy a world-publication workflow. Use the current progress and preserve successful jobs. A preview is not a gameplay screenshot."
        WorkflowMode.COMPLETE_WORLD -> if (summary) "This is an explicit COMPLETE_WORLD promise. First persist a named WorldDesignPlan, then follow worldsmith_get_generation_progress rather than restarting a fixed checklist. All declared biomes, buildings, blocks, items, creatures, main-line quests and actual Boss profiles must exist and have real usage links before publication. Shared expectedRevision protects every edit. The Mod does not call an LLM or provide an image model; any capable MCP client can author its data and PNGs. Frozen-content checks and native activation are separate receipts."
            else "COMPLETE_WORLD additionally requires a persisted WorldDesignPlan and verified coverage of its named targets, relationships and Boss quests. Empty optional libraries are not completion in this mode. Follow the current generation progress for the next missing action.\n\n$OVERVIEW"
        WorkflowMode.WORLDGEN_ONLY -> if (summary) "This is a WORLDGEN_ONLY guided run: theme, terrain, biomes, features and the existing architecture quality policy. Other content modules may be empty unless explicitly requested. Use progress for missing work, share expectedRevision across all edits, and preserve existing drawings. For the full biomes/buildings/blocks/items/creatures/quests/Boss promise, begin with mode=COMPLETE_WORLD; for one artifact use STANDALONE. Native finish is distinct from a preview or Core save."
            else OVERVIEW
    }

    fun procedure(mode: WorkflowMode, summary: Boolean): List<WorkflowStep> {
        if (mode == WorkflowMode.WORLDGEN_ONLY && !summary) return PROCEDURE
        val steps = when (mode) {
            WorkflowMode.STANDALONE -> listOf(
                CONTRACT_TOOL to "Read the drawing or creature authoring contract for the requested artifact; no world modules are required.",
                "worldsmith_build_drawing" to "Continue a source project or submit the requested drawing; creature recipes use worldsmith_build_creature instead.",
                "worldsmith_get_generation_progress" to "Resume the owned job or artifact indicated by progress; ask for a required host approval instead of replacing it.",
                "worldsmith_preview_drawing" to "Inspect the actual result, repair its largest visible flaw, and export the artifact when requested.",
            )
            WorkflowMode.COMPLETE_WORLD -> listOf(
                "worldsmith_get_content_contract" to "Read module=world_design and the compact cross-domain contract pointers.",
                "worldsmith_put_world_design_plan" to "Commit prompt-specific names, roles, real relationship promises and Boss/quest links at expectedRevision.",
                "worldsmith_get_generation_progress" to "Follow the highest-priority current gap. Read full contracts only for the domain being authored.",
                "worldsmith_put_content_modules" to "Author theme/worldgen/content/quests coherently. Build real textures and creature rigs through the linked authoring tools; keep returned asset identities.",
                STRUCTURE_TOOL to "Use the established SDK, preview, architecture and preflight loops for every planned building; preserve current jobs and shared revisions.",
                WRITE_TOOL to "Freeze the current revision. Publication verifies named targets and actual compiled-material/reward/spawn/objective links, not merely plan declarations.",
                FINISH_TOOL to "Request the native receipt. WAITING_NATIVE_CONTEXT is a player action, not a reason to rebuild already-frozen content.",
            )
            WorkflowMode.WORLDGEN_ONLY -> listOf(
                "worldsmith_get_generation_progress" to "Resume the current missing worldgen or architecture step instead of repeating completed work.",
                TEMPLATE_TOOL to "Read technical field shapes when needed; derive all design choices from the player's prompt.",
                "worldsmith_put_content_modules" to "Commit the theme and worldgen drafts at the shared expectedRevision; nonrequested item/creature/quest modules may remain empty.",
                ARCHITECTURE_TOOL to "Apply the existing guided architecture quality contract and author its real structures.",
                WRITE_TOOL to "Freeze the exact current draft and fix named diagnostics.",
                FINISH_TOOL to "Finish only after the native receipt for the saved worldgen pack.",
            )
        }
        return steps.mapIndexed { index, (tool, instruction) -> WorkflowStep(index + 1, tool, instruction) }
    }
}

/** Atomically persisted guided drafts. Completed and unfinished records are retained;
 * capacity exhaustion is reported instead of silently evicting authoring work.
 * Drawing job recovery is separate and never restores source-execution approval. */
class WorkflowSessions @JvmOverloads constructor(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val directory: Path? = null,
    private val writer:(Path,ByteArray)->Unit = com.wjz.worldsmith.core.drawhost.DurableFiles::write,
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1" }
    }

    private val sessions = LinkedHashMap<String, WorkflowSession>()
    private val recoveryProblems=mutableListOf<String>()
    val recoveryDiagnostics:List<String> get()=recoveryProblems.toList()
    init {
        directory?.let { root ->
            Files.createDirectories(root)
            Files.list(root).use { files -> files.filter { it.fileName.toString().matches(Regex("[a-f0-9]{32}\\.json")) }.sorted().forEach { path ->
                require(sessions.size<128) { "Session store exceeds 128 records" }
                runCatching {
                    require(Files.size(path)<=8*1024*1024 && !Files.isSymbolicLink(path))
                    val s=WorldsmithJson.decode<WorkflowSession>(Files.readString(path))
                    require(path.fileName.toString()==s.id+".json"); sessions[s.id]=s
                }.onFailure {recoveryProblems+="${path.fileName}: ${it.message}; file preserved"}
            } }
        }
    }

    @Synchronized @JvmOverloads
    fun begin(prompt: String, mode: WorkflowMode = WorkflowMode.WORLDGEN_ONLY): WorkflowSession {
        require(sessions.values.count { !it.finished } < maxSessions && sessions.size<128) { "Active session capacity reached; resume existing drafts rather than discarding them" }
        val session = WorkflowSession(idFactory(), prompt, mode = mode)
        save(session)
        sessions[session.id] = session
        return session
    }

    @Synchronized
    fun find(id: String): WorkflowSession? = sessions[id] ?: archivedRecord(id)

    /** Returns null when the id is unknown, which the caller reports rather than throws. */
    @Synchronized
    fun recordPack(id: String, packId: String): WorkflowSession? = update(id) {
        it.copy(packId = packId, finished = it.finished && it.packId == packId, revision=it.revision+if(it.packId==packId)0 else 1, lastWriteFailure = null)
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
    /** Inline publication inputs become the exact durable draft, so resume never calls a saved module "missing". */
    @Synchronized fun recordPackSnapshotAtRevision(id: String, pack: WorldsmithPack, revision: Long): WorkflowSession? {
        val current = sessions[id] ?: return null
        if (current.revision != revision) return null
        val modules = ExistingWorldContentModules.input(pack).modules - "structures"
        val structures = pack.structures.structures.associateBy { it.id }
        val changed = current.packId != pack.manifest.id || current.contentModules != modules || current.structures != structures || current.architecture != pack.structures.architecture
        val saved = current.copy(packId = pack.manifest.id, finished = current.finished && current.packId == pack.manifest.id,
            contentModules = modules, structures = structures, architecture = pack.structures.architecture,
            revision = current.revision + if (changed) 1 else 0, lastWriteFailure = null)
        save(saved); sessions[id] = saved
        return saved
    }
    /** Validation observations do not mutate authored content or advance its CAS revision. */
    @Synchronized fun recordWriteFailureAtRevision(id: String, receipt: PackValidationReceipt): WorkflowSession? {
        val current = sessions[id] ?: return null
        if (current.archived || current.revision != receipt.revision) return null
        val bounded = PackValidationReceipt.bounded(receipt.revision, receipt.diagnostics, receipt.displayName, receipt.description, receipt.inlineInputs)
            .copy(diagnosticCount = receipt.diagnosticCount)
        val saved = current.copy(lastWriteFailure = bounded)
        save(saved); sessions[id] = saved
        return saved
    }
    @Synchronized fun finishAtRevision(id:String,packId:String,revision:Long):WorkflowSession? {
        val current=sessions[id] ?: return null
        if(current.revision!=revision || current.packId!=packId)return null
        return finish(id)
    }

    @Synchronized
    fun size(): Int = sessions.size
    @Synchronized fun all(): List<WorkflowSession> = sessions.values.toList()
    @Synchronized fun archivedSessions():List<WorkflowSession> {
        val root=directory?.resolve("archive") ?: return emptyList();if(!Files.isDirectory(root))return emptyList()
        return Files.list(root).use {it.filter {p->p.fileName.toString().matches(Regex("[a-f0-9]{32}\\.json"))}.sorted().map {p->archivedRecord(p.fileName.toString().removeSuffix(".json"))}.filter {it!=null}.toList().filterNotNull()}
    }
    @Synchronized fun archive(id:String):WorkflowSession {
        val current=sessions[id] ?: return requireNotNull(archivedRecord(id)) {"Unknown session"}
        val root=requireNotNull(directory) {"Archiving requires a persistent session store"}
        val archived=current.copy(archived=true);writer(root.resolve("archive/$id.json"),WorldsmithJson.encode(archived).toByteArray(Charsets.UTF_8))
        Files.deleteIfExists(root.resolve("$id.json"));sessions.remove(id);return archived
    }
    @Synchronized fun resume(id:String):WorkflowSession? {
        sessions[id]?.let {return it}
        val current=archivedRecord(id) ?: return null
        require(sessions.size<128 && (current.finished || sessions.values.count {!it.finished}<maxSessions)) {"Active session capacity reached"}
        val resumed=current.copy(archived=false);save(resumed);sessions[id]=resumed
        directory?.let {Files.deleteIfExists(it.resolve("archive/$id.json"))};return resumed
    }
    /** Architecture and all changed definitions share one durable revision boundary. */
    @Synchronized fun putArchitectureDraft(id:String,expectedRevision:Long,architecture:StructureArchitecture?,structures:List<WorldStructureDefinition>,remove:List<String> = emptyList()):WorkflowSession? = update(id) {
        require(it.revision==expectedRevision) {"DRAFT_REVISION_CONFLICT: expected $expectedRevision, current ${it.revision}"}
        val next=it.structures-remove.toSet()+structures.associateBy {s->s.id}
        require(next.size<=48 && structures.map {s->s.id}.distinct().size==structures.size)
        if(next==it.structures && (architecture ?: it.architecture)==it.architecture)it else
            it.copy(architecture=architecture ?: it.architecture,structures=next,revision=it.revision+1,packId=null,finished=false)
    }
    @Synchronized fun invalidate(id:String):WorkflowSession? = update(id) { it.copy(packId=null,finished=false,revision=it.revision+1) }

    /** A complete-world promise is durable and shares the same revision as every content mutation. */
    @Synchronized fun putDesignPlan(id: String, expectedRevision: Long, plan: WorldDesignPlan, mode: WorkflowMode): WorkflowSession? = update(id) {
        require(it.revision == expectedRevision) { "DRAFT_REVISION_CONFLICT: expected $expectedRevision, current ${it.revision}" }
        require(it.mode != WorkflowMode.COMPLETE_WORLD || mode == WorkflowMode.COMPLETE_WORLD) { "A complete-world run keeps its declared scope; start an explicit focused run instead of silently downgrading it" }
        val errors = WorldDesignPlans.validate(plan, mode == WorkflowMode.COMPLETE_WORLD)
        require(errors.isEmpty()) { errors.take(16).joinToString("; ") { d -> "${d.path}: ${d.message}" } }
        val frozen = WorldDesignPlans.freeze(plan)
        if (it.designPlan == frozen && it.mode == mode) it else it.copy(designPlan = frozen, mode = mode, revision = it.revision + 1, packId = null, finished = false)
    }

    /** All world modules and asset handles share the architecture/publication revision. */
    @Synchronized fun putContent(id:String, expectedRevision:Long,
        modules:Map<String,kotlinx.serialization.json.JsonObject> = emptyMap(),
        assets:Map<String,com.wjz.worldsmith.core.content.ContentAsset> = emptyMap(),
        removeAssets:List<String> = emptyList()):WorkflowSession? = update(id) {
        require(it.revision==expectedRevision) { "DRAFT_REVISION_CONFLICT: expected $expectedRevision, current ${it.revision}" }
        require(modules.keys.all { key -> key in setOf("theme","terrain","features","biomes","blocks","creatures","items","quests") }) { "Use the architecture tools for structures; unknown content modules are not installed" }
        val nextModules=it.contentModules+modules
        val nextAssets=(it.contentAssets-removeAssets.toSet())+assets
        require(nextAssets.size<=com.wjz.worldsmith.core.content.ContentAssetValidation.MAX_ASSETS)
        require(nextAssets.values.sumOf { a->requireNotNull(a.byteLength) }<=com.wjz.worldsmith.core.content.ContentAssetValidation.MAX_TOTAL_BYTES)
        if(nextModules==it.contentModules && nextAssets==it.contentAssets) it else
            it.copy(contentModules=nextModules,contentAssets=nextAssets,revision=it.revision+1,packId=null,finished=false)
    }

    private fun update(id: String, change: (WorkflowSession) -> WorkflowSession): WorkflowSession? {
        val current = sessions[id] ?: return null
        val updated = change(current)
        save(updated)
        sessions[id] = updated
        return updated
    }

    private fun save(session:WorkflowSession) {
        val bytes=WorldsmithJson.encode(session).toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024) { "Session document budget exceeded; frozen assets remain outside the draft" }
        directory?.let { writer(it.resolve(session.id+".json"),bytes) }
    }
    private fun archivedRecord(id:String):WorkflowSession? {
        if(!id.matches(Regex("[a-f0-9]{32}")))return null
        val p=directory?.resolve("archive/$id.json") ?: return null;if(!Files.isRegularFile(p))return null
        return runCatching {require(Files.size(p)<=8*1024*1024&&!Files.isSymbolicLink(p));WorldsmithJson.decode<WorkflowSession>(Files.readString(p)).also {require(it.id==id)}}
            .getOrElse {throw IllegalStateException("Archived session $id is unreadable; file preserved: ${it.message}",it)}
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 8
    }
}
