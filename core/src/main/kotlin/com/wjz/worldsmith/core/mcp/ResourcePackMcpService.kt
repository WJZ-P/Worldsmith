package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAsset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class PackTextureAttachmentReceipt(
    val sessionId:String,val revision:Long,val bundleId:String,val assets:List<ContentAsset>,val alreadyAttachedCount:Int,
    val modulesChanged:Boolean=false,val sourceExecuted:Boolean=false,val activated:Boolean=false,
)

/** Thin MCP adapter over the same archive exchange used by native UI and the local CLI. */
class ResourcePackMcpService(private val exchange:ResourcePackExchange,private val content:WorldContentMcpService) {
    fun tools():List<McpTool> {
        val string=McpJson.type("string")
        val filename=McpJson.schema(mapOf("filename" to string),listOf("filename"))
        return listOf(
            McpTool("worldsmith_get_resource_pack_workflow","Read single-file resource pack exchange workflow",
                "Discover dedicated .wspack inbox/exports directories and a bounded inbox filename list. Reuse worldsmith_list_packs and worldsmith_inspect_world_content for the installed library. Import/export never activates a world or executes archived Java sources.",
                McpJson.schema(emptyMap(),emptyList()),true,handler={McpToolResult.success(workflow())}),
            McpTool("worldsmith_inspect_resource_pack","Inspect a single-file resource pack",
                "Read one plain .wspack filename from the dedicated inbox. Validate archive paths/budgets, bundle SHA, typed modules, PNGs and references without importing, activating or executing archived code.",
                filename,true,handler={a->result("inspect") {exchange.inspect(McpJson.string(a,"filename"))}}),
            McpTool("worldsmith_import_resource_pack","Import a validated single-file resource pack",
                "Import one .wspack inbox filename as the original immutable bundle. Writes reusable PNG blobs before publishing its hash directory. Existing identical content keeps its saved display metadata; formats 3/4 remain read-only. Does not edit a session or activate a world.",
                filename,false,idempotent=true,handler={a->result("import") {exchange.importPack(McpJson.string(a,"filename"))}}),
            McpTool("worldsmith_export_resource_pack","Export a saved bundle as one reusable file",
                "Export a managed bundle id to the dedicated exports directory. Optional plain .wspack filename defaults to <id>.wspack. Includes frozen drawings, source provenance and PNGs; incompatible existing files are preserved rather than overwritten. No source execution or activation.",
                McpJson.schema(mapOf("id" to string,"filename" to string),listOf("id")),false,idempotent=true,
                handler={a->result("export") {exchange.exportPack(McpJson.string(a,"id"),a["filename"]?.jsonPrimitive?.content)}}),
            McpTool("worldsmith_attach_pack_textures","Reuse saved bundle PNGs in an existing draft",
                "Batch-attach selected assetIds from one managed bundle to an existing session at expectedRevision; omitted assetIds means all PNGs. Uses the same immutable content-assets store and one atomic shared revision. Does not merge modules, create drawing jobs, execute old sources or activate content.",
                McpJson.schema(mapOf("sessionId" to string,"expectedRevision" to McpJson.type("integer"),"packId" to string,"assetIds" to McpJson.array()),listOf("sessionId","expectedRevision","packId")),
                false,handler=::attach),
        )
    }

    fun workflow():JsonObject=buildJsonObject {
        put("extension",".wspack");put("archiveVersion",1);put("currentBundleFormat",5);put("legacyReadOnlyFormats",McpJson.encode(listOf(3,4)))
        put("inboxDirectory",exchange.inboxDirectory().toString());put("exportsDirectory",exchange.exportsDirectory().toString())
        put("inbox",McpJson.encode(exchange.listInbox()));put("arbitraryImportPathsAccepted",false);put("urlsAccepted",false)
        put("installedPacksTool","worldsmith_list_packs");put("installedPackInspectionTool","worldsmith_inspect_world_content")
        put("inspectTool","worldsmith_inspect_resource_pack");put("importTool","worldsmith_import_resource_pack");put("exportTool","worldsmith_export_resource_pack")
        put("reuseTexturesTool","worldsmith_attach_pack_textures");put("nextTool","worldsmith_inspect_resource_pack");put("requiredAuthoring",McpJson.encode(listOf("filename")))
        put("generatedPackExportAutomatic",true);put("generatedPackTools",McpJson.encode(listOf(WorldsmithWorkflow.WRITE_TOOL,WorldsmithWorkflow.FINISH_TOOL)))
        put("includes",McpJson.encode(listOf("typed world modules","actual PNG bytes","frozen structure drawings","source provenance","original bundle hash and display metadata")))
        put("importActivatesWorld",false);put("archivedSourceExecuted",false);put("mergeModulesAutomatically",false)
        put("instruction","Generated write_pack/finish_world replies already include a ready .wspack resourcePack receipt; optional resourcePackFilename chooses its export name. To import, place one .wspack file in this host's dedicated inbox, inspect it, then import. Import retains the original content address and does not mutate an authoring session. For reuse in a current draft, batch-attach its actual PNG ids with the current shared revision; author related modules explicitly. Archive readiness is separate from native create-world activation.")
    }

    private fun result(operation:String,action:()->ResourcePackReceipt):McpToolResult=try {
        McpToolResult.success(McpJson.encode(action()).jsonObject)
    } catch(failure:Exception) {rejected(operation,failure)}

    private fun attach(a:JsonObject):McpToolResult=try {
        val pack=exchange.loadPack(McpJson.string(a,"packId"))
        val attached=content.attachPackTextures(McpJson.string(a,"sessionId"),a.getValue("expectedRevision").jsonPrimitive.long,
            pack,if("assetIds" in a)McpJson.strings(a,"assetIds") else null)
        McpToolResult.success(JsonObject(McpJson.encode(attached).jsonObject+buildJsonObject {
            put("nextTool","worldsmith_get_content_draft");put("nextArguments",buildJsonObject {put("sessionId",attached.sessionId)})
            put("instruction","These verified PNG handles are now attached. Reference their ids in explicitly authored modules; no modules or drawing jobs were copied.")
        }))
    } catch(failure:Exception) {rejected("attach_textures",failure)}

    private fun rejected(operation:String,failure:Exception):McpToolResult {
        val message=failure.message?.take(4096) ?: failure.javaClass.simpleName
        return McpToolResult.error(message,buildJsonObject {
            put("operation",operation);put("status","REJECTED");put("error",message);put("activated",false);put("sourceExecuted",false)
            put("nextTool",if(operation=="attach_textures")"worldsmith_get_content_draft" else "worldsmith_get_resource_pack_workflow")
        })
    }
}
