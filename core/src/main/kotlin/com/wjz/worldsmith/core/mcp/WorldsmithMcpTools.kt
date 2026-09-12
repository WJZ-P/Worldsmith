package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.draw.DrawPreview
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import java.util.Base64
import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.analysis.BiomeDistributionAnalyzer
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.HumidityBand
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithPackFiles
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.prompt.ClasspathPromptTemplateRepository
import com.wjz.worldsmith.core.prompt.ClasspathStyleCatalog
import com.wjz.worldsmith.core.prompt.PromptTemplateRepository
import com.wjz.worldsmith.core.prompt.StyleCatalog
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Worldsmith's local MCP tool catalog.
 *
 * The catalog is a guided flow rather than a bag of verbs. [WorldsmithWorkflow]
 * fixes the order, `worldsmith_begin_world` hands that order to the agent along
 * with the rules a pack has to satisfy, and `worldsmith_finish_world` is the
 * only tool allowed to say the run is over.
 */
class WorldsmithMcpTools @JvmOverloads constructor(
    packDirectory: Path,
    private val runtimeInfo: Supplier<Map<String, String>> = Supplier { emptyMap() },
    private val packFinished: Consumer<String> = Consumer { },
    private val templates: PromptTemplateRepository = ClasspathPromptTemplateRepository(),
    private val styles: StyleCatalog = ClasspathStyleCatalog(),
    private val sessions: WorkflowSessions = WorkflowSessions(directory = packDirectory.resolveSibling("drafts")),
    private val drawings: DrawingHost = DrawingHost(packDirectory.resolveSibling("drawing-work")),
    private val publicationHost: PublicationHost = PublicationHost.UNAVAILABLE,
    private val drawingExport: DrawingExportHost? = null,
    private val nativeChecks:StructureNativeHost? = null,
) {
    private val packDirectory = packDirectory.toAbsolutePath().normalize()

    private val previewService=StructurePreviewService()
    private val drawingService=DrawingMcpService(drawings,sessions,previewService)
    private val structureService=StructureMcpService(this.packDirectory.resolveSibling("structure-previews"),drawings,sessions,nativeChecks,previewService,drawingService)
    private val packStore=ManagedPackStore(this.packDirectory)
    private val publicationService=PackPublicationService(packStore,sessions,publicationHost,drawingService)
    private val contentService=WorldContentMcpService(packStore,nativeChecks!=null,sessions,this.packDirectory.resolveSibling("content-assets"),drawings::list)
    private val materialPalette=PreviewMaterialPalette(contentService::textureBytes)
    private val creatureAuthoringService=CreatureAuthoringMcpService(sessions,contentService,this.packDirectory.resolveSibling("creature-work"))
    private val resourcePackExchange=ResourcePackExchange(this.packDirectory)
    private val resourcePackService=ResourcePackMcpService(resourcePackExchange,contentService)
    private val progressGateway=GenerationProgressGateway(sessions::all,drawings::liveJobs)
    init {
        drawings.completionChecks=structureService::completed;drawingService.inspectDrawing=structureService::inspectDrawing
        previewService.paletteForSession = { sid -> materialPalette.resolve(sessions.find(sid)) }
    }

    /** Native UI entry point: immutable, bounded data only; callers perform projection off the render thread. */
    @JvmOverloads fun progressSnapshot(preferredSessionId: String? = null): GenerationProgressSnapshot = progressGateway.snapshot(preferredSessionId)

    fun all(): List<McpTool> = rawTools().map(progressGateway::observe)

    private fun rawTools(): List<McpTool> = resourcePackService.tools()+contentService.tools()+creatureAuthoringService.tools()+drawingService.tools()+structureService.tools()+listOf(
        McpTool("worldsmith_get_generation_progress_view", "Read the native progress view", "Read the same bounded in-memory session catalog and progress view shown in Create World. No draft edits, selection changes, source execution or archived-file scan.",
            objectSchema(mapOf("sessionId" to stringSchema()),emptyList()),true,handler={ a ->
                McpToolResult.success(encode(progressSnapshot((a["sessionId"] as? JsonPrimitive)?.contentOrNull)).jsonObject)
            }),
        McpTool("worldsmith_list_sessions", "List saved world drafts", "List persistent sessions without executing sources.", objectSchema(mapOf("includeArchived" to buildJsonObject {put("type","boolean")}),emptyList()), true, handler=::listSavedSessions),
        McpTool("worldsmith_resume_session", "Resume a world draft", "Restore the saved scope/plan and current next action without executing code. detail=summary omits full structures and job logs while retaining progress and cross-domain pointers.", objectSchema(mapOf("sessionId" to stringSchema(),"detail" to buildJsonObject {put("type","string");put("enum",encode(listOf("summary","full")))}),listOf("sessionId")), true, handler=::resumeSession),
        McpTool("worldsmith_build_drawing", "Build a drawing with Java", "Build Java 21 StructureProgram or DrawProgram, using a source project target or inline sources. Runs in the MC-side worker, not in worldgen. Returns a job id; use a new requestId for each revision. Develop one representative building and inspect its model before expanding a family.", drawingBuildSchema(), false, handler=drawingService::build),
        McpTool("worldsmith_get_drawing_job", "Drawing job status", "Read compile/draw/validation progress, diagnostics and frozen drawing ids.", sessionSchema("jobId"), true, handler={ a->
            McpToolResult.success(drawingService.job(drawings.get(requiredString(a,"sessionId"),requiredString(a,"jobId"))))
        }),
        McpTool("worldsmith_cancel_drawing_job", "Cancel drawing job", "Stop only this authoring job; keep saved drafts and successful drawings.", sessionSchema("jobId"), false, handler={ a->
            McpToolResult.success(drawingService.job(drawings.cancel(requiredString(a,"sessionId"),requiredString(a,"jobId"))))
        }),
        McpTool("worldsmith_preview_drawing", "Preview frozen drawing", "Return up to four actual PNG images: elevations, top, opposite isometrics or a slice. Use renderMode=clay to study massing, fixed frame to compare revisions, and cutaway+sliceY to inspect interiors. Simplified model, not game rendering or an aesthetic score.",
            objectSchema(mapOf("sessionId" to stringSchema(),"drawingId" to stringSchema())+StructurePreviewService.optionSchema(),listOf("sessionId","drawingId")),true,handler=drawingService::preview),
        McpTool("worldsmith_export_drawing","Export standalone structure NBT","Export a frozen drawing as native structure NBT, including drawings too large for worldgen deployment. Returns a binary MCP resource; does not execute source or load chunks.",sessionSchema("drawingId"),true,handler={ a->
            val exporter=drawingExport ?: return@McpTool McpToolResult.error("Native drawing export is unavailable on this host")
            val artifact=drawings.artifact(requiredString(a,"sessionId"),requiredString(a,"drawingId"),true)
            val sid=requiredString(a,"sessionId")
            val blocks=sessions.find(sid)?.contentModules?.get("blocks")?.let {decode<CustomBlockLibrary>(it)} ?: CustomBlockLibrary()
            val bytes=exporter.exportContent(drawings.drawing(artifact),sid,blocks);require(bytes.size<=DrawSnapshotCodec.MAX_BYTES) { "Native export exceeds resource limit" }
            McpToolResult.success(buildJsonObject {put("drawingId",artifact.id);put("sha256",DrawSnapshotCodec.hash(bytes));put("bytes",bytes.size)},resources=listOf(McpBinaryResource("worldsmith://drawing/${artifact.id}.nbt",Base64.getEncoder().encodeToString(bytes))))
        }),
        McpTool(
            name = WorldsmithWorkflow.BEGIN_TOOL,
            title = "Begin a Worldsmith world",
            description =
                "Start here. Takes the player's description of a world and returns the whole procedure for " +
                    "building it: the design rules, optional semantic placement presets, exact climate axes, " +
                    "and a sessionId that carries the run through to ${WorldsmithWorkflow.FINISH_TOOL}.",
            inputSchema = beginWorldSchema(),
            readOnly = false,
            idempotent = false,
            handler = ::beginWorld,
        ),
        McpTool(
            name = "worldsmith_put_structure", title = "Submit a structure draft",
            description = "Validate and replace one complete structure in a generation session. Preserves the other drafts. No Minecraft compilation is run.",
            inputSchema = objectSchema(mapOf("sessionId" to buildJsonObject { put("type", "string") }, "structure" to documentSchema("WorldStructureDefinition: id, blueprint and placement; read contract/structure.")), listOf("sessionId", "structure")),
            readOnly = false, handler = ::putStructure,
        ),
        McpTool(
            name = WorldsmithWorkflow.ARCHITECTURE_TOOL, title = "Plan this world's architecture",
            description = "Submit the world-specific architecture plan before buildings: at least two distinct groups, an independent structure and one monumental LANDMARK group. Declare centerpieces, required/optional member roles and theme/layout/discovery intent. Read contract/architecture first. Replacing a plan preserves drafts and invalidates the previous publication.",
            inputSchema = objectSchema(mapOf("sessionId" to buildJsonObject { put("type", "string") }, "architecture" to documentSchema("StructureArchitecture: policyVersion, worldTheme, groups, standalone; see contract/architecture.")), listOf("sessionId", "architecture")),
            readOnly = false, handler = ::planArchitecture,
        ),
        McpTool(
            name = WorldsmithWorkflow.ARCHITECTURE_VALIDATE_TOOL, title = "Check the full architecture program",
            description = "Check the architecture plan against executable structure drafts: required/optional member counts in every variant, landmark scale, independent structures and readable interiors. Does not place structures or run the Minecraft light engine. Optional structures replaces the draft library for this read-only check.",
            inputSchema = objectSchema(mapOf("sessionId" to buildJsonObject { put("type", "string") }, "structures" to documentSchema("Optional complete StructureLibrary; omitted means this session's drafts.")), listOf("sessionId")),
            readOnly = true, handler = ::validateArchitecture,
        ),
        McpTool(
            name = "worldsmith_validate_structure", title = "Validate a structure blueprint",
            description = "Check and expand one bounded construction blueprint in Core, without Minecraft. Returns geometry counts and exact diagnostics.",
            inputSchema = structureInspectionSchema(false),
            readOnly = true, handler = { inspectStructure(it, false) },
        ),
        McpTool(
            name = "worldsmith_preview_structure", title = "Preview a structure blueprint",
            description = "Return actual multi-view PNG images plus an SVG schematic. Choose material/clay, fixed frame, cropped region or cutaway above normalized sliceY. Inspect silhouette, facade depth and occupied interiors; validation is not an aesthetic verdict. No Minecraft compiler is run.",
            inputSchema = structureInspectionSchema(true),
            readOnly = false, handler = structureService::preview,
        ),
        McpTool(
            name = "worldsmith_preview_assembly", title = "Preview connected structure pieces",
            description = "Compile a bounded layout, return its graph and actual multi-view PNG images plus an SVG schematic. Use top and opposite isometrics or clay to review hierarchy, spacing and approach. A failed assembly shows labelled members instead, never a claimed complete layout.",
            inputSchema = objectSchema(mapOf("sessionId" to stringSchema(), "structure" to documentSchema("WorldStructureDefinition"), "variant" to buildJsonObject {put("type","integer");put("minimum",0);put("maximum",7)})+StructurePreviewService.optionSchema(false),listOf("structure")),
            readOnly = false, handler = structureService::previewAssembly,
        ),
        McpTool(
            name = "worldsmith_get_structure_example", title = "Get an executable structure example",
            description = "Return forest_shrine, wayfarer_lodge (stairs, variants, loot/sign), arcane_observatory (curves/dome/banner), connected_courtyard (rigid assembly), or hillside_settlement (regions, terrain-following roads, instance patches). Copy grammar, not mandatory style. Replace example biome ids.",
            inputSchema = objectSchema(mapOf("id" to buildJsonObject {put("type","string");put("enum",JsonArray(listOf("forest_shrine","wayfarer_lodge","arcane_observatory","connected_courtyard","hillside_settlement").map(::JsonPrimitive)))}),emptyList()),
            readOnly = true, handler = { structureExample(it) },
        ),
        McpTool(
            name = "worldsmith_status",
            title = "Worldsmith status",
            description = "Read the local Worldsmith bridge, schema and managed pack-directory status.",
            inputSchema = emptySchema(),
            readOnly = true,
            handler = { status() },
        ),
        McpTool(
            name = "worldsmith_get_draw_sdk",
            title = "Read the Java drawing SDK",
            description = "Read the pure-Java Core drawing API: canvas, brushes, masks, curves, implicit fields, transforms and native structure NBT export. Documentation only; does not compile or execute Java source or place blocks in a world.",
            inputSchema = emptySchema(),
            readOnly = true,
            handler = {
                val reference = templates.load(PromptTemplateRef("contract/draw")).systemPrompt
                McpToolResult.success(buildJsonObject { put("language", "java"); put("reference", reference); put("executesCode", false) }, reference)
            },
        ),
        McpTool(
            name = "worldsmith_get_pack_template",
            title = "Get Worldsmith pack template",
            description =
                "Return the validated built-in pack as a complete example of terrain, biome and feature documents. " +
                    "Call this before writing a new pack.",
            inputSchema = emptySchema(),
            readOnly = true,
            handler = { getPackTemplate() },
        ),
        McpTool(
            name = WorldsmithWorkflow.STYLE_LIST_TOOL,
            title = "List Worldsmith world styles",
            description =
                "List the world styles available, one sentence each. A style says which values make a world " +
                    "read as a particular kind of place. Pick the one matching the player's prompt and read it " +
                    "with " + WorldsmithWorkflow.STYLE_GET_TOOL + "; when none matches, read `general` instead.",
            inputSchema = emptySchema(),
            readOnly = true,
            handler = { listStyles() },
        ),
        McpTool(
            name = WorldsmithWorkflow.STYLE_GET_TOOL,
            title = "Get a Worldsmith world style",
            description =
                "Return one style in full, by the id " + WorldsmithWorkflow.STYLE_LIST_TOOL + " reported. Ask " +
                    "for `general` when no style matched the prompt: it is the method for deriving a world from " +
                    "an arbitrary description.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "id" to buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "A style id from " + WorldsmithWorkflow.STYLE_LIST_TOOL + ", or '" +
                                StyleCatalog.FALLBACK_ID + "' when none matched.",
                        )
                    },
                ),
                required = listOf("id"),
            ),
            readOnly = true,
            handler = ::getStyle,
        ),
        McpTool(
            name = WorldsmithWorkflow.CONTRACT_TOOL,
            title = "Get a Worldsmith document contract",
            description =
                "Read grand_world planning, terrain, biome, feature, structure, architecture or draw. Omit detail for full text, " +
                    "use detail=index for section ids, or section=<id> for one section. Summary begin returns indexes, " +
                    "not full contracts. For content quality read architecture sections creative-brief and visual-quality-loop.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "detail" to stringSchema(),"section" to stringSchema(),
                    "id" to buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(PromptSet.DEFAULT.contracts.keys.map(::JsonPrimitive)))
                        put("description", "Which document's field vocabulary to return.")
                    },
                ),
                required = listOf("id"),
            ),
            readOnly = true,
            handler = ::getContract,
        ),
        McpTool(
            name = WorldsmithWorkflow.ANALYZE_TOOL,
            title = "Analyze Worldsmith biome distribution",
            description =
                "Predict how much of the world each biome will actually cover, before writing the pack. A climate " +
                    "box says where a biome may be, never how much that is, and the axes are bell-shaped noise: " +
                    "the HOT band looks like COLD's mirror and is a quarter its size. Reports per-biome share, " +
                    "land/water split, biomes that are never chosen, and which pairs share a border. " +
                    "Send the same terrain and biomes you intend to write, or the id of a saved pack.",
            inputSchema = analyzeSchema(),
            readOnly = true,
            handler = ::analyzeDistribution,
        ),
        McpTool(
            name = "worldsmith_list_packs",
            title = "List Worldsmith packs",
            description = "List portable packs already stored in Worldsmith's managed local pack directory.",
            inputSchema = emptySchema(),
            readOnly = true,
            handler = { listPacks() },
        ),
        McpTool(
            name = "worldsmith_validate_pack",
            title = "Validate Worldsmith pack",
            description = "Validate one managed pack by its 64-character generation id and return diagnostics.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "id" to buildJsonObject {
                        put("type", "string")
                        put("pattern", "^[0-9a-f]{64}$")
                        put("description", "The generation-content SHA-256 returned by worldsmith_write_pack.")
                    },
                ),
                required = listOf("id"),
            ),
            readOnly = true,
            handler = ::validatePack,
        ),
        McpTool(
            name = "worldsmith_write_pack",
            title = "Write Worldsmith pack",
            description =
                "Validate all nine modules, quest/reward references and attached PNG assets, freeze a format-6 bundle, atomically save it and return a ready single-file .wspack resourcePack receipt. resourcePackFilename optionally names the export; default is <id>.wspack. Inline modules override drafts for publication; guided writes require expectedRevision. Core/package readiness is separate from native activation. Export failure preserves the frozen pack and names an export-only retry.",
            inputSchema = writePackSchema(),
            readOnly = false,
            idempotent = true,
            handler = ::writePack,
        ),
        McpTool(
            name = WorldsmithWorkflow.FINISH_TOOL,
            title = "Finish a Worldsmith world",
            description =
                "End the run started by ${WorldsmithWorkflow.BEGIN_TOOL}. Re-reads the session's pack from disk " +
                    "and re-validates it, ensures the single-file .wspack export is ready before native publication, then answers complete=true when there is nothing left to do, or " +
                    "complete=false and the tool to call next. Stop only on true.",
            inputSchema = objectSchema(mapOf("sessionId" to stringSchema(),"resourcePackFilename" to resourcePackFilenameSchema()),listOf("sessionId")),
            readOnly = false,
            idempotent = true,
            handler = ::finishWorld,
        ),
    )

    /**
     * The entry point: everything an agent needs before it designs anything.
     *
     * The design rules handed out here are the same prompt the in-game
     * generator uses, so an outside agent and the built-in one cannot drift
     * into being asked for different documents.
     */
    private fun listSavedSessions(arguments:JsonObject):McpToolResult = McpToolResult.success(buildJsonObject {
        put("recoveryDiagnostics",encode(drawings.recoveryDiagnostics));put("sessionRecoveryDiagnostics",encode(sessions.recoveryDiagnostics))
        putJsonArray("sessions") {
            (sessions.all()+if(arguments["includeArchived"]?.jsonPrimitive?.content=="true")sessions.archivedSessions()else emptyList()).forEach { session ->
                add(buildJsonObject {
                    put("sessionId",session.id);put("prompt",session.prompt);put("revision",session.revision)
                    put("finished",session.finished);put("archived",session.archived);put("draftCount",session.structures.size)
                    put("mode",session.mode.name);put("designPlanPresent",session.designPlan!=null)
                })
            }
        }
    })
    private fun resumeSession(arguments:JsonObject):McpToolResult {
        val session=sessions.resume(requiredString(arguments,"sessionId")) ?: return McpToolResult.error("Unknown session")
        val detail=optionalString(arguments,"detail").ifBlank {"full"};require(detail in setOf("summary","full"))
        val progress=contentService.progress(session)
        return McpToolResult.success(buildJsonObject {
            put("sessionId",session.id);put("prompt",session.prompt);put("revision",session.revision)
            put("mode",session.mode.name);put("designPlanPresent",session.designPlan!=null)
            session.architecture?.let { put("architecture",encode(it)) }
            if(detail=="full") {
                put("structures",encode(session.structureLibrary()))
                put("designPlan",session.designPlan?.let {encode(it)} ?: JsonNull)
                putJsonArray("jobs") { drawings.list(session.id).forEach { add(drawingService.job(it)) } }
            } else {
                put("structureIds",encode(session.structures.keys.sorted()));put("moduleIds",encode(session.contentModules.keys.sorted()))
                put("drawingJobCount",drawings.list(session.id).size)
            }
            put("recoveryDiagnostics",encode(drawings.recoveryDiagnostics));put("sessionRecoveryDiagnostics",encode(sessions.recoveryDiagnostics))
            put("progress",encode(progress));put("contractPointers",generationContractPointers())
            put("nextTool",progress.nextTool);put("nextArguments",progress.nextArguments);put("nextInstruction",progress.nextInstruction)
        })
    }
    private fun stringSchema() = buildJsonObject { put("type","string") }
    private fun sessionSchema(extra:String?=null):JsonObject = objectSchema(
        linkedMapOf("sessionId" to stringSchema()).apply { if(extra!=null)put(extra,stringSchema()) },listOfNotNull("sessionId",extra))
    private fun drawingBuildSchema():JsonObject = objectSchema(linkedMapOf(
        "sessionId" to stringSchema(),"name" to stringSchema(),"requestId" to stringSchema(),"entryClass" to stringSchema(),
        "sources" to documentSchema("Map of relative .java file names to UTF-8 source; 1..16 files, total <=1 MiB"),
        "sourceRef" to documentSchema("projectId, revision, target; mutually exclusive with inline entryClass/sources"),
        "seeds" to buildJsonObject { put("type","array");put("items",buildJsonObject { put("type","integer") });put("minItems",1);put("maxItems",8) },
        "parameters" to documentSchema("String parameters passed to DrawContext")),listOf("sessionId","name","requestId"))
    private fun attachDrawings(library:StructureLibrary,sessionId:String):StructureLibrary=structureService.attach(sessionId,library)

    private fun beginWorld(arguments: JsonObject): McpToolResult {
        val prompt = requiredString(arguments, "prompt").trim()
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(prompt.length <= MAX_PROMPT_LENGTH) { "prompt must be at most $MAX_PROMPT_LENGTH characters" }

        val mode=optionalString(arguments,"mode").ifBlank {WorkflowMode.WORLDGEN_ONLY.name}.let {WorkflowMode.valueOf(it)}
        val detail=optionalString(arguments,"detail").ifBlank {if(mode==WorkflowMode.WORLDGEN_ONLY)"full" else "summary"};require(detail in setOf("summary","full"))
        val session = sessions.begin(prompt,mode)
        val progress=contentService.progress(session)
        val overview=WorldsmithWorkflow.overview(mode,detail=="summary")
        val planningText=if(mode!=WorkflowMode.STANDALONE)templates.load(PromptSet.DEFAULT.contracts.getValue(PromptSet.CONTRACT_GRAND_WORLD)).systemPrompt else null
        val planningOverview=planningText?.let {ContractSections.split(it).getValue("overview")}
        val nextTool=if(planningText!=null)WorldsmithWorkflow.CONTRACT_TOOL else progress.nextTool
        val nextArguments=if(planningText!=null)buildJsonObject {put("id",PromptSet.CONTRACT_GRAND_WORLD);put("section","world-atlas")} else progress.nextArguments
        val nextInstruction=if(planningText!=null)"Read grand_world/world-atlas, plan broad geography, places and production batches within authoringBudgets, then commit intent through the existing world-design/theme/content fields. The progress object retains the actual missing draft action." else progress.nextInstruction
        val structured = buildJsonObject {
            put("sessionId", session.id)
            put("prompt", prompt)
            put("mode",mode.name);put("complete", false);put("stage",progress.stage)
            put("overview", overview)
            put("authoringBudgets",WorldsmithAuthoringBudgets.snapshot())
            planningOverview?.let {
                put("worldPlanningGuide",it.take(MAX_WORLD_PLANNING_GUIDE_CHARS))
                put("worldPlanningGuideTruncated",it.length>MAX_WORLD_PLANNING_GUIDE_CHARS)
                putJsonObject("worldPlanningReference") {
                    put("tool",WorldsmithWorkflow.CONTRACT_TOOL);put("id",PromptSet.CONTRACT_GRAND_WORLD);put("section","world-atlas")
                }
            }
            put("procedure", procedureJson(mode,detail=="summary"))
            putJsonObject("capabilities") { put("javaDrawingWorker",drawings.available);put("structureProgram",true);put("sourceProjects",true);put("spatialPreflight",nativeChecks!=null);put("autoApproveSourceExecution",drawings.automaticSourceExecution);put("drawingSnapshotVersion",DrawSnapshotCodec.VERSION);put("persistentSessions",true);put("nativePublicationRequired",true) }
            putJsonObject("architecturePolicy") {
                put("requiredForThisScope",mode!=WorkflowMode.STANDALONE)
                put("version", 1); put("minimumGroups", StructureArchitectureValidator.MIN_GROUPS)
                put("minimumStandalone", StructureArchitectureValidator.MIN_STANDALONE); put("minimumLandmarkGroups", 1)
                put("minimumOccupiedBlockLight", 8); put("landmarkInstancesVerified", false)
                put("referenceTool", WorldsmithWorkflow.CONTRACT_TOOL); put("referenceId", "architecture")
            }
            // Summary mode must retain the creative objective, not only numeric publication gates.
            val architectureText=templates.load(PromptSet.DEFAULT.contracts.getValue("architecture")).systemPrompt
            ContractSections.split(architectureText)["creative-brief"]?.let {put("designGuide",it)}
            putJsonObject("designReference") {put("id","architecture");put("section","visual-quality-loop");put("tool",WorldsmithWorkflow.CONTRACT_TOOL)}
            if(detail=="full")put("howToDesign", templates.load(PromptSet.DEFAULT.worldEntry).systemPrompt)
            putJsonObject("contracts") {
                PromptSet.DEFAULT.contracts.forEach { (name, ref) -> val text=if(name==PromptSet.CONTRACT_GRAND_WORLD && planningText!=null)planningText else templates.load(ref).systemPrompt
                    put(name,if(detail=="full")JsonPrimitive(text)else ContractSections.index(text)) }
            }
            put("detail",detail)
            put("contentFramework",contentService.capabilities())
            put("contractPointers",generationContractPointers());put("progress",encode(progress))
            put("styleCount", styles.list().size)
            put("climatePlacement", climatePlacementJson())
            put("nextTool", nextTool);put("nextArguments",nextArguments);put("nextInstruction",nextInstruction)
        }
        val text = buildString {
            appendLine(overview)
            appendLine()
            appendLine("sessionId: ${session.id}")
            appendLine("mode: ${mode.name}")
            append("next: $nextTool — $nextInstruction")
        }
        return McpToolResult.success(structured, text)
    }

    private fun structureExample(arguments:JsonObject): McpToolResult {
        val id=arguments["id"]?.jsonPrimitive?.contentOrNull ?: "forest_shrine"
        if(id !in listOf("forest_shrine","wayfarer_lodge","arcane_observatory","connected_courtyard","hillside_settlement"))return McpToolResult.error("Unknown structure example")
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("worldsmith/structures/$id.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        if(id=="connected_courtyard" || id=="hillside_settlement") {
            val definition=WorldsmithJson.decode<WorldStructureDefinition>(text)
            return McpToolResult.success(buildJsonObject {put("structure",encode(definition));put("blueprint",encode(definition.blueprint));put("replaceExampleBiomeIds",true);put("contract","structure")})
        }
        val blueprint = WorldsmithJson.decode<StructureBlueprint>(text)
        return McpToolResult.success(buildJsonObject { put("blueprint", encode(blueprint)); put("contract", "structure") })
    }

    private fun putStructure(arguments: JsonObject): McpToolResult {
        val sessionId = requiredString(arguments, "sessionId")
        if (sessions.find(sessionId) == null) return McpToolResult.error("Unknown sessionId; begin a world first")
        val structure = structureService.resolve(sessionId,requiredObject(arguments,"structure"))
        if (!structure.id.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}"))) return McpToolResult.error("Invalid structure id")
        require(sessions.find(sessionId)?.archived==false) {"Resume the archived session before editing"}
        val inspection=structureService.inspect(sessionId,structure)
        val diagnostics=inspection.report.stages.values.flatMap {it.diagnostics}
        if (inspection.report.stages["geometry"]?.state=="FAILED") return McpToolResult.error("Structure geometry needs repair", buildJsonObject { put("diagnostics", diagnosticsJson(diagnostics)) })
        val updated = requireNotNull(sessions.putStructure(sessionId, structure))
        return McpToolResult.success(buildJsonObject {
            put("sessionId", sessionId); put("id", structure.id); put("draftCount", updated.structures.size)
            put("geometryValid", true); put("placementValidated", false);put("checks",encode(inspection.report))
            put("diagnostics", diagnosticsJson(diagnostics))
            val planned = updated.architecture?.let { it.groups.map { g -> g.structure } + it.standalone.map { s -> s.structure } }.orEmpty()
            val missing = planned.filter { it !in updated.structures }
            put("remainingPlannedStructures", JsonArray(missing.map(::JsonPrimitive)))
            put("nextTool", if (updated.architecture == null) WorldsmithWorkflow.ARCHITECTURE_TOOL else if (missing.isEmpty()) WorldsmithWorkflow.ARCHITECTURE_VALIDATE_TOOL else WorldsmithWorkflow.STRUCTURE_TOOL)
        }, "Structure draft saved; biome references and placement are checked when the whole pack is written.")
    }

    private fun planArchitecture(arguments: JsonObject): McpToolResult {
        val sessionId = requiredString(arguments, "sessionId").trim()
        if (sessions.find(sessionId) == null) return incomplete(sessionId, WorldsmithWorkflow.BEGIN_TOOL, "Unknown sessionId; begin a world first")
        val plan = decode<StructureArchitecture>(requiredObject(arguments, "architecture"))
        val diagnostics = StructureArchitectureValidator.validatePlan(plan)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) return McpToolResult.error("Architecture plan needs repair", buildJsonObject {
            put("valid", false); put("diagnostics", diagnosticsJson(diagnostics)); put("nextTool", WorldsmithWorkflow.ARCHITECTURE_TOOL)
        })
        val session = requireNotNull(sessions.planArchitecture(sessionId, plan))
        return McpToolResult.success(buildJsonObject {
            put("sessionId", sessionId); put("planValid", true); put("architecture", encode(plan))
            put("groupCount", plan.groups.size); put("landmarkGroupCount", plan.groups.count { it.role == StructureGroupRole.LANDMARK })
            put("standaloneCount", plan.standalone.size); put("existingDrafts", session.structures.size)
            put("geometryVerified", false); put("landmarkInstancesVerified", false)
            put("nextTool", WorldsmithWorkflow.STRUCTURE_TOOL)
        }, "Architecture intent saved. Next author executable groups and independent structures; this plan alone is not a generated world.")
    }

    private fun structureDrafts(arguments: JsonObject, session: WorkflowSession?): StructureLibrary {
        val supplied = arguments["structures"]?.let { decode<StructureLibrary>(it) }
            ?: session?.structureLibrary() ?: StructureLibrary()
        require(supplied.architecture == null || session?.architecture == null || supplied.architecture == session.architecture) {
            "Inline architecture differs from the session plan; update it with worldsmith_plan_architecture first"
        }
        val result=supplied.copy(architecture = supplied.architecture ?: session?.architecture)
        return if(session!=null)attachDrawings(result,session.id) else result
    }

    private fun validateArchitecture(arguments: JsonObject): McpToolResult {
        val sessionId = requiredString(arguments, "sessionId").trim()
        val session = sessions.find(sessionId) ?: return incomplete(sessionId, WorldsmithWorkflow.BEGIN_TOOL, "Unknown sessionId; begin a world first")
        val library = structureDrafts(arguments, session)
        val inspections=library.structures.map {structureService.inspect(sessionId,it)}
        val diagnostics=inspections.flatMap {it.report.stages.values.flatMap {stage->stage.diagnostics}}.toMutableList()
        if(library.architecture==null)diagnostics+=StructureArchitectureValidator.missingPlan()
        else if(diagnostics.none {it.severity==DiagnosticSeverity.ERROR})try {
            diagnostics+=StructureArchitectureValidator.validate(library,StructureCatalogCompiler.compile(library))
        }catch(f:StructureBuildException){diagnostics+=f.diagnostic}
        val valid = diagnostics.none { it.severity == DiagnosticSeverity.ERROR }
        val result = buildJsonObject {
            put("sessionId", sessionId); put("valid", valid); put("diagnostics", diagnosticsJson(diagnostics))
            put("placementValidated", false); put("minecraftCompiled", false); put("landmarkInstancesVerified", false)
            put("lightingAssessment", "declarations_checked; voxel_estimate_opt_in_non_blocking; native_emission_checks_separate")
            put("nextTool", if (valid) WorldsmithWorkflow.WRITE_TOOL else if (library.architecture == null) WorldsmithWorkflow.ARCHITECTURE_TOOL else WorldsmithWorkflow.STRUCTURE_TOOL)
        }
        return if (valid) McpToolResult.success(result, "Architecture drafts satisfy Core policy; worldgen placement and native light-source checks remain separate.")
            else McpToolResult.error("Architecture drafts need repair", result)
    }

    private fun inspectStructure(arguments: JsonObject, preview: Boolean): McpToolResult {
        val blueprint = decode<StructureBlueprint>(requiredObject(arguments, "blueprint"))
        val variant=arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if(variant !in 0 until (blueprint.drawing?.variants?.size ?: blueprint.variation.count) || arguments["variant"]!=null && arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()==null)return McpToolResult.error("variant must be an integer inside variation.count")
        val drawingAssets=if(blueprint.drawing==null)emptyMap() else attachDrawings(StructureLibrary(structures=listOf(WorldStructureDefinition("inspection",blueprint,StructurePlacement(emptyList())))),requiredString(arguments,"sessionId")).drawingAssets
        val variants = try { StructureGeometryCompiler.compileVariants(blueprint,drawingAssets) } catch (failure: StructureBuildException) {
            return McpToolResult.error("Structure geometry needs repair", buildJsonObject { put("valid", false); put("diagnostics", diagnosticsJson(listOf(failure.diagnostic))) })
        }
        val geometry=variants[variant]
        val metadata=if(blueprint.drawing==null)blueprint else StructureDrawCompiler.metadata(blueprint,geometry)
        val requestedSlice = arguments["sliceY"]?.jsonPrimitive?.contentOrNull
        val slice = requestedSlice?.toIntOrNull() ?: minOf(2, geometry.size.y - 1)
        if (requestedSlice != null && requestedSlice.toIntOrNull() == null || slice !in 0 until geometry.size.y) {
            return McpToolResult.error("sliceY must be an integer inside 0..${blueprint.size.y-1}")
        }
        val cutawayArgument = arguments["cutaway"]?.jsonPrimitive?.contentOrNull
        if (cutawayArgument != null && cutawayArgument.toBooleanStrictOrNull() == null) return McpToolResult.error("cutaway must be true or false")
        val cutaway = cutawayArgument?.toBooleanStrict() ?: false
        val result = buildJsonObject {
            put("valid", true); put("id", blueprint.id); put("cells", geometry.voxels.size)
            put("solidCells", geometry.voxels.count { !it.material.isAir() })
            put("explicitAirCells", geometry.voxels.count { it.material.isAir() })
            put("expandedWork", geometry.expandedWork); put("minecraftCompiled", false)
            put("variant",variant);put("variantCount",variants.size)
            put("reachableFeet",StructureNavigation.inspect(metadata,geometry.voxels).reachableFeet.size)
            metadata.lighting?.let { policy ->
                val report = StructureLightingChecker.inspect(metadata, geometry.voxels)
                putJsonObject("lighting") {
                    put("mode", policy.mode.name); put("sampledFeet", report.sampledFeet)
                    put("minimumEstimatedLevel", report.minimumEstimatedLevel?.let(::JsonPrimitive) ?: JsonNull)
                    put("estimated", false); put("brightnessGate", false)
                    put("skylightIncluded", false); put("nativeSourcesVerified", false)
                }
            }
            put("diagnostics", diagnosticsJson(geometry.diagnostics))
            put("sliceY", slice)
            put("floorPlan", StructurePreview.floorPlan(geometry, slice))
            if (preview) {
                val directory = packDirectory.resolveSibling("structure-previews")
                Files.createDirectories(directory)
                val path = directory.resolve(blueprint.id + (if(variant==0)"" else "-variant-$variant") + if (cutaway) "-cutaway-$slice.svg" else ".svg")
                Files.writeString(path, StructurePreview.svg(geometry, slice, cutaway), StandardCharsets.UTF_8)
                put("previewPath", path.toString()); put("previewType", "orthographic-isometric-schematic")
                put("cutaway", cutaway)
            }
        }
        return McpToolResult.success(result,images=if(preview)listOf(modelImage(geometry,if(cutaway)"slice" else "isometric",if(cutaway)slice else null))else emptyList())
    }

    private fun modelImage(g:CompiledStructure,view:String,slice:Int?):McpImage {
        val box=com.wjz.worldsmith.core.draw.Box.sized(g.size.x,g.size.y,g.size.z)
        val voxels=g.voxels.map { v->com.wjz.worldsmith.core.draw.DrawVoxel(
            com.wjz.worldsmith.core.draw.Vec3i(v.position.x,v.position.y,v.position.z),
            com.wjz.worldsmith.core.draw.DrawBlock(com.wjz.worldsmith.core.draw.BlockStateRef(v.material.block,v.material.properties),
                com.wjz.worldsmith.core.draw.GridTransform(v.quarterTurns,v.mirrorX,com.wjz.worldsmith.core.draw.Vec3i.ZERO))) }
        val drawing=com.wjz.worldsmith.core.draw.DrawStructure(box,voxels,emptyMap())
        return McpImage(Base64.getEncoder().encodeToString(DrawPreview.png(drawing,view,slice)))
    }

    private fun procedureJson(mode:WorkflowMode=WorkflowMode.WORLDGEN_ONLY,summary:Boolean=false): JsonArray = buildJsonArray {
        WorldsmithWorkflow.procedure(mode,summary).forEach { step ->
            add(
                buildJsonObject {
                    put("order", step.order)
                    put("tool", step.tool)
                    put("instruction", step.instruction)
                },
            )
        }
    }

    /** Small, executable contract lookups retained even when the full cross-domain prompts are omitted. */
    private fun generationContractPointers():JsonObject=buildJsonObject {
        putJsonObject(PromptSet.CONTRACT_GRAND_WORLD) {
            put("tool",WorldsmithWorkflow.CONTRACT_TOOL)
            putJsonObject("arguments") {put("id",PromptSet.CONTRACT_GRAND_WORLD);put("detail","index")}
        }
        listOf("world_design","theme","blocks","creatures","items","quests").forEach { module ->
            putJsonObject(module) { put("tool","worldsmith_get_content_contract");putJsonObject("arguments") {put("module",module)} }
        }
        mapOf("terrain" to "terrain","biomes" to "biome","features" to "feature","structures" to "structure","architecture" to "architecture","drawing" to "draw").forEach { (name,id) ->
            putJsonObject(name) {put("tool",WorldsmithWorkflow.CONTRACT_TOOL);putJsonObject("arguments") {put("id",id)}}
        }
        putJsonObject("textures") {put("tool","worldsmith_get_texture_workflow");putJsonObject("arguments") {}}
        putJsonObject("creatureAuthoring") {put("tool","worldsmith_get_creature_authoring_contract");putJsonObject("arguments") {}}
    }

    private fun structureInspectionSchema(preview: Boolean): JsonObject = objectSchema(
        buildMap {
            put("sessionId", stringSchema())
            put("blueprint", documentSchema("StructureBlueprint or authored reference"))
            if(preview)putAll(StructurePreviewService.optionSchema())
            put("variant",buildJsonObject {put("type","integer");put("minimum",0);put("maximum",7);put("description","Preview this precompiled blueprint variant; default 0.")})
            put("sliceY", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 127); put("description", "Local Y floor-plan layer; default min(2, size.y-1).") })
        },
        listOf("blueprint"),
    )

    /** Placement vocabulary, explicitly presented as optional rather than a quota. */
    private fun climatePlacementJson(): JsonObject = buildJsonObject {
        put(
            "principle",
            "The player's prompt is the only distribution standard. There is no required biome count, " +
                "temperature quota, humidity quota or full-grid coverage rule.",
        )
        putJsonObject("semanticSlotPresets") {
            put("relief", bandNames(ReliefBand.entries))
            put("temperature", bandNames(TemperatureBand.entries))
            put("humidity", bandNames(HumidityBand.entries))
        }
        put(
            "rawClimateAxes",
            JsonArray(
                listOf("temperature", "humidity", "continentalness", "erosion", "depth", "weirdness", "offset")
                    .map(::JsonPrimitive),
            ),
        )
        put(
            "guidance",
            "Use a semantic slot for a simple familiar placement or a raw climate box for precise distribution. " +
                "Broad ranges make a theme dominant; narrow ranges make it rare. Gaps are valid and Minecraft " +
                "resolves them to the nearest declared biome.",
        )
    }

    private fun bandNames(values: List<Enum<*>>): JsonArray = JsonArray(values.map { JsonPrimitive(it.name) })

    /**
     * Answers the one question the document cannot: how much of it is what.
     *
     * <p>Accepts the documents directly rather than only a saved pack, because
     * the useful moment is before the write, while the design is still cheap to
     * change. A pack id is accepted too, for looking at what was already built.
     */
    private fun analyzeDistribution(arguments: JsonObject): McpToolResult {
        val id = arguments["id"]?.jsonPrimitive?.contentOrNull
        val terrain: TerrainPlan
        val biomes: BiomePlan
        if (id != null) {
            val pack = runCatching { WorldsmithPackLoader.loadDirectory(packDirectory.resolve(id)) }
                .getOrElse { return McpToolResult.error("No managed pack with id " + id) }
            terrain = pack.terrain
            biomes = pack.biomes
        } else {
            terrain = decode(requiredObject(arguments, "terrain"))
            biomes = decode(requiredObject(arguments, "biomes"))
        }

        val report = BiomeDistributionAnalyzer.analyze(biomes, terrain)
        val structured = buildJsonObject {
            put("samples", report.samples)
            put("landShare", report.landShare)
            put("waterShare", report.waterShare)
            putJsonArray("biomes") {
                report.biomes.forEach { share ->
                    add(
                        buildJsonObject {
                            put("id", share.id)
                            put("archetype", share.archetype.name)
                            put("share", share.share)
                        },
                    )
                }
            }
            putJsonObject("archetypes") {
                report.archetypes.forEach { (role, share) -> put(role.name, share) }
            }
            put("neverChosen", JsonArray(report.absent.map(::JsonPrimitive)))
            put("rare", JsonArray(report.rare.map(::JsonPrimitive)))
            put("dominant", JsonArray(report.dominant.map(::JsonPrimitive)))
            putJsonArray("borders") {
                report.borders.forEach { border ->
                    add(
                        buildJsonObject {
                            put("first", border.first)
                            put("second", border.second)
                            put("share", border.share)
                        },
                    )
                }
            }
            put("notes", JsonArray(report.notes.map(::JsonPrimitive)))
        }

        val text = buildString {
            appendLine("land " + percent(report.landShare) + " / water " + percent(report.waterShare))
            report.biomes.forEach { appendLine(percent(it.share).padStart(6) + "  " + it.id) }
            if (report.absent.isNotEmpty()) {
                appendLine("never chosen: " + report.absent.joinToString(", "))
            }
            if (report.rare.isNotEmpty()) {
                appendLine("under " + percent(BiomeDistributionAnalyzer.RARE_SHARE) + ": " + report.rare.joinToString(", "))
            }
            report.notes.forEach { appendLine(it) }
        }
        return McpToolResult.success(structured, text.trim())
    }

    private fun percent(share: Double): String = ((share * 1000).toInt() / 10.0).toString() + "%"


    private fun analyzeSchema(): JsonObject = objectSchema(
        properties = mapOf(
            "id" to buildJsonObject {
                put("type", "string")
                put("pattern", "^[0-9a-f]{64}$")
                put("description", "A saved pack to analyze instead of sending documents.")
            },
            "terrain" to buildJsonObject {
                put("type", "object")
                put("description", "The terrain document; its land ratio decides where the coastline falls.")
            },
            "biomes" to buildJsonObject {
                put("type", "object")
                put("description", "The biome document to measure.")
            },
        ),
        required = emptyList(),
    )

    /**
     * The cheap half of the style lookup: an id and a sentence each.
     *
     * Kept apart from the bodies so that adding a style costs the agent one line
     * rather than a page. That is the only reason a catalog of styles can grow
     * at all: a run consults one of them, so paying for all of them up front
     * would crowd out the design the styles exist to inform.
     */
    private fun listStyles(): McpToolResult {
        val summaries = styles.list()
        val fallback = styles.fallback().summary
        val structured = buildJsonObject {
            putJsonArray("styles") {
                summaries.forEach { style ->
                    add(
                        buildJsonObject {
                            put("id", style.id)
                            put("name", style.name)
                            put("description", style.description)
                        },
                    )
                }
            }
            putJsonObject("fallback") {
                put("id", fallback.id)
                put("name", fallback.name)
                put("description", fallback.description)
            }
            put("nextTool", WorldsmithWorkflow.STYLE_GET_TOOL)
        }
        val text = buildString {
            if (summaries.isEmpty()) {
                append("No styles are installed, which is not a failure. Read '")
                append(fallback.id)
                append("' with ")
                append(WorldsmithWorkflow.STYLE_GET_TOOL)
                append(" and derive the world from the prompt itself.")
            } else {
                appendLine("Pick the style matching the player's prompt, or '" + fallback.id + "' when none does.")
                summaries.forEach { appendLine(it.id + " - " + it.description) }
                append(fallback.id + " - " + fallback.description)
            }
        }
        return McpToolResult.success(structured, text)
    }

    private fun getStyle(arguments: JsonObject): McpToolResult {
        val id = requiredString(arguments, "id").trim()
        // An unknown id is answered rather than resolved: silently falling back
        // would turn a typo into a world built from the wrong method, and the
        // agent would have no way to notice.
        val guide = styles.load(id)
            ?: return McpToolResult.error(
                "Unknown style '" + id + "'. Call " + WorldsmithWorkflow.STYLE_LIST_TOOL +
                    " for the ids, or ask for '" + StyleCatalog.FALLBACK_ID +
                    "' to derive the world from the prompt.",
            )
        val structured = buildJsonObject {
            put("id", guide.summary.id)
            put("name", guide.summary.name)
            put("description", guide.summary.description)
            put("guide", guide.body)
            put("nextTool", WorldsmithWorkflow.ANALYZE_TOOL)
        }
        return McpToolResult.success(structured, guide.body)
    }

    private fun getContract(arguments: JsonObject): McpToolResult {
        val id = requiredString(arguments, "id").trim()
        val ref = PromptSet.DEFAULT.contracts[id]
            ?: return McpToolResult.error(
                "Unknown contract '" + id + "'. It is one of " +
                    PromptSet.DEFAULT.contracts.keys.joinToString(", ") + ".",
            )
        val full=templates.load(ref).systemPrompt
        if(optionalString(arguments,"detail")=="index")return McpToolResult.success(ContractSections.index(full))
        val section=optionalString(arguments,"section")
        val contract=if(section.isEmpty())full else ContractSections.split(full)[section] ?: return McpToolResult.error("Unknown contract section; request detail=index")
        return McpToolResult.success(
            buildJsonObject {
                put("id", id)
                put("contract", contract);put("revision",DrawSnapshotCodec.hash(full.toByteArray(Charsets.UTF_8)));put("section",section)
            },
            contract,
        )
    }

    private fun status(): McpToolResult {
        val structured = buildJsonObject {
            put("service", "Worldsmith MCP Bridge")
            put("mcpProtocolVersion", McpHttpServer.PROTOCOL_VERSION)
            put("blueprintSchemaVersion", WorldsmithCore.BLUEPRINT_SCHEMA_VERSION)
            put("packFormatVersion", PACK_FORMAT_VERSION);put("supportedPackFormats",JsonArray(listOf(JsonPrimitive(3),JsonPrimitive(4),JsonPrimitive(5),JsonPrimitive(6))));put("readOnlyPackFormats",JsonArray(listOf(JsonPrimitive(3),JsonPrimitive(4),JsonPrimitive(5))));put("sourceProjects",true);put("structureProgram",true);put("autoApproveSourceExecution",drawings.automaticSourceExecution)
            put("packDirectory", packDirectory.toString())
            putJsonObject("runtime") {
                runtimeInfo.get().toSortedMap().forEach { (key, value) -> put(key, value) }
            }
        }
        return McpToolResult.success(structured)
    }

    private fun getPackTemplate(): McpToolResult {
        val pack = WorldsmithPackLoader.loadClasspath(BUILTIN_PACK)
        val structured = packPayload(pack)
        return McpToolResult.success(structured)
    }

    private fun listPacks(): McpToolResult {
        if (!Files.isDirectory(packDirectory)) {
            return McpToolResult.success(buildJsonObject { put("packs", JsonArray(emptyList())) })
        }
        val entries = Files.list(packDirectory).use { paths ->
            paths.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }
                .sorted()
                .map(::packSummary)
                .toList()
        }
        return McpToolResult.success(buildJsonObject { put("packs", JsonArray(entries)) })
    }

    private fun packSummary(directory: Path): JsonObject = runCatching {
        val pack = WorldsmithPackLoader.loadDirectory(directory)
        val diagnostics = WorldsmithPackValidator.validate(pack)
        buildJsonObject {
            put("directory", directory.fileName.toString())
            put("id", pack.manifest.id)
            put("displayName", pack.manifest.displayName)
            put("description", pack.manifest.description)
            put("valid", diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
            put("diagnostics", diagnosticsJson(diagnostics))
        }
    }.getOrElse { failure ->
        buildJsonObject {
            put("directory", directory.fileName.toString())
            put("valid", false)
            put("error", failure.message ?: "Pack could not be read")
        }
    }

    private fun validatePack(arguments: JsonObject): McpToolResult {
        val id = requiredString(arguments, "id")
        if (!PACK_ID.matches(id)) {
            return McpToolResult.error("Pack id must be a lowercase 64-character SHA-256")
        }
        val directory = managedPack(id) ?: return McpToolResult.error("Managed pack '$id' does not exist")
        val pack = WorldsmithPackLoader.loadDirectory(directory)
        val diagnostics = WorldsmithPackValidator.validate(pack)
        val valid = diagnostics.none { it.severity == DiagnosticSeverity.ERROR }
        val structured = buildJsonObject {
            put("id", id)
            put("valid", valid)
            put("coreValidated",valid);put("nativeValidated",false);put("activated",false);put("selectedForCreation",false)
            put("resourceReloadRequested",false)
            put("diagnostics", diagnosticsJson(diagnostics))
        }
        return if (valid) McpToolResult.success(structured) else McpToolResult.error("Pack '$id' is invalid", structured)
    }

    private fun writePack(arguments: JsonObject): McpToolResult {
        val result = try { writePackAttempt(arguments) } catch (failure: StructureBuildException) {
            McpToolResult.error("Structure compilation needs repair", buildJsonObject {
                put("valid", false); put("diagnostics", diagnosticsJson(listOf(failure.diagnostic)))
            })
        }
        if (!result.isError) return result
        val id = (arguments["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return result
        val revision = (arguments["expectedRevision"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return result
        val diagnostics = result.structuredContent["diagnostics"]?.let { decode<List<Diagnostic>>(it) }.orEmpty()
        if (diagnostics.none { it.severity == DiagnosticSeverity.ERROR }) return result
        val receipt = PackValidationReceipt.bounded(revision, diagnostics,
            (arguments["displayName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            (arguments["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            listOf("terrain", "biomes", "features", "theme", "blocks", "creatures", "items", "quests", "structures", "architecture").filter { it in arguments })
        val current = sessions.recordWriteFailureAtRevision(id, receipt)
        val progress = current?.let(contentService::progress)
        return result.copy(structuredContent = JsonObject(result.structuredContent + buildJsonObject {
            put("sessionId", id); put("revision", revision); put("writeFailureRecorded", current != null)
            progress?.let {
                put("progress", encode(it)); put("nextTool", it.nextTool); put("nextArguments", it.nextArguments); put("nextInstruction", it.nextInstruction)
            }
        }))
    }

    private fun writePackAttempt(arguments: JsonObject): McpToolResult {
        val displayName = requiredString(arguments, "displayName").trim()
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be at most $MAX_DISPLAY_NAME_LENGTH characters"
        }
        val description = optionalString(arguments, "description").trim()
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "description must be at most $MAX_DESCRIPTION_LENGTH characters"
        }

        val sessionId = optionalString(arguments, "sessionId").trim()
        val session = sessions.find(sessionId)
        if (sessionId.isNotEmpty() && session == null) return incomplete(sessionId, WorldsmithWorkflow.BEGIN_TOOL, "Unknown or expired sessionId; begin a new run before publishing")
        val guidedSession = session != null && session.mode != WorkflowMode.STANDALONE
        val completeWorld = session?.mode == WorkflowMode.COMPLETE_WORLD
        if(session!=null) {
            require(!session.archived) { "Resume the archived draft before publishing" }
            require(arguments["expectedRevision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()==session.revision) { "DRAFT_REVISION_CONFLICT: expectedRevision is required; current ${session.revision}" }
        }
        if(completeWorld) {
            val design=session?.designPlan
            val problems=if(design==null) listOf(Diagnostic("designPlan","DESIGN_PLAN_REQUIRED",DiagnosticSeverity.ERROR,"A COMPLETE_WORLD run must commit its named design plan before publication")) else WorldDesignPlans.validate(design)
            if(problems.isNotEmpty())return McpToolResult.error("Complete-world plan is missing or invalid",buildJsonObject {
                put("sessionId",sessionId);put("valid",false);put("diagnostics",diagnosticsJson(problems));put("nextTool","worldsmith_put_world_design_plan")
            })
        }
        fun document(key:String)=arguments[key]?.jsonObject ?: session?.contentModules?.get(key) ?: error("$key is required inline or in the content draft")
        val terrainDocument = document("terrain")
        val shapeDocument = terrainDocument["shape"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val shapeKind = shapeDocument?.get("kind")?.jsonPrimitive?.contentOrNull
        if (shapeKind == "procedural" && shapeDocument["hydrology"] == null) {
            val diagnostics = listOf(
                Diagnostic(
                    path = "terrain.shape.hydrology",
                    code = "MISSING_HYDROLOGY",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Every procedural terrain shape must explicitly define its complete hydrology block",
                ),
            )
            val structured = buildJsonObject {
                put("valid", false)
                put("diagnostics", diagnosticsJson(diagnostics))
            }
            return McpToolResult.error("Generated pack is missing required terrain fields", structured)
        }
        val terrain = decode<TerrainPlan>(terrainDocument)
        val biomes = decode<BiomePlan>(document("biomes"))
        val features = decode<FeatureLibrary>(document("features"))
        val theme = decode<WorldTheme>(document("theme"))
        val blocks = (arguments["blocks"] ?: session?.contentModules?.get("blocks"))?.let {decode<CustomBlockLibrary>(it)} ?: CustomBlockLibrary()
        val creatures = (arguments["creatures"] ?: session?.contentModules?.get("creatures"))?.let {decode<CreatureLibrary>(it)} ?: CreatureLibrary()
        val items = (arguments["items"] ?: session?.contentModules?.get("items"))?.let {decode<CustomItemLibrary>(it)} ?: CustomItemLibrary()
        val quests = (arguments["quests"] ?: session?.contentModules?.get("quests"))?.let {decode<QuestLibrary>(it)} ?: QuestLibrary()
        val structures = structureDrafts(arguments, session)
        if (guidedSession && structures.architecture == null) return McpToolResult.error("Architecture planning is required", buildJsonObject {
            put("valid", false); put("diagnostics", diagnosticsJson(listOf(StructureArchitectureValidator.missingPlan())))
            put("nextTool", WorldsmithWorkflow.ARCHITECTURE_TOOL)
        })
        val structureDiagnostics = StructureValidator.validate(structures, biomes)
        if (structureDiagnostics.any { it.severity == DiagnosticSeverity.ERROR }) return McpToolResult.error(
            "Structure documents need repair", buildJsonObject { put("valid", false); put("diagnostics", diagnosticsJson(structureDiagnostics)) },
        )
        val proseDiagnostics = PlayerTextPolicy.validate(displayName, description, theme, items, quests)
        if (proseDiagnostics.isNotEmpty()) return McpToolResult.error("Player-facing writing needs revision", buildJsonObject {
            put("valid", false); put("diagnostics", diagnosticsJson(proseDiagnostics)); put("nextTool", "worldsmith_put_content_modules")
        })
        val representativeContent = arguments["representativeContent"]?.takeUnless { it is JsonNull }?.let { decode<ContentKey>(it) }
        val pack = WorldContentBundleIO.create(displayName,description,terrain,biomes,features,structures,theme,blocks,creatures,contentService.assetBytes(session),items,quests,representativeContent)
        val bundle=WorldContentBundleIO.encode(pack)
        val manifest=bundle.manifest
        val diagnostics = WorldsmithPackValidator.validate(pack).toMutableList()
        if (guidedSession && terrain.shape !is TerrainShape.Procedural) {
            diagnostics += Diagnostic(
                path = "terrain.shape",
                code = "PROMPT_TERRAIN_REQUIRED",
                severity = DiagnosticSeverity.ERROR,
                message = "A guided prompt run must provide a procedural terrain shape derived from its terrain contract",
            )
        }
        if(completeWorld && diagnostics.none {it.severity==DiagnosticSeverity.ERROR}) {
            diagnostics += WorldDesignCoverage.validate(pack,requireNotNull(session?.designPlan))
        }
        val structured = buildJsonObject {
            put("id", manifest.id)
            put("valid", diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
            put("diagnostics", diagnosticsJson(diagnostics))
            put("mode",session?.mode?.name ?: WorkflowMode.STANDALONE.name)
            put("completeWorldCoverageVerified",completeWorld && diagnostics.none {it.severity==DiagnosticSeverity.ERROR})
            if(diagnostics.any {it.severity==DiagnosticSeverity.ERROR})put("nextTool","worldsmith_get_generation_progress")
        }
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return McpToolResult.error("Generated pack did not pass Worldsmith validation", structured)
        }

        val directory = persistPack(manifest, bundle.texts, bundle.binaries)
        // Display metadata is not part of the content hash. An existing identical directory retains its actual metadata.
        val savedManifest=WorldsmithJson.decode<WorldsmithPackManifest>(Files.readString(directory.resolve("worldsmith.json")))
        // Session validity and the complete architecture were checked before writing files.
        val savedSession = session?.let { sessions.recordPackSnapshotAtRevision(sessionId,pack.copy(manifest=savedManifest),it.revision) }
        val recorded = savedSession != null
        if(session!=null && !recorded)return incomplete(sessionId,WorldsmithWorkflow.WRITE_TOOL,"Draft revision changed during validation; the frozen pack was preserved but not bound to the newer draft")
        val result = buildJsonObject {
            put("id", manifest.id)
            put("displayName", savedManifest.displayName);put("description",savedManifest.description)
            put("requestedDisplayName",displayName);put("existingMetadataRetained",savedManifest.displayName!=displayName || savedManifest.description!=description || savedManifest.representativeContent!=representativeContent)
            savedManifest.representativeContent?.let { put("representativeContent",encode(it)) }
            put("path", directory.toString())
            put("valid", true);put("stage","CORE_CHECK");put("minecraftCompiled",false)
            put("diagnostics", diagnosticsJson(diagnostics))
            put("mode",session?.mode?.name ?: WorkflowMode.STANDALONE.name)
            put("completeWorldCoverageVerified",completeWorld)
            if (sessionId.isNotEmpty()) {
                put("sessionId", sessionId)
                put("sessionRecorded", recorded)
                savedSession?.let { put("revision",it.revision) }
                put(
                    "nextTool",
                    if (recorded) WorldsmithWorkflow.FINISH_TOOL else WorldsmithWorkflow.BEGIN_TOOL,
                )
            }
        }
        val coreResult=McpToolResult.success(result, "Saved Worldsmith pack '${savedManifest.displayName}' as ${manifest.id}")
        val resourcePack=try {resourcePackExchange.exportPack(manifest.id,arguments["resourcePackFilename"]?.jsonPrimitive?.content)}
            catch(failure:Exception) {return resourcePackFailure(coreResult,manifest.id,arguments,failure)}
        return withResourcePack(coreResult,resourcePack)
    }

    /**
     * The only tool that may end a run.
     *
     * It answers the one question the agent actually has, may I stop, and it
     * answers from disk rather than from memory. Re-reading and re-validating
     * is what lets complete=true promise that a later reader finds the same
     * valid pack, instead of only that a write call once returned.
     */
    private fun finishWorld(arguments:JsonObject):McpToolResult {
        val session=sessions.find(requiredString(arguments,"sessionId"))
        var completeWorldCoverageVerified=false
        var resourcePack:ResourcePackReceipt?=null
        fun receipt(result:McpToolResult)=result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {
            put("mode",session?.mode?.name ?: WorkflowMode.WORLDGEN_ONLY.name)
            put("completeWorldCoverageVerified",completeWorldCoverageVerified)
            put("resourcePackReady",resourcePack!=null)
            resourcePack?.let {put("resourcePack",encode(it))}
            if(resourcePack!=null && result.structuredContent["nextTool"]?.jsonPrimitive?.contentOrNull==WorldsmithWorkflow.FINISH_TOOL && session!=null)
                put("nextArguments",buildJsonObject {put("sessionId",session.id);put("resourcePackFilename",resourcePack!!.filename)})
        }))
        if(session?.mode==WorkflowMode.COMPLETE_WORLD && session.packId!=null) {
            val plan=session.designPlan
            val path=packStore.managed(session.packId)
            if(plan==null)return receipt(incomplete(session.id,"worldsmith_put_world_design_plan","Complete-world plan is missing; restore the declared scope before native publication"))
            if(path!=null) {
                val loaded=try {WorldsmithPackLoader.loadDirectory(path)} catch(failure:Exception) {
                    return receipt(McpToolResult.error("Saved complete-world pack could not be read",buildJsonObject {
                        put("sessionId",session.id);put("complete",false);put("id",session.packId)
                        put("diagnostics",diagnosticsJson(listOf(Diagnostic("pack","COMPLETE_WORLD_PACK_READ_FAILED",DiagnosticSeverity.ERROR,
                            failure.message?.take(2048) ?: failure.javaClass.simpleName))))
                        put("nextTool","worldsmith_inspect_world_content");put("nextArguments",buildJsonObject {put("id",session.packId)})
                    }))
                }
                val diagnostics=WorldDesignCoverage.validate(loaded,plan)
                if(diagnostics.any {it.severity==DiagnosticSeverity.ERROR})return receipt(McpToolResult.error("Frozen complete-world coverage no longer meets its plan",buildJsonObject {
                    put("sessionId",session.id);put("complete",false);put("diagnostics",diagnosticsJson(diagnostics));put("nextTool","worldsmith_get_generation_progress")
                }))
                completeWorldCoverageVerified=true
            }
        }
        if(session?.packId!=null && packStore.managed(session.packId)!=null) {
            resourcePack=try {resourcePackExchange.exportPack(session.packId,arguments["resourcePackFilename"]?.jsonPrimitive?.content)}
                catch(failure:Exception) {return resourcePackFailure(receipt(McpToolResult.success(buildJsonObject {
                    put("sessionId",session.id);put("packId",session.packId);put("revision",session.revision);put("complete",false)
                })),session.packId,arguments,failure)}
        }
        if(session?.mode==WorkflowMode.STANDALONE) {
            val progress=contentService.progress(session)
            return receipt(McpToolResult.success(buildJsonObject {
                put("sessionId",session.id);put("mode",session.mode.name);put("complete",false);put("artifactWorkflow",true)
                put("nextTool",progress.nextTool);put("nextArguments",progress.nextArguments)
                put("message","This focused session ends with the requested inspected/exported artifact, not a whole-world native activation receipt")
            }))
        }
        return receipt(publicationService.finish(arguments))
    }

    private fun withResourcePack(result:McpToolResult,resourcePack:ResourcePackReceipt)=result.copy(
        text=result.text+" Resource pack: ${resourcePack.path}",
        structuredContent=JsonObject(result.structuredContent+buildJsonObject {
            put("resourcePackReady",true);put("resourcePack",encode(resourcePack))
            result.structuredContent["sessionId"]?.let {sid->put("nextArguments",buildJsonObject {
                put("sessionId",sid);put("resourcePackFilename",resourcePack.filename)
            })}
        }))

    /** Archive I/O is not an authoring failure: keep the valid saved pack/session and retry only its export. */
    private fun resourcePackFailure(result:McpToolResult,id:String,arguments:JsonObject,failure:Exception):McpToolResult {
        val filename=arguments["resourcePackFilename"]?.jsonPrimitive?.content ?: "$id.wspack"
        val retryName=(sequenceOf("$id.wspack")+(1..32).asSequence().map {"$id-$it.wspack"})
            .firstOrNull {it!=filename && !Files.exists(resourcePackExchange.exportsDirectory().resolve(it),java.nio.file.LinkOption.NOFOLLOW_LINKS)}
        val message=failure.message?.take(2048) ?: failure.javaClass.simpleName
        return McpToolResult.error("The frozen pack is preserved; its .wspack export needs repair: $message",
            JsonObject(result.structuredContent+buildJsonObject {
                put("id",id);put("complete",false);put("resourcePackReady",false);put("packPreserved",true)
                put("resourcePackError",buildJsonObject {put("code","RESOURCE_PACK_EXPORT_FAILED");put("filename",filename);put("message",message)})
                put("nextTool","worldsmith_export_resource_pack")
                put("nextArguments",buildJsonObject {put("id",id);retryName?.let {put("filename",it)}})
                if(retryName==null)put("requiredAuthoring",encode(listOf("filename")))
                put("nextInstruction","Retry only the saved bundle export using the given id and an unused .wspack filename. Preserve the existing file and current session; do not regenerate modules, textures or drawings. Then finish with the exported filename as resourcePackFilename.")
                result.structuredContent["sessionId"]?.let {sid->
                    put("continueTool",WorldsmithWorkflow.FINISH_TOOL)
                    put("continueArguments",buildJsonObject {put("sessionId",sid);retryName?.let {put("resourcePackFilename",it)}})
                }
            }))
    }
    private fun incomplete(sessionId:String,nextTool:String,reason:String)=publicationService.incomplete(sessionId,nextTool,reason)

    /** Resolves a managed pack directory, refusing anything that leaves it. */
    private fun managedPack(id:String):Path?=packStore.managed(id)
    private fun persistPack(manifest:WorldsmithPackManifest,contents:Map<String,String>,binaries:Map<String,ByteArray> = emptyMap()):Path=packStore.persist(manifest,contents,binaries)

    private fun packPayload(pack: WorldsmithPack): JsonObject = buildJsonObject {
        put("manifest", encode(pack.manifest))
        put("terrain", encode(pack.terrain))
        put("biomes", encode(pack.biomes))
        put("features", encode(pack.features))
        put("structures", encode(pack.structures))
        put("theme", encode(pack.theme));put("blocks",encode(pack.blocks));put("creatures",encode(pack.creatures));put("items",encode(pack.items))
        put("quests",encode(pack.quests))
        put("assets",encode(pack.manifest.assets))
        put("computedId", pack.computedId)
    }

    private fun diagnosticsJson(diagnostics: List<Diagnostic>): JsonArray = buildJsonArray {
        diagnostics.forEach { add(encode(it)) }
    }

    private inline fun <reified T> encode(value: T): JsonElement =
        WorldsmithJson.format.encodeToJsonElement(serializer<T>(), value)

    private inline fun <reified T> decode(element: JsonElement): T =
        WorldsmithJson.format.decodeFromJsonElement(serializer<T>(), element)

    private fun requiredString(arguments: JsonObject, name: String): String =
        arguments[name]?.jsonPrimitive?.contentOrNull ?: error("$name must be a string")

    private fun optionalString(arguments: JsonObject, name: String): String =
        arguments[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun requiredObject(arguments: JsonObject, name: String): JsonObject =
        arguments[name]?.let { runCatching { it.jsonObject }.getOrNull() } ?: error("$name must be an object")

    private fun emptySchema(): JsonObject = objectSchema(emptyMap(), emptyList())

    private fun objectSchema(properties: Map<String, JsonObject>, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

    private fun beginWorldSchema(): JsonObject = objectSchema(
        properties = mapOf(
            "detail" to buildJsonObject {put("type","string");put("enum",JsonArray(listOf(JsonPrimitive("summary"),JsonPrimitive("full"))))},
            "mode" to buildJsonObject {put("type","string");put("enum",encode(WorkflowMode.entries.map {it.name}));put("description","WORLDGEN_ONLY preserves the existing guided worldgen route; COMPLETE_WORLD requires a named linked design plan and all promised modules/Bosses; STANDALONE is one artifact without world-publication obligations.")},
            "prompt" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_PROMPT_LENGTH)
                put("description", "The player's description of the world they want, in their own words.")
            },
        ),
        required = listOf("prompt"),
    )

    private fun sessionSchema(): JsonObject = objectSchema(
        properties = mapOf(
            "sessionId" to buildJsonObject {
                put("type", "string")
                put("description", "The sessionId returned by " + WorldsmithWorkflow.BEGIN_TOOL + ".")
            },
        ),
        required = listOf("sessionId"),
    )

    private fun writePackSchema(): JsonObject = objectSchema(
        properties = mapOf(
            "sessionId" to buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "The sessionId from " + WorldsmithWorkflow.BEGIN_TOOL +
                        ", so the run can be finished. Omit only when writing a pack outside a guided run.",
                )
            },
            "displayName" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_DISPLAY_NAME_LENGTH)
            },
            "description" to buildJsonObject {
                put("type", "string")
                put("maxLength", MAX_DESCRIPTION_LENGTH)
            },
            "expectedRevision" to buildJsonObject { put("type","integer");put("description","Required with sessionId; shared revision returned by content or architecture draft tools.") },
            "resourcePackFilename" to resourcePackFilenameSchema(),
            "theme" to documentSchema("Required WorldTheme inline or in session draft; read worldsmith_get_content_contract module=theme."),
            "blocks" to documentSchema("CustomBlockLibrary; omitted uses draft or an explicit empty library."),
            "creatures" to documentSchema("CreatureLibrary; omitted uses draft or an explicit empty library."),
            "items" to documentSchema("CustomItemLibrary schema 1 ordinary items or schema 2 equipment/consumables/actions; omitted uses draft. Schema 2 requires format 6."),
            "representativeContent" to documentSchema("Optional player-facing icon reference: {kind:item|block,id:<existing local id>}. Uses that content's existing PNG without activating the world."),
            "quests" to documentSchema("QuestLibrary for one linear main quest chain, kill_creature/deliver_item objectives and ordinary item rewards; omitted uses draft or an explicit empty library."),
            "terrain" to documentSchema(
                "A TerrainPlan matching the template, with procedural terrain and hydrology controls derived from the player's prompt.",
            ),
            "biomes" to documentSchema("A BiomePlan object matching the template."),
            "features" to documentSchema("A FeatureLibrary object matching the template."),
            "structures" to documentSchema("StructureLibrary with complete definitions and optional architecture plan. Omit to use the session drafts and plan. Guided publication requires all planned groups, landmark and independent structures; an empty library is only valid outside guided runs."),
        ),
        required = listOf("displayName"),
    )

    private fun resourcePackFilenameSchema():JsonObject=buildJsonObject {
        put("type","string");put("maxLength",128)
        put("description","Optional plain .wspack export filename in the dedicated resource-packs/exports directory; default <bundleId>.wspack. Existing different files are preserved. No paths or URLs.")
    }

    private fun documentSchema(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }

    companion object {
        private const val PACK_FORMAT_VERSION = 6
        private const val BUILTIN_PACK = "worldsmith/packs/ashlands"
        private const val MANIFEST_FILE = "worldsmith.json"
        private const val TERRAIN_FILE = "terrain.json"
        private const val BIOMES_FILE = "biomes.json"
        private const val FEATURES_FILE = "features.json"
        private const val MAX_PROMPT_LENGTH = 4000
        private const val MAX_DISPLAY_NAME_LENGTH = 128
        private const val MAX_DESCRIPTION_LENGTH = 2048
        private const val MAX_WORLD_PLANNING_GUIDE_CHARS = 2048
        private val PACK_ID = Regex("^[0-9a-f]{64}$")
    }
}
