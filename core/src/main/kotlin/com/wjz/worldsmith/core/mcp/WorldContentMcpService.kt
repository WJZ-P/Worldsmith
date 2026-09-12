package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64
import com.wjz.worldsmith.core.drawhost.DrawingJob

/** Durable modular authoring; planning, semantic validation and activation are separate receipts. */
class WorldContentMcpService(private val store:ManagedPackStore, private val nativeAdapterPresent:Boolean,
    private val sessions:WorkflowSessions=WorkflowSessions(), assetDirectory:Path?=null,
    private val drawingJobs:(String)->List<DrawingJob> = { emptyList() }) {
    private val registry=ExistingWorldContentModules.registry()
    private val assets=assetDirectory?.let {ContentAssetStore(it,ContentAssetValidation.MAX_ASSET_BYTES)}
    private val textureInbox=assetDirectory?.resolveSibling("texture-inbox")?.let {
        Files.createDirectories(it);require(!Files.isSymbolicLink(it)) {"Texture inbox must be a regular directory"};it.toRealPath()
    }
    fun capabilities():JsonObject=buildJsonObject {
        put("frameworkVersion",5);put("packFormat",6)
        put("authoringBudgets",WorldsmithAuthoringBudgets.snapshot())
        put("implementedLayer","typed nine-module bundles, linear quest progress, world-bound items and portable texture authoring")
        put("installedModules",McpJson.encode(registry.descriptors));put("plannedModules",McpJson.encode(ExistingWorldContentModules.plannedModules))
        put("customBlockRuntime",nativeAdapterPresent);put("customCreatureRuntime",nativeAdapterPresent)
        put("customItemRuntime",nativeAdapterPresent);put("creatureDrops",nativeAdapterPresent)
        put("geckoLibIntegration",false);put("nativeAdapterPresent",nativeAdapterPresent)
        put("newPackFormatEnabled",true);put("legacyPackFormats",McpJson.encode(listOf(3,4,5)));put("legacyFormatsReadOnly",true);put("activationVerified",false)
        put("linearQuestRuntime",nativeAdapterPresent)
        put("runtimeScope","one local integrated-server world; remote multiplayer content negotiation is not installed")
        put("assetAuthoring","actual PNG upload or indexed-pixel texture authoring; not a hosted image-generation service")
        put("textureWorkflowContract","worldsmith_get_texture_workflow")
        put("modelIndependentTextureRecipes",true)
        put("creatureAuthoring", "deterministic bone/cube recipes, mirroring, automatic UVs and frozen textured pose previews")
        put("creatureAuthoringContract", "worldsmith_get_creature_authoring_contract")
        put("creativeModeContentTab", nativeAdapterPresent)
        put("customBlockReference","worldsmith:content/<blockId>; no arbitrary state properties or raw host slot ids")
        put("customItemReference","worldsmith:item/<itemId>; plain resources and relics, not new tools or equipment")
        put("nextTool","worldsmith_get_content_contract")
        put("generationModes",McpJson.encode(WorkflowMode.entries.map { it.name }))
        put("completeWorldPlanTool","worldsmith_put_world_design_plan");put("generationProgressTool","worldsmith_get_generation_progress")
        put("completeWorldCoverageRequiresActualContent",true)
        put("resourcePackWorkflowTool","worldsmith_get_resource_pack_workflow");put("resourcePackExtension",".wspack")
        put("resourcePackImportsActivateWorld",false);put("savedPackTextureReuseTool","worldsmith_attach_pack_textures")
    }
    fun tools():List<McpTool> {
        val str=McpJson.type("string");val obj=McpJson.type("object");val integer=McpJson.type("integer")
        val revision=mapOf("sessionId" to str,"expectedRevision" to integer)
        return listOf(
            McpTool("worldsmith_get_content_framework","Read world content capabilities","Read installed modules and lifecycle boundaries; does not activate content.",McpJson.schema(emptyMap(),emptyList()),true,handler={McpToolResult.success(capabilities())}),
            McpTool("worldsmith_put_world_design_plan","Commit a named complete-world design plan","Persist one prompt-derived goal, named targets, real relationship promises and Boss/quest links at the shared expectedRevision. COMPLETE_WORLD publication checks the actual frozen content against this plan; mode changes may upgrade scope but never silently downgrade an existing complete-world promise.",
                McpJson.schema(revision+mapOf("plan" to obj,"mode" to buildJsonObject {put("type","string");put("enum",McpJson.encode(WorkflowMode.entries.map {it.name}))}),listOf("sessionId","expectedRevision","plan")),false,handler=::putDesignPlan),
            McpTool("worldsmith_get_generation_progress","Read the next missing generation step","Cheap read-only module, named-target, texture-handle, drawing-job, quest and Boss coverage. Does not compile drawings, decode every PNG, reload Minecraft or score aesthetics. Follow nextTool/nextInstruction while preserving existing work.",
                McpJson.schema(mapOf("sessionId" to str),listOf("sessionId")),true,handler={a->McpToolResult.success(McpJson.encode(progress(session(a))).jsonObject)}),
            McpTool("worldsmith_get_texture_workflow","Read reusable texture production workflow","Explain item icons, block tiles and creature UV atlases, plus model-independent recipes and PNG import. The mod does not supply a proprietary image-generation model.",McpJson.schema(emptyMap(),emptyList()),true,handler={ textureWorkflow() }),
            McpTool("worldsmith_build_texture","Build a reusable procedural pixel texture","Compile a bounded TextureRecipe with palette, seed and fill/noise/checker/line/stamp operations. No image model, executable source or network calls. Attaches a real PNG at expectedRevision.",McpJson.schema(revision+mapOf("recipe" to obj),listOf("sessionId","expectedRevision","recipe")),false,handler={a->
                current(a);val raw=a.getValue("recipe");require(raw.toString().toByteArray().size<=1024*1024) {"Texture recipe exceeds 1 MiB"}
                val recipe=McpJson.decode<TextureRecipe>(raw);val result=attach(a,TextureRecipes.render(recipe))
                result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {put("recipeVersion",TextureRecipes.VERSION);put("seed",recipe.seed);put("imageModelUsed",false)}))
            }),
            McpTool("worldsmith_import_texture_file","Import a PNG from the dedicated texture inbox","Read a single PNG filename from the configured host-local texture inbox, avoiding base64 through the model. No arbitrary paths, URLs or symlinks. Optional fitWidth/fitHeight plus resample=nearest explicitly resizes the entire image, preserving the original file.",McpJson.schema(revision+mapOf("filename" to str,"fitWidth" to integer,"fitHeight" to integer,"resample" to str),listOf("sessionId","expectedRevision","filename")),false,handler=::importTexture),
            McpTool("worldsmith_get_content_contract","Read a typed content contract","Read exact theme, blocks, creatures, items or quests fields, or world_design for complete-world authoring plans. Existing worldgen domain contracts remain separate.",McpJson.schema(mapOf("module" to str),listOf("module")),true,handler={contract(McpJson.string(it,"module"))}),
            McpTool("worldsmith_put_content_modules","Commit a world content draft","Atomically merge complete typed module documents at expectedRevision: theme, blocks, creatures, items, quests, terrain, biomes, features. Structure tools own architecture. removeAssets detaches obsolete handles without deleting their stored bytes. Repairable links may remain in drafts. Invalidates publication.",McpJson.schema(revision+mapOf("modules" to obj,"removeAssets" to McpJson.array()),listOf("sessionId","expectedRevision","modules")),false,handler=::putModules),
            McpTool("worldsmith_get_content_draft","Read current world content draft","Read durable modules, immutable asset handles and shared revision without executing sources.",McpJson.schema(mapOf("sessionId" to str),listOf("sessionId")),true,handler={draft(session(it))}),
            McpTool("worldsmith_put_texture_asset","Attach an immutable PNG texture","Upload actual PNG bytes as base64. Validates bytes and dimensions and attaches its content address at expectedRevision. No filesystem path or URL is accepted.",McpJson.schema(revision+mapOf("pngBase64" to str),listOf("sessionId","expectedRevision","pngBase64")),false,handler={a->current(a);attach(a,ContentTextureMcp.upload(McpJson.string(a,"pngBase64")))}),
            McpTool("worldsmith_create_pixel_texture","Author an indexed-pixel PNG","Create a real PNG from palette colors (#RRGGBB/#RRGGBBAA) and rectangular rows of zero-based color indices, up to 256x256. Return the actual preview and durable handle.",McpJson.schema(revision+mapOf("palette" to McpJson.array(),"rows" to buildJsonObject {put("type","array");put("items",buildJsonObject {put("type","array");put("items",integer)})}),listOf("sessionId","expectedRevision","palette","rows")),false,handler={a->current(a);attach(a,ContentTextureMcp.pixels(McpJson.strings(a,"palette"),a.getValue("rows").jsonArray.map {row->row.jsonArray.map {it.jsonPrimitive.int}}))}),
            McpTool("worldsmith_preview_texture_asset","Inspect an attached PNG","Read and hash-check one session texture; return the actual PNG without mutation.",McpJson.schema(mapOf("sessionId" to str,"assetId" to str),listOf("sessionId","assetId")),true,handler={a->
                val handle=requireNotNull(session(a).contentAssets[McpJson.string(a,"assetId")]) {"Unknown session asset"}
                val bytes=requireNotNull(assets) {"Asset storage is not installed"}.read(handle)
                McpToolResult.success(buildJsonObject {put("asset",McpJson.encode(portable(handle)));put("verified",true)},images=listOf(McpImage(Base64.getEncoder().encodeToString(bytes))))
            }),
            McpTool("worldsmith_plan_world_content","Plan a modular world draft","Read-only symbols, links and capabilities. Accept scope/modules/assets or sessionId for durable drafts. Not full semantic validation or activation.",McpJson.schema(mapOf("sessionId" to str,"scope" to str,"modules" to obj,"assets" to buildJsonObject {put("type","array");put("items",obj)}),emptyList()),true,handler={a->result(registry.plan(if("sessionId" in a) input(session(a)) else McpJson.decode<WorldContentInput>(a),availableCapabilities()))}),
            McpTool("worldsmith_inspect_world_content","Inspect a saved content bundle","Re-read and validate a format-3, format-4, format-5 or format-6 bundle, then report its catalog and compile order. Does not assert successful game resource reload.",McpJson.schema(mapOf("id" to str),listOf("id")),true,handler={a->
                val path=store.managed(McpJson.string(a,"id")) ?: return@McpTool McpToolResult.error("Unknown managed pack")
                val pack=WorldsmithPackLoader.loadDirectory(path);val diagnostics=WorldsmithPackValidator.validate(pack)
                if(diagnostics.any {it.severity==DiagnosticSeverity.ERROR}) McpToolResult.error("Saved bundle needs repair",buildJsonObject {put("diagnostics",McpJson.encode(diagnostics))})
                else result(registry.plan(ExistingWorldContentModules.input(pack),availableCapabilities()))
            })
        )
    }
    fun assetBytes(session:WorkflowSession?):Map<String,ByteArray> = session?.contentAssets?.mapValues {(_,asset)->requireNotNull(assets) {"Asset storage is not installed"}.read(asset)}.orEmpty()
    fun textureBytes(session:WorkflowSession,id:String):ByteArray = requireNotNull(assets) {"Asset storage is not installed"}
        .read(requireNotNull(session.contentAssets[id]) {"Texture is not attached to this session"})
    /** Attach verified saved-pack PNGs in one shared CAS; this never imports module documents or source jobs. */
    fun attachPackTextures(sessionId:String,expectedRevision:Long,pack:WorldsmithPack,assetIds:List<String>?=null):PackTextureAttachmentReceipt {
        require(pack.manifest.id==pack.computedId && WorldContentRegistry.SHA256.matches(pack.manifest.id)) {"Saved texture source must retain its verified bundle identity"}
        val current=requireNotNull(sessions.find(sessionId)) {"Unknown session; begin or resume a world draft"}
        require(!current.archived) {"Resume the archived draft before attaching textures"}
        require(current.revision==expectedRevision) {"DRAFT_REVISION_CONFLICT: expected $expectedRevision, current ${current.revision}"}
        val descriptors=pack.manifest.assets.associateBy {it.id}
        val selected=assetIds ?: descriptors.keys.sorted()
        require(selected.size<=ContentAssetValidation.MAX_ASSETS && selected.distinct().size==selected.size && selected.all {it in descriptors}) {"Select distinct PNG asset ids present in the saved bundle"}
        val combined=(current.contentAssets-selected.toSet())+selected.associateWith {descriptors.getValue(it)}
        require(combined.size<=ContentAssetValidation.MAX_ASSETS && combined.values.sumOf {requireNotNull(it.byteLength)}<=ContentAssetValidation.MAX_TOTAL_BYTES) {"Attached textures exceed the session asset budget"}
        val bytes=pack.assets
        val attached=selected.associateWith {id->
            val descriptor=descriptors.getValue(id)
            require(descriptor.id==descriptor.sha256 && descriptor.mediaType=="image/png") {"Only content-addressed PNG assets are reusable"}
            ContentAssetValidation.verify(descriptor,bytes.getValue(id))
            requireNotNull(assets) {"Asset storage is not installed"}.put(bytes.getValue(id),"image/png").also {require(it.id==id)}
        }
        val saved=requireNotNull(sessions.putContent(sessionId,expectedRevision,assets=attached)) {"Draft is no longer active"}
        return PackTextureAttachmentReceipt(sessionId,saved.revision,pack.manifest.id,attached.values.map(::portable),selected.count {it in current.contentAssets})
    }
    fun progress(session:WorkflowSession):GenerationProgress=WorldGenerationProgress.inspect(session,drawingJobs(session.id))
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
            "theme"->McpJson.decode<WorldTheme>(document);"blocks"->McpJson.decode<CustomBlockLibrary>(document);"creatures"->McpJson.decode<CreatureLibrary>(document);"items"->McpJson.decode<CustomItemLibrary>(document);"quests"->McpJson.decode<QuestLibrary>(document)
            "terrain"->McpJson.decode<TerrainPlan>(document);"biomes"->McpJson.decode<BiomePlan>(document);"features"->McpJson.decode<FeatureLibrary>(document)
            else->error("Unknown authorable module '$id'; structure tools own architecture; independent achievement modules are not installed; existing quests have a native advancement projection")
        }}
        return draft(requireNotNull(sessions.putContent(s.id,s.revision,modules,removeAssets=remove)) {"Draft is not active"})
    }
    private fun putDesignPlan(a:JsonObject):McpToolResult {
        val s=current(a)
        val plan=McpJson.decode<WorldDesignPlan>(a.getValue("plan"))
        val mode=a["mode"]?.jsonPrimitive?.content?.let {WorkflowMode.valueOf(it)} ?: s.mode
        val diagnostics=WorldDesignPlans.validate(plan,mode==WorkflowMode.COMPLETE_WORLD)
        if(diagnostics.isNotEmpty())return McpToolResult.error("World design plan needs repair",buildJsonObject {
            put("sessionId",s.id);put("revision",s.revision);put("diagnostics",McpJson.encode(diagnostics));put("nextTool","worldsmith_put_world_design_plan")
        })
        return draft(requireNotNull(sessions.putDesignPlan(s.id,s.revision,plan,mode)) {"Draft is not active"})
    }
    private fun attach(a:JsonObject,texture:TextureAsset):McpToolResult {
        val s=current(a)
        require(s.contentAssets.size<ContentAssetValidation.MAX_ASSETS || texture.descriptor.id in s.contentAssets)
        val handle=requireNotNull(assets) {"Asset storage is not installed"}.put(texture.bytes,"image/png")
        val saved=requireNotNull(sessions.putContent(s.id,s.revision,assets=mapOf(handle.id to handle))) {"Draft is not active"}
        return McpToolResult.success(buildJsonObject {put("sessionId",s.id);put("revision",saved.revision);put("asset",McpJson.encode(portable(handle)));put("width",texture.width);put("height",texture.height);put("published",false)},images=listOf(McpImage(Base64.getEncoder().encodeToString(texture.bytes))))
    }
    private fun portable(asset:ContentAsset)=asset.copy(path=ContentAssetValidation.path(asset.sha256))
    private fun importTexture(a:JsonObject):McpToolResult {
        current(a)
        val name=McpJson.string(a,"filename")
        require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,123}\\.png")) && !name.contains("..")) {"Use one plain PNG filename, not a directory or URL"}
        val inbox=requireNotNull(textureInbox) {"Texture inbox is not configured on this host"}
        val file=inbox.resolve(name)
        require(Files.isRegularFile(file,NOFOLLOW_LINKS) && file.toRealPath().startsWith(inbox) && Files.size(file) in 1..ContentAssetValidation.MAX_ASSET_BYTES.toLong()) {"Texture inbox file is missing, linked, empty or oversized"}
        val bytes=Files.newInputStream(file).use {it.readNBytes(ContentAssetValidation.MAX_ASSET_BYTES+1)}
        val original=ContentTextureMcp.upload(Base64.getEncoder().encodeToString(bytes))
        require(("fitWidth" in a)==("fitHeight" in a)) {"Supply both fitWidth and fitHeight"}
        val resample=a["resample"]?.jsonPrimitive?.content ?: "none"
        require(resample in setOf("none","nearest"))
        val texture=if("fitWidth" in a)TextureRecipes.fit(original,a.getValue("fitWidth").jsonPrimitive.int,a.getValue("fitHeight").jsonPrimitive.int,resample) else original
        val result=attach(a,texture)
        return result.copy(structuredContent=JsonObject(result.structuredContent+buildJsonObject {
            put("sourceFilename",name);put("sourceSha256",original.descriptor.sha256);put("sourceWidth",original.width);put("sourceHeight",original.height)
            put("resampled",original.descriptor.sha256!=texture.descriptor.sha256);put("sourcePreserved",true)
        }))
    }
    private fun textureWorkflow()=McpToolResult.success(buildJsonObject {
        val contract=javaClass.classLoader.getResourceAsStream("prompts/contract/textures.system.md")?.bufferedReader()?.use {it.readText()} ?: error("Missing texture workflow contract")
        put("contract",contract);put("imageModelProvidedByMod",false);put("providerIndependent",true)
        put("textureInbox",textureInbox?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("fileImportRequiresSameHostOrClientTransfer",true);put("rawPngUploadTool","worldsmith_put_texture_asset")
        put("recipeTool","worldsmith_build_texture");put("creatureUvTool","worldsmith_build_creature")
    })
    private fun input(s:WorkflowSession)=WorldContentInput(s.id,s.contentModules+mapOf("structures" to McpJson.encode(s.structureLibrary()).jsonObject),s.contentAssets.values.map(::portable))
    private fun draft(s:WorkflowSession)=McpToolResult.success(buildJsonObject {
        val progress=progress(s)
        put("sessionId",s.id);put("revision",s.revision);put("modules",JsonObject(s.contentModules));put("assets",McpJson.encode(s.contentAssets.values.map(::portable)))
        put("mode",s.mode.name);put("designPlan",s.designPlan?.let {McpJson.encode(it)} ?: JsonNull)
        put("structureCount",s.structures.size);put("published",s.packId!=null);put("packId",s.packId?.let(::JsonPrimitive) ?: JsonNull)
        put("missingModules",McpJson.encode(progress.missingModules));put("progress",McpJson.encode(progress));put("nextTool",progress.nextTool);put("nextArguments",progress.nextArguments);put("nextInstruction",progress.nextInstruction)
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
        require(module in setOf("theme","blocks","creatures","items","quests","world_design")) {"Use worldsmith_get_contract for other worldgen domains"}
        val text=javaClass.classLoader.getResourceAsStream("prompts/contract/$module.system.md")?.bufferedReader()?.use {it.readText()} ?: error("Missing content contract: $module")
        return McpToolResult.success(buildJsonObject {
            put("module",module);put("schemaVersion",if(module=="creatures")2 else 1);put("contract",text)
            if(module=="creatures") {put("supportedSchemaVersions",McpJson.encode(listOf(1,2)));put("defaultSchemaVersion",1)}
        })
    }
}
