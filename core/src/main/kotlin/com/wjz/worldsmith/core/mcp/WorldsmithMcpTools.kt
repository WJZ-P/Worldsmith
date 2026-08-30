package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.WorldsmithCore
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
import com.wjz.worldsmith.core.prompt.PromptTemplateRepository
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
            put("terrainContract", templates.load(PromptSet.DEFAULT.terrainPlan).systemPrompt)
            put("designContract", templates.load(PromptSet.DEFAULT.biomePlan).systemPrompt)
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
        val declaresHydrology = terrainDocument["shape"]
            ?.let { runCatching { it.jsonObject.containsKey("hydrology") }.getOrDefault(false) }
            ?: false
        val terrain = decode<TerrainPlan>(terrainDocument)
        val biomes = decode<BiomePlan>(requiredObject(arguments, "biomes"))
        val features = decode<FeatureLibrary>(requiredObject(arguments, "features"))
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
        )
        val manifest = WorldsmithHashUtil.finalizeManifest(draftManifest, contents)
        val pack = WorldsmithPack(manifest, terrain, biomes, features, manifest.id)
        val diagnostics = WorldsmithPackValidator.validate(pack).toMutableList()
        if (guidedSession && terrain.shape !is TerrainShape.Procedural) {
            diagnostics += Diagnostic(
                path = "terrain.shape",
                code = "PROMPT_TERRAIN_REQUIRED",
                severity = DiagnosticSeverity.ERROR,
                message = "A guided prompt run must provide a procedural terrain shape derived from its terrain contract",
            )
        }
        if (guidedSession && terrain.shape is TerrainShape.Procedural && !declaresHydrology) {
            diagnostics += Diagnostic(
                path = "terrain.shape.hydrology",
                code = "PROMPT_HYDROLOGY_REQUIRED",
                severity = DiagnosticSeverity.ERROR,
                message = "A guided prompt run must explicitly choose hydrology values, including zeros for absent water systems",
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
            "${pack.biomes.biomes.size} biomes and ${pack.features.features.size} features, stored at $directory. " +
            "It has been selected for Minecraft's world-creation screen. Nothing further is required from you."
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
            // The move removes pending on success. On failure it contains only
            // the four fixed files written above, so cleanup never walks an
            // arbitrary caller-controlled tree.
            listOf(MANIFEST_FILE, TERRAIN_FILE, BIOMES_FILE, FEATURES_FILE).forEach { name ->
                Files.deleteIfExists(pending.resolve(name))
            }
            Files.deleteIfExists(pending)
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
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun packPayload(pack: WorldsmithPack): JsonObject = buildJsonObject {
        put("manifest", encode(pack.manifest))
        put("terrain", encode(pack.terrain))
        put("biomes", encode(pack.biomes))
        put("features", encode(pack.features))
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
