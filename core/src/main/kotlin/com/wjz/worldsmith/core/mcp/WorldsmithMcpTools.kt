package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.analysis.BiomeDistributionAnalyzer
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.HumidityBand
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithPackFiles
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
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
    private val sessions: WorkflowSessions = WorkflowSessions(),
) {
    private val packDirectory = packDirectory.toAbsolutePath().normalize()

    fun all(): List<McpTool> = listOf(
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
            name = "worldsmith_validate_structure", title = "Validate a structure blueprint",
            description = "Check and expand one bounded construction blueprint in Core, without Minecraft. Returns geometry counts and exact diagnostics.",
            inputSchema = structureInspectionSchema(false),
            readOnly = true, handler = { inspectStructure(it, false) },
        ),
        McpTool(
            name = "worldsmith_preview_structure", title = "Preview a structure blueprint",
            description = "Write orthographic and isometric SVG views, optionally cut away above sliceY. This is a geometry preview, not an in-game screenshot; no Minecraft compiler is run.",
            inputSchema = structureInspectionSchema(true),
            readOnly = false, handler = { inspectStructure(it, true) },
        ),
        McpTool(
            name = "worldsmith_preview_assembly", title = "Preview connected structure pieces",
            description = "Compile a bounded multi-piece layout in Core, return its graph and write a schematic. No Minecraft compiler is run.",
            inputSchema = objectSchema(mapOf("structure" to documentSchema("WorldStructureDefinition"), "variant" to buildJsonObject {put("type","integer");put("minimum",0);put("maximum",7)}),listOf("structure")),
            readOnly = false, handler = ::previewAssembly,
        ),
        McpTool(
            name = "worldsmith_get_structure_example", title = "Get an executable structure example",
            description = "Return forest_shrine, wayfarer_lodge (stairs, variants, loot/sign), arcane_observatory (curves/dome/banner), or connected_courtyard (multi-piece assembly). Copy grammar, not mandatory style. Replace example biome ids.",
            inputSchema = objectSchema(mapOf("id" to buildJsonObject {put("type","string");put("enum",JsonArray(listOf("forest_shrine","wayfarer_lodge","arcane_observatory","connected_courtyard").map(::JsonPrimitive)))}),emptyList()),
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
                "Return one pack contract in full: 'terrain', 'biome', 'feature' or 'structure'. " +
                    WorldsmithWorkflow.BEGIN_TOOL + " already hands out all four, so reach for this only to " +
                    "re-read one while repairing a document.",
            inputSchema = objectSchema(
                properties = mapOf(
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
                "Validate terrain, biomes and features, compute the immutable pack id, and atomically save a portable " +
                    "pack. Content stays inside Worldsmith's managed pack directory.",
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
                    "and re-validates it, then answers complete=true when there is nothing left to do, or " +
                    "complete=false and the tool to call next. Stop only on true.",
            inputSchema = sessionSchema(),
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
    private fun beginWorld(arguments: JsonObject): McpToolResult {
        val prompt = requiredString(arguments, "prompt").trim()
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(prompt.length <= MAX_PROMPT_LENGTH) { "prompt must be at most $MAX_PROMPT_LENGTH characters" }

        val session = sessions.begin(prompt)
        val structured = buildJsonObject {
            put("sessionId", session.id)
            put("prompt", prompt)
            put("complete", false)
            put("overview", WorldsmithWorkflow.OVERVIEW)
            put("procedure", procedureJson())
            put("howToDesign", templates.load(PromptSet.DEFAULT.worldEntry).systemPrompt)
            putJsonObject("contracts") {
                PromptSet.DEFAULT.contracts.forEach { (name, ref) -> put(name, templates.load(ref).systemPrompt) }
            }
            put("styleCount", styles.list().size)
            put("climatePlacement", climatePlacementJson())
            put("nextTool", WorldsmithWorkflow.TEMPLATE_TOOL)
        }
        val text = buildString {
            appendLine(WorldsmithWorkflow.OVERVIEW)
            appendLine()
            appendLine("sessionId: ${session.id}")
            append("next: ${WorldsmithWorkflow.TEMPLATE_TOOL}")
        }
        return McpToolResult.success(structured, text)
    }

    private fun structureExample(arguments:JsonObject): McpToolResult {
        val id=arguments["id"]?.jsonPrimitive?.contentOrNull ?: "forest_shrine"
        if(id !in listOf("forest_shrine","wayfarer_lodge","arcane_observatory","connected_courtyard"))return McpToolResult.error("Unknown structure example")
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("worldsmith/structures/$id.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        if(id=="connected_courtyard") {
            val definition=WorldsmithJson.decode<WorldStructureDefinition>(text)
            return McpToolResult.success(buildJsonObject {put("structure",encode(definition));put("blueprint",encode(definition.blueprint));put("replaceExampleBiomeIds",true);put("contract","structure")})
        }
        val blueprint = WorldsmithJson.decode<StructureBlueprint>(text)
        return McpToolResult.success(buildJsonObject { put("blueprint", encode(blueprint)); put("contract", "structure") })
    }

    private fun putStructure(arguments: JsonObject): McpToolResult {
        val sessionId = requiredString(arguments, "sessionId")
        if (sessions.find(sessionId) == null) return McpToolResult.error("Unknown sessionId; begin a world first")
        val structure = decode<WorldStructureDefinition>(requiredObject(arguments, "structure"))
        if (!structure.id.matches(Regex("[a-z0-9_][a-z0-9_-]{0,63}"))) return McpToolResult.error("Invalid structure id")
        val diagnostics = StructureValidator.validateDefinition(structure)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) return McpToolResult.error("Structure geometry needs repair", buildJsonObject { put("diagnostics", diagnosticsJson(diagnostics)) })
        val updated = requireNotNull(sessions.putStructure(sessionId, structure))
        return McpToolResult.success(buildJsonObject {
            put("sessionId", sessionId); put("id", structure.id); put("draftCount", updated.structures.size)
            put("geometryValid", true); put("placementValidated", false)
            put("diagnostics", diagnosticsJson(diagnostics))
            put("nextTool", WorldsmithWorkflow.WRITE_TOOL)
        }, "Structure draft saved; biome references and placement are checked when the whole pack is written.")
    }

    private fun inspectStructure(arguments: JsonObject, preview: Boolean): McpToolResult {
        val blueprint = decode<StructureBlueprint>(requiredObject(arguments, "blueprint"))
        val variant=arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if(variant !in 0 until blueprint.variation.count || arguments["variant"]!=null && arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()==null)return McpToolResult.error("variant must be an integer inside variation.count")
        val variants = try { StructureGeometryCompiler.compileVariants(blueprint) } catch (failure: StructureBuildException) {
            return McpToolResult.error("Structure geometry needs repair", buildJsonObject { put("valid", false); put("diagnostics", diagnosticsJson(listOf(failure.diagnostic))) })
        }
        val geometry=variants[variant]
        val requestedSlice = arguments["sliceY"]?.jsonPrimitive?.contentOrNull
        val slice = requestedSlice?.toIntOrNull() ?: minOf(2, blueprint.size.y - 1)
        if (requestedSlice != null && requestedSlice.toIntOrNull() == null || slice !in 0 until blueprint.size.y) {
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
            put("reachableFeet",StructureNavigation.inspect(blueprint,geometry.voxels).reachableFeet.size)
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
        return McpToolResult.success(result)
    }

    private fun previewAssembly(arguments:JsonObject):McpToolResult {
        val definition=decode<WorldStructureDefinition>(requiredObject(arguments,"structure"))
        val compiled=try {StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(definition)))}catch(f:StructureBuildException){
            return McpToolResult.error("Structure assembly needs repair",buildJsonObject {put("diagnostics",diagnosticsJson(listOf(f.diagnostic)))})
        }
        val variant=arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val plans=compiled.plans.getValue(definition.id)
        if(variant !in plans.indices || arguments["variant"]!=null && arguments["variant"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()==null)return McpToolResult.error("variant must name an existing assembly plan")
        val plan=plans[variant];val geometry=StructureCatalogCompiler.preview(definition.id,plan)
        val directory=packDirectory.resolveSibling("structure-previews");Files.createDirectories(directory)
        val path=directory.resolve("${definition.id}-assembly-$variant.svg")
        Files.writeString(path,StructurePreview.svg(geometry),StandardCharsets.UTF_8)
        return McpToolResult.success(buildJsonObject {
            put("valid",true);put("minecraftCompiled",false);put("variant",variant);put("variantCount",plans.size)
            put("previewPath",path.toString());put("pieceCount",plan.parts.size);put("connectionCount",plan.connections.size)
            putJsonArray("parts") {plan.parts.forEach {p->add(buildJsonObject {put("blueprint",p.blueprintId);put("variant",p.variant);put("offset",encode(p.offset));put("rotation",p.rotation.name)})}}
            putJsonArray("connections") {plan.connections.forEach {c->add(buildJsonObject {put("fromPart",c.fromPart);put("fromPort",c.fromPort);put("toPart",c.toPart);put("toPort",c.toPort)})}}
        })
    }

    private fun procedureJson(): JsonArray = buildJsonArray {
        WorldsmithWorkflow.PROCEDURE.forEach { step ->
            add(
                buildJsonObject {
                    put("order", step.order)
                    put("tool", step.tool)
                    put("instruction", step.instruction)
                },
            )
        }
    }

    private fun structureInspectionSchema(preview: Boolean): JsonObject = objectSchema(
        buildMap {
            put("blueprint", documentSchema("StructureBlueprint"))
            put("variant",buildJsonObject {put("type","integer");put("minimum",0);put("maximum",7);put("description","Preview this precompiled blueprint variant; default 0.")})
            put("sliceY", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 63); put("description", "Local Y floor-plan layer; default min(2, size.y-1).") })
            if (preview) put("cutaway", buildJsonObject { put("type", "boolean"); put("description", "Hide cells above sliceY in all SVG views; defaults to false.") })
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
            put("nextTool", WorldsmithWorkflow.WRITE_TOOL)
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
        val contract = templates.load(ref).systemPrompt
        return McpToolResult.success(
            buildJsonObject {
                put("id", id)
                put("contract", contract)
            },
            contract,
        )
    }

    private fun status(): McpToolResult {
        val structured = buildJsonObject {
            put("service", "Worldsmith MCP Bridge")
            put("mcpProtocolVersion", McpHttpServer.PROTOCOL_VERSION)
            put("blueprintSchemaVersion", WorldsmithCore.BLUEPRINT_SCHEMA_VERSION)
            put("packFormatVersion", PACK_FORMAT_VERSION)
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
            put("diagnostics", diagnosticsJson(diagnostics))
        }
        return if (valid) McpToolResult.success(structured) else McpToolResult.error("Pack '$id' is invalid", structured)
    }

    private fun writePack(arguments: JsonObject): McpToolResult {
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
        val guidedSession = sessionId.isNotEmpty() && sessions.find(sessionId) != null
        val terrainDocument = requiredObject(arguments, "terrain")
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
        val biomes = decode<BiomePlan>(requiredObject(arguments, "biomes"))
        val features = decode<FeatureLibrary>(requiredObject(arguments, "features"))
        val structures = arguments["structures"]?.let { decode<StructureLibrary>(it) }
            ?: StructureLibrary(structures = sessions.find(sessionId)?.structures?.values?.toList().orEmpty())
        val structureDiagnostics = StructureValidator.validate(structures, biomes)
        if (structureDiagnostics.any { it.severity == DiagnosticSeverity.ERROR }) return McpToolResult.error(
            "Structure documents need repair", buildJsonObject { put("valid", false); put("diagnostics", diagnosticsJson(structureDiagnostics)) },
        )
        val files = WorldsmithPackFiles(TERRAIN_FILE, BIOMES_FILE, FEATURES_FILE)
        val draftManifest = WorldsmithPackManifest(
            formatVersion = PACK_FORMAT_VERSION,
            id = "0".repeat(64),
            displayName = displayName,
            description = description,
            files = files,
        )
        val contents = mapOf(
            TERRAIN_FILE to WorldsmithJson.encode(terrain),
            BIOMES_FILE to WorldsmithJson.encode(biomes),
            FEATURES_FILE to WorldsmithJson.encode(features),
        ) + StructurePackIO.files(structures)
        val manifest = WorldsmithHashUtil.finalizeManifest(draftManifest, contents)
        val pack = WorldsmithPack(manifest, terrain, biomes, features, manifest.id, structures)
        val diagnostics = WorldsmithPackValidator.validate(pack).toMutableList()
        if (guidedSession && terrain.shape !is TerrainShape.Procedural) {
            diagnostics += Diagnostic(
                path = "terrain.shape",
                code = "PROMPT_TERRAIN_REQUIRED",
                severity = DiagnosticSeverity.ERROR,
                message = "A guided prompt run must provide a procedural terrain shape derived from its terrain contract",
            )
        }
        val structured = buildJsonObject {
            put("id", manifest.id)
            put("valid", diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
            put("diagnostics", diagnosticsJson(diagnostics))
        }
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return McpToolResult.error("Generated pack did not pass Worldsmith validation", structured)
        }

        val directory = persistPack(manifest, contents)
        // An unknown session is reported rather than thrown: the pack really was
        // saved, and failing the call here would hide that.
        val recorded = sessionId.isNotEmpty() && sessions.recordPack(sessionId, manifest.id) != null
        val result = buildJsonObject {
            put("id", manifest.id)
            put("displayName", manifest.displayName)
            put("path", directory.toString())
            put("valid", true)
            put("diagnostics", diagnosticsJson(diagnostics))
            if (sessionId.isNotEmpty()) {
                put("sessionId", sessionId)
                put("sessionRecorded", recorded)
                put(
                    "nextTool",
                    if (recorded) WorldsmithWorkflow.FINISH_TOOL else WorldsmithWorkflow.BEGIN_TOOL,
                )
            }
        }
        return McpToolResult.success(result, "Saved Worldsmith pack '${manifest.displayName}' as ${manifest.id}")
    }

    /**
     * The only tool that may end a run.
     *
     * It answers the one question the agent actually has, may I stop, and it
     * answers from disk rather than from memory. Re-reading and re-validating
     * is what lets complete=true promise that a later reader finds the same
     * valid pack, instead of only that a write call once returned.
     */
    private fun finishWorld(arguments: JsonObject): McpToolResult {
        val sessionId = requiredString(arguments, "sessionId").trim()
        val session = sessions.find(sessionId) ?: return incomplete(
            sessionId,
            WorldsmithWorkflow.BEGIN_TOOL,
            "Unknown sessionId. Sessions live only as long as the bridge, so start a new run.",
        )
        val packId = session.packId ?: return incomplete(
            sessionId,
            WorldsmithWorkflow.WRITE_TOOL,
            "No pack has been saved for this session yet.",
        )
        val directory = managedPack(packId) ?: return incomplete(
            sessionId,
            WorldsmithWorkflow.WRITE_TOOL,
            "Pack '$packId' is no longer in the managed pack directory.",
        )
        val pack = runCatching { WorldsmithPackLoader.loadDirectory(directory) }.getOrElse { failure ->
            return incomplete(
                sessionId,
                WorldsmithWorkflow.WRITE_TOOL,
                "Pack '$packId' could not be read back: " + (failure.message ?: "unreadable"),
            )
        }
        if (pack.computedId != packId) {
            return incomplete(
                sessionId,
                WorldsmithWorkflow.WRITE_TOOL,
                "Pack '$packId' no longer hashes to its own id, so it was edited outside Worldsmith.",
            )
        }

        val diagnostics = WorldsmithPackValidator.validate(pack)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            val failed = buildJsonObject {
                put("sessionId", sessionId)
                put("complete", false)
                put("packId", packId)
                put("nextTool", WorldsmithWorkflow.WRITE_TOOL)
                put("diagnostics", diagnosticsJson(diagnostics))
            }
            return McpToolResult.error("Pack '$packId' no longer passes validation", failed)
        }

        val activationQueued = !session.finished
        if (activationQueued) {
            runCatching { packFinished.accept(packId) }.getOrElse { failure ->
                return incomplete(
                    sessionId,
                    WorldsmithWorkflow.FINISH_TOOL,
                    "Pack '$packId' is valid, but the Minecraft activation request failed: " +
                        (failure.message ?: "unknown error"),
                )
            }
        }
        sessions.finish(sessionId)
        val report = "Worldsmith pack '${pack.manifest.displayName}' is saved and valid: " +
            "${pack.biomes.biomes.size} biomes, ${pack.features.features.size} features and ${pack.structures.structures.size} structures, stored at $directory. " +
            "Activation has been requested for Minecraft's world-creation screen; export and reload happen there."
        val structured = buildJsonObject {
            put("sessionId", sessionId)
            put("complete", true)
            put("packId", pack.manifest.id)
            put("displayName", pack.manifest.displayName)
            put("description", pack.manifest.description)
            put("worldPresetId", "worldsmith:generated/$packId/wasteland")
            put("path", directory.toString())
            put("biomeCount", pack.biomes.biomes.size)
            put("featureCount", pack.features.features.size)
            put("structureCount", pack.structures.structures.size)
            put("minecraftCompiled", false)
            putJsonObject("climatePlacement") {
                put("semanticSlots", pack.biomes.biomes.count { it.slot != null })
                put("rawClimateBoxes", pack.biomes.biomes.count { it.climate != null })
            }
            put("diagnostics", diagnosticsJson(diagnostics))
            put("activationQueued", activationQueued)
            put("nextTool", JsonNull)
            put("report", report)
        }
        return McpToolResult.success(structured, report)
    }

    /** A false answer is not a failure; it names the step the agent still owes. */
    private fun incomplete(sessionId: String, nextTool: String, reason: String): McpToolResult {
        val structured = buildJsonObject {
            put("sessionId", sessionId)
            put("complete", false)
            put("nextTool", nextTool)
            put("reason", reason)
        }
        return McpToolResult.success(structured, "complete=false. $reason Call $nextTool next.")
    }

    /** Resolves a managed pack directory, refusing anything that leaves it. */
    private fun managedPack(id: String): Path? {
        if (!PACK_ID.matches(id)) {
            return null
        }
        val directory = packDirectory.resolve(id).normalize()
        if (!directory.startsWith(packDirectory) || !Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            return null
        }
        return directory
    }

    private fun persistPack(manifest: WorldsmithPackManifest, contents: Map<String, String>): Path {
        Files.createDirectories(packDirectory)
        val target = packDirectory.resolve(manifest.id)
        if (Files.exists(target)) {
            verifyExistingTarget(target, manifest.id)
            return target
        }

        val pending = Files.createTempDirectory(packDirectory, ".pending-")
        try {
            writeUtf8(pending.resolve(MANIFEST_FILE), WorldsmithJson.encode(manifest))
            contents.forEach { (name, content) -> writeUtf8(pending.resolve(name), content) }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, target)
            } catch (_: FileAlreadyExistsException) {
                verifyExistingTarget(target, manifest.id)
            }
        } finally {
            // Only our compiler-generated files live in pending. A successful
            // atomic move removes the directory; a failed write is cleaned up.
            if (Files.exists(pending)) {
                Files.walk(pending).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
        return target
    }

    private fun verifyExistingTarget(target: Path, expectedId: String) {
        require(Files.isDirectory(target) && !Files.isSymbolicLink(target)) {
            "Existing pack target is not a regular directory"
        }
        val existing = WorldsmithPackLoader.loadDirectory(target)
        require(existing.computedId == expectedId && existing.manifest.id == expectedId) {
            "Existing pack target does not contain the expected generation content"
        }
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun packPayload(pack: WorldsmithPack): JsonObject = buildJsonObject {
        put("manifest", encode(pack.manifest))
        put("terrain", encode(pack.terrain))
        put("biomes", encode(pack.biomes))
        put("features", encode(pack.features))
        put("structures", encode(pack.structures))
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
            "terrain" to documentSchema(
                "A TerrainPlan matching the template, with procedural terrain and hydrology controls derived from the player's prompt.",
            ),
            "biomes" to documentSchema("A BiomePlan object matching the template."),
            "features" to documentSchema("A FeatureLibrary object matching the template."),
            "structures" to documentSchema("StructureLibrary with complete inline definitions. Omit to use this session's submitted structure drafts; an empty structures list deliberately selects no structures."),
        ),
        required = listOf("displayName", "terrain", "biomes", "features"),
    )

    private fun documentSchema(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }

    companion object {
        private const val PACK_FORMAT_VERSION = 1
        private const val BUILTIN_PACK = "worldsmith/packs/ashlands"
        private const val MANIFEST_FILE = "worldsmith.json"
        private const val TERRAIN_FILE = "terrain.json"
        private const val BIOMES_FILE = "biomes.json"
        private const val FEATURES_FILE = "features.json"
        private const val MAX_PROMPT_LENGTH = 4000
        private const val MAX_DISPLAY_NAME_LENGTH = 128
        private const val MAX_DESCRIPTION_LENGTH = 2048
        private val PACK_ID = Regex("^[0-9a-f]{64}$")
    }
}
