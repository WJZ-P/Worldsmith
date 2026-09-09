package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.creatureauthoring.*
import com.wjz.worldsmith.core.drawhost.DurableFiles
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64

/** Frozen authoring artifacts are separate from published creature definitions and gameplay state. */
class CreatureAuthoringMcpService(private val sessions:WorkflowSessions,private val content:WorldContentMcpService,directory:Path) {
    private val root:Path
    init { Files.createDirectories(directory);require(!Files.isSymbolicLink(directory));root=directory.toRealPath() }
    @Serializable private data class BuildRecord(
        val schemaVersion:Int=1,val recipe:CreatureRecipe,val definition:CreatureDefinition,
        val uvLayout:CreatureUvLayout,val texture:ContentAsset,val textureGuideOnly:Boolean,
    )
    fun tools():List<McpTool> {
        val str=McpJson.type("string");val obj=McpJson.type("object")
        return listOf(
            McpTool("worldsmith_get_creature_authoring_contract","Read creature construction and preview contract",
                "Read the version-independent bone/cube builder recipe, mirroring, automatic UV layout and frozen preview flow. Does not add boss combat or execute Java source.",McpJson.schema(emptyMap(),emptyList()),true,handler={
                    val text=javaClass.classLoader.getResourceAsStream("prompts/contract/creature_authoring.system.md")?.bufferedReader()?.use {it.readText()} ?: error("Missing creature authoring contract")
                    McpToolResult.success(buildJsonObject {put("contract",text);put("views",McpJson.encode(CreaturePreview.VIEWS));put("poses",McpJson.encode(CreaturePose.POSES));put("runtimeSchema",1)})
                }),
            McpTool("worldsmith_build_creature","Build a frozen creature model candidate",
                "Compile a CreatureRecipe: named bones/cubes, mirrored limbs and automatic box UVs. Optional textureAsset must already be attached to the session. Without it the output is a diagnostic UV guide, not a final skin. Saves an immutable build artifact, but never changes content drafts or activates a world.",
                McpJson.schema(mapOf("sessionId" to str,"recipe" to obj,"textureAsset" to str),listOf("sessionId","recipe")),false,handler=::build),
            McpTool("worldsmith_get_creature_build","Read a frozen creature build",
                "Restore the exact recipe, runtime definition and UV layout for a buildId. Does not rebuild or publish.",McpJson.schema(mapOf("sessionId" to str,"buildId" to str),listOf("sessionId","buildId")),true,handler={ a->
                    val sid=session(a).id;val id=McpJson.string(a,"buildId");val record=read(sid,id)
                    McpToolResult.success(payload(sid,id,record))
                }),
            McpTool("worldsmith_preview_creature","Render an actual textured model or UV layout",
                "Offline z-buffer rendering of the frozen model and verified PNG, with the same procedural pose evaluator as the native renderer. mode=model|sheet|uv; model views isometric/isometric_back/front/back/left/right/top; poses idle/walk/windup/strike/recovery. A model image is not a Minecraft screenshot or gameplay acceptance.",
                McpJson.schema(mapOf("sessionId" to str,"buildId" to str,"mode" to str,"view" to str,"pose" to str),listOf("sessionId","buildId")),true,handler=::preview),
        )
    }
    private fun session(a:JsonObject)=requireNotNull(sessions.find(McpJson.string(a,"sessionId"))) {"Unknown session; begin or resume a world draft"}
    private fun build(a:JsonObject):McpToolResult {
        val session=session(a);require(!session.archived) {"Resume the archived draft before building"}
        val raw=a.getValue("recipe").jsonObject
        require(raw.toString().toByteArray().size<=512*1024) {"Creature recipe exceeds 512 KiB"}
        val recipe=McpJson.decode<CreatureRecipe>(raw)
        val assetId=a["textureAsset"]?.jsonPrimitive?.content
        val record:BuildRecord;val png:ByteArray
        if(assetId==null) {
            val guide=CreatureAuthoring.guide(recipe);png=guide.png
            record=BuildRecord(recipe=recipe,definition=guide.definition,uvLayout=guide.uvLayout,texture=guide.asset,textureGuideOnly=true)
        } else {
            png=content.textureBytes(session,assetId)
            val compiled=CreatureAuthoring.compile(recipe,assetId)
            val texture=ContentAsset(assetId,assetId,"image/png",png.size.toLong(),ContentAssetValidation.path(assetId))
            val size=ContentAssetValidation.verify(texture,png)
            require(size.width==recipe.atlasWidth && size.height==recipe.atlasHeight) {"PNG dimensions must exactly match the recipe atlas; import a correctly sized UV texture first"}
            record=BuildRecord(recipe=recipe,definition=compiled.definition,uvLayout=compiled.uvLayout,texture=texture,textureGuideOnly=false)
        }
        val document=WorldsmithJson.encode(record).toByteArray(Charsets.UTF_8)
        require(document.size<=2*1024*1024) {"Creature build exceeds metadata budget"}
        val id=ContentAssetValidation.hash(document);val dir=sessionDirectory(session.id)
        synchronized(this) {
            val recordPath=dir.resolve("$id.json")
            if(!Files.exists(recordPath,NOFOLLOW_LINKS)) {
                val count=Files.list(dir).use {it.filter {p->p.fileName.toString().endsWith(".json")}.count()}
                require(count<128) {"Creature build capacity reached for this session; preserve or archive the draft rather than overwriting immutable builds"}
                DurableFiles.write(dir.resolve("$id.png"),png)
                DurableFiles.write(recordPath,document)
            } else read(session.id,id)
        }
        return McpToolResult.success(JsonObject(payload(session.id,id,record)+buildJsonObject {
            put("builtFromRevision",session.revision);put("draftUpdated",false);put("nextTool","worldsmith_preview_creature")
        }),images=listOf(McpImage(Base64.getEncoder().encodeToString(if(record.textureGuideOnly)png else CreaturePreview.png(record.definition,png,"isometric","idle")))))
    }
    private fun preview(a:JsonObject):McpToolResult {
        val sid=session(a).id;val id=McpJson.string(a,"buildId");val record=read(sid,id)
        val png=readTexture(sid,id,record);val mode=a["mode"]?.jsonPrimitive?.content ?: "model"
        val rendered=when(mode) {
            "model"->CreaturePreview.png(record.definition,png,a["view"]?.jsonPrimitive?.content ?: "isometric",a["pose"]?.jsonPrimitive?.content ?: "idle")
            "sheet"->CreaturePreview.sheet(record.definition,png)
            "uv"->CreaturePreview.uvDebug(record.definition,png)
            else->error("Preview mode must be model, sheet or uv")
        }
        return McpToolResult.success(buildJsonObject {
            put("sessionId",sid);put("buildId",id);put("mode",mode);put("textureGuideOnly",record.textureGuideOnly)
            put("offlineModelPreview",true);put("minecraftScreenshot",false);put("worldActivated",false)
        },images=listOf(McpImage(Base64.getEncoder().encodeToString(rendered))))
    }
    private fun payload(sid:String,id:String,record:BuildRecord)=buildJsonObject {
        put("sessionId",sid);put("buildId",id);put("recipe",McpJson.encode(record.recipe));put("definition",McpJson.encode(record.definition))
        put("uvLayout",McpJson.encode(record.uvLayout));put("texture",McpJson.encode(record.texture));put("textureGuideOnly",record.textureGuideOnly)
        put("readyForContentDraft",!record.textureGuideOnly);put("runtimeSchema",1);put("worldActivated",false)
        put("publication",if(record.textureGuideOnly)"Paint the guide and attach the real PNG; rebuild with textureAsset. Guide textures are not auto-attached to world content." else "Merge this definition into the session CreatureLibrary using put_content_modules at the current revision; do not replace other species accidentally.")
    }
    private fun read(sid:String,id:String):BuildRecord {
        require(id.matches(Regex("[a-f0-9]{64}"))) {"Invalid creature build id"}
        val path=sessionDirectory(sid).resolve("$id.json")
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=2*1024*1024) {"Missing or oversized creature build"}
        val bytes=Files.readAllBytes(path);require(ContentAssetValidation.hash(bytes)==id) {"Frozen creature build was modified"}
        val record=WorldsmithJson.decode<BuildRecord>(String(bytes,Charsets.UTF_8));require(record.schemaVersion==1)
        readTexture(sid,id,record)
        return record
    }
    private fun readTexture(sid:String,id:String,record:BuildRecord):ByteArray {
        val path=sessionDirectory(sid).resolve("$id.png")
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=ContentAssetValidation.MAX_ASSET_BYTES) {"Missing or oversized frozen texture"}
        return Files.readAllBytes(path).also {ContentAssetValidation.verify(record.texture,it)}
    }
    private fun sessionDirectory(sid:String):Path {
        require(sid.matches(Regex("[a-f0-9]{32}"))) {"Invalid durable session identity"}
        val path=root.resolve(sid);Files.createDirectories(path)
        require(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root)) {"Creature artifact directory leaves its workspace"}
        return path
    }
}
