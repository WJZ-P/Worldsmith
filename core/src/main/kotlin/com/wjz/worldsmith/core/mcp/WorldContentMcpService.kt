package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Base64

/** Durable modular authoring; planning, semantic validation and activation are separate receipts. */
class WorldContentMcpService(private val store:ManagedPackStore, private val nativeAdapterPresent:Boolean,
    private val sessions:WorkflowSessions=WorkflowSessions(), assetDirectory:Path?=null) {
    private val registry=ExistingWorldContentModules.registry()
    private val assets=assetDirectory?.let {ContentAssetStore(it,ContentAssetValidation.MAX_ASSET_BYTES)}
    fun capabilities():JsonObject=buildJsonObject {
        put("frameworkVersion",2);put("packFormat",3)
        put("implementedLayer","typed seven-module bundles, PNG assets, revision-checked authoring and native adapter hooks")
        put("installedModules",McpJson.encode(registry.descriptors));put("plannedModules",McpJson.encode(ExistingWorldContentModules.plannedModules))
        put("customBlockRuntime",nativeAdapterPresent);put("customCreatureRuntime",nativeAdapterPresent)
        put("geckoLibIntegration",false);put("nativeAdapterPresent",nativeAdapterPresent)
        put("newPackFormatEnabled",true);put("legacyPackFormats",JsonArray(emptyList()));put("activationVerified",false)
        put("runtimeScope","one local integrated-server world; remote multiplayer content negotiation is not installed")
        put("assetAuthoring","actual PNG upload or indexed-pixel texture authoring; not a hosted image-generation service")
        put("customBlockReference","worldsmith:content/<blockId>; no arbitrary state properties or raw host slot ids")
        put("nextTool","worldsmith_get_content_contract")
    }
    fun tools():List<McpTool> {
        val str=McpJson.type("string");val obj=McpJson.type("object");val integer=McpJson.type("integer")
        val revision=mapOf("sessionId" to str,"expectedRevision" to integer)
        return listOf(
            McpTool("worldsmith_get_content_framework","Read world content capabilities","Read installed modules and lifecycle boundaries; does not activate content.",McpJson.schema(emptyMap(),emptyList()),true,handler={McpToolResult.success(capabilities())}),
            McpTool("worldsmith_get_content_contract","Read a typed content contract","Read exact theme, blocks or creatures fields and limits before authoring. Existing worldgen domain contracts remain separate.",McpJson.schema(mapOf("module" to str),listOf("module")),true,handler={contract(McpJson.string(it,"module"))}),
            McpTool("worldsmith_put_content_modules","Commit a world content draft","Atomically merge complete typed module documents at expectedRevision: theme, blocks, creatures, terrain, biomes, features. Structure tools own architecture. removeAssets detaches obsolete handles without deleting their stored bytes. Repairable links may remain in drafts. Invalidates publication.",McpJson.schema(revision+mapOf("modules" to obj,"removeAssets" to McpJson.array()),listOf("sessionId","expectedRevision","modules")),false,handler=::putModules),
            McpTool("worldsmith_get_content_draft","Read current world content draft","Read durable modules, immutable asset handles and shared revision without executing sources.",McpJson.schema(mapOf("sessionId" to str),listOf("sessionId")),true,handler={draft(session(it))}),
            McpTool("worldsmith_put_texture_asset","Attach an immutable PNG texture","Upload actual PNG bytes as base64. Validates bytes and dimensions and attaches its content address at expectedRevision. No filesystem path or URL is accepted.",McpJson.schema(revision+mapOf("pngBase64" to str),listOf("sessionId","expectedRevision","pngBase64")),false,handler={a->current(a);attach(a,ContentTextureMcp.upload(McpJson.string(a,"pngBase64")))}),
            McpTool("worldsmith_create_pixel_texture","Author an indexed-pixel PNG","Create a real PNG from palette colors (#RRGGBB/#RRGGBBAA) and rectangular rows of zero-based color indices, up to 256x256. Return the actual preview and durable handle.",McpJson.schema(revision+mapOf("palette" to McpJson.array(),"rows" to buildJsonObject {put("type","array");put("items",buildJsonObject {put("type","array");put("items",integer)})}),listOf("sessionId","expectedRevision","palette","rows")),false,handler={a->current(a);attach(a,ContentTextureMcp.pixels(McpJson.strings(a,"palette"),a.getValue("rows").jsonArray.map {row->row.jsonArray.map {it.jsonPrimitive.int}}))}),
            McpTool("worldsmith_preview_texture_asset","Inspect an attached PNG","Read and hash-check one session texture; return the actual PNG without mutation.",McpJson.schema(mapOf("sessionId" to str,"assetId" to str),listOf("sessionId","assetId")),true,handler={a->
                val handle=requireNotNull(session(a).contentAssets[McpJson.string(a,"assetId")]) {"Unknown session asset"}
                val bytes=requireNotNull(assets) {"Asset storage is not installed"}.read(handle)
                McpToolResult.success(buildJsonObject {put("asset",McpJson.encode(portable(handle)));put("verified",true)},images=listOf(McpImage(Base64.getEncoder().encodeToString(bytes))))
            }),
            McpTool("worldsmith_plan_world_content","Plan a modular world draft","Read-only symbols, links and capabilities. Accept scope/modules/assets or sessionId for durable drafts. Not full semantic validation or activation.",McpJson.schema(mapOf("sessionId" to str,"scope" to str,"modules" to obj,"assets" to buildJsonObject {put("type","array");put("items",obj)}),emptyList()),true,handler={a->result(registry.plan(if("sessionId" in a) input(session(a)) else McpJson.decode<WorldContentInput>(a),availableCapabilities()))}),
            McpTool("worldsmith_inspect_world_content","Inspect a saved content bundle","Re-read and validate a format-3 bundle, then report its catalog and compile order. Does not assert successful game resource reload.",McpJson.schema(mapOf("id" to str),listOf("id")),true,handler={a->
                val path=store.managed(McpJson.string(a,"id")) ?: return@McpTool McpToolResult.error("Unknown managed pack")
                val pack=WorldsmithPackLoader.loadDirectory(path);val diagnostics=WorldsmithPackValidator.validate(pack)
                if(diagnostics.any {it.severity==DiagnosticSeverity.ERROR}) McpToolResult.error("Saved bundle needs repair",buildJsonObject {put("diagnostics",McpJson.encode(diagnostics))})
                else result(registry.plan(ExistingWorldContentModules.input(pack),availableCapabilities()))
            })
        )
    }
    fun assetBytes(session:WorkflowSession?):Map<String,ByteArray> = session?.contentAssets?.mapValues {(_,asset)->requireNotNull(assets) {"Asset storage is not installed"}.read(asset)}.orEmpty()
    private fun session(a:JsonObject)=requireNotNull(sessions.find(McpJson.string(a,"sessionId"))) {"Unknown session; begin or resume a world draft"}
    private fun current(a:JsonObject)=session(a).also {
        require(!it.archived) {"Resume the archived draft before editing"}
        require(it.revision==a.getValue("expectedRevision").jsonPrimitive.long) {"DRAFT_REVISION_CONFLICT: current ${it.revision}"}
    }
    private fun putModules(a:JsonObject):McpToolResult {
        val s=current(a);val modules=a.getValue("modules").jsonObject.mapValues {it.value.jsonObject}
        val remove=McpJson.strings(a,"removeAssets");require(remove.all {it in s.contentAssets}) {"Unknown attached asset"}
        require(modules.isNotEmpty() || remove.isNotEmpty());require(modules.values.sumOf {it.toString().toByteArray().size.toLong()}<=4*1024*1024) {"Module edit exceeds 4 MiB"}
        modules.forEach {(id,document)->when(id) {
            "theme"->McpJson.decode<WorldTheme>(document);"blocks"->McpJson.decode<CustomBlockLibrary>(document);"creatures"->McpJson.decode<CreatureLibrary>(document)
            "terrain"->McpJson.decode<TerrainPlan>(document);"biomes"->McpJson.decode<BiomePlan>(document);"features"->McpJson.decode<FeatureLibrary>(document)
            else->error("Unknown authorable module '$id'; structure tools own architecture; quests/achievements are not installed")
        }}
        return draft(requireNotNull(sessions.putContent(s.id,s.revision,modules,removeAssets=remove)) {"Draft is not active"})
    }
    private fun attach(a:JsonObject,texture:TextureAsset):McpToolResult {
        val s=current(a)
        require(s.contentAssets.size<ContentAssetValidation.MAX_ASSETS || texture.descriptor.id in s.contentAssets)
        val handle=requireNotNull(assets) {"Asset storage is not installed"}.put(texture.bytes,"image/png")
        val saved=requireNotNull(sessions.putContent(s.id,s.revision,assets=mapOf(handle.id to handle))) {"Draft is not active"}
        return McpToolResult.success(buildJsonObject {put("sessionId",s.id);put("revision",saved.revision);put("asset",McpJson.encode(portable(handle)));put("width",texture.width);put("height",texture.height);put("published",false)},images=listOf(McpImage(Base64.getEncoder().encodeToString(texture.bytes))))
    }
    private fun portable(asset:ContentAsset)=asset.copy(path=ContentAssetValidation.path(asset.sha256))
    private fun input(s:WorkflowSession)=WorldContentInput(s.id,s.contentModules+mapOf("structures" to McpJson.encode(com.wjz.worldsmith.core.structure.StructureLibrary(structures=s.structures.values.toList(),architecture=s.architecture)).jsonObject),s.contentAssets.values.map(::portable))
    private fun draft(s:WorkflowSession)=McpToolResult.success(buildJsonObject {
        put("sessionId",s.id);put("revision",s.revision);put("modules",JsonObject(s.contentModules));put("assets",McpJson.encode(s.contentAssets.values.map(::portable)))
        put("structureCount",s.structures.size);put("published",s.packId!=null);put("packId",s.packId?.let(::JsonPrimitive) ?: JsonNull)
        put("missingModules",McpJson.encode((WorldContentBundleIO.REQUIRED_MODULES-setOf("structures"))-s.contentModules.keys));put("nextTool","worldsmith_plan_world_content")
    })
    private fun availableCapabilities()=if(nativeAdapterPresent) ExistingWorldContentModules.nativeCapabilities() else emptyMap()
    private fun result(plan:WorldContentPlan):McpToolResult {
        val body=buildJsonObject {
            put("plan",McpJson.encode(plan));put("catalogValid",plan.catalogValid);put("capabilitiesSatisfied",plan.capabilitiesSatisfied)
            put("planOnly",true);put("activationVerified",false);put("nativeReferencesChecked",false)
            put("validationScope","typed documents and catalog links; write_pack owns whole-bundle semantic checks, finish_world owns native activation")
        }
        return if(plan.catalogValid) McpToolResult.success(body) else McpToolResult.error("World content plan needs repair",body)
    }
    private fun contract(module:String):McpToolResult {
        require(module in setOf("theme","blocks","creatures")) {"Use worldsmith_get_contract for other worldgen domains"}
        val text=javaClass.classLoader.getResourceAsStream("prompts/contract/$module.system.md")?.bufferedReader()?.use {it.readText()} ?: error("Missing content contract: $module")
        return McpToolResult.success(buildJsonObject {put("module",module);put("schemaVersion",1);put("contract",text)})
    }
}
