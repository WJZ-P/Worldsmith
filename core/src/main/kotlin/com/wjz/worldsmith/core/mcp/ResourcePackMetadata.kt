package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.content.ContentAsset
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile

@Serializable data class ResourcePackIcon(val content:ContentKey,val asset:ContentAsset)
@Serializable data class ResourcePackDetails(val summary:ResourcePackSummary,val counts:Map<String,Int>,val icon:ResourcePackIcon?=null)

/** Bounded configuration reads for the browser; never decodes PNGs, geometry or executable source. */
internal object ResourcePackMetadata {
    private const val MANIFEST_LIMIT=512*1024
    private val kinds=listOf("biomes","structures","features","blocks","creatures","items","quests")

    fun directory(directory:Path,manifest:WorldsmithPackManifest):ResourcePackDetails {
        var remaining=WorldContentBundleIO.MAX_TEXT_BYTES
        return describe(directory,manifest) { relative ->
            val path=directory.resolve(relative).normalize()
            require(path.startsWith(directory) && Files.isRegularFile(path,NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) && path.toRealPath()==path) {"Missing or linked module configuration"}
            val text=Files.newInputStream(path,NOFOLLOW_LINKS).use {read(it,remaining)}
            remaining-=text.toByteArray(Charsets.UTF_8).size
            Json.parseToJsonElement(text).jsonObject
        }
    }

    fun archive(path:Path):ResourcePackDetails {
        require(Files.size(path) in 22..WorldsmithResourceArchive.MAX_ARCHIVE_BYTES) {"Archive is incomplete or too large"}
        return ZipFile(path.toFile(),Charsets.UTF_8).use { zip ->
            val entries=zip.entries().asSequence().take(WorldsmithResourceArchive.MAX_ENTRIES+1).toList()
            require(entries.size<=WorldsmithResourceArchive.MAX_ENTRIES && entries.map {it.name}.distinct().size==entries.size) {"Invalid or oversized archive directory"}
            fun text(name:String,limit:Int):String {
                val entry=requireNotNull(zip.getEntry(name)) {"Missing configuration: $name"}
                require(!entry.isDirectory && entry.size in 0..limit.toLong()) {"Configuration exceeds its byte budget"}
                return zip.getInputStream(entry).use {read(it,limit)}
            }
            val header=Json.parseToJsonElement(text("wspack.json",4096)).jsonObject
            require(header["format"]?.jsonPrimitive?.content=="worldsmith-resource-pack" &&
                header["containerVersion"]?.jsonPrimitive?.intOrNull==WorldsmithResourceArchive.VERSION) {"Unsupported world-pack archive"}
            val manifest=WorldsmithJson.decode<WorldsmithPackManifest>(text("worldsmith.json",MANIFEST_LIMIT))
            WorldContentBundleIO.validateManifest(manifest)
            require(header["bundleId"]?.jsonPrimitive?.content==manifest.id) {"Archive and manifest identity differ"}
            var remaining=WorldContentBundleIO.MAX_TEXT_BYTES
            describe(path,manifest) { relative ->
                val document=text(relative,remaining)
                remaining-=document.toByteArray(Charsets.UTF_8).size
                Json.parseToJsonElement(document).jsonObject
            }
        }
    }

    private fun describe(path:Path,manifest:WorldsmithPackManifest,document:(String)->JsonObject):ResourcePackDetails {
        val counts=linkedMapOf<String,Int>()
        val lists=linkedMapOf<String,List<JsonObject>>()
        for(kind in kinds) {
            val module=manifest.modules[kind]
            counts[kind]=if(module==null)0 else {
                val entries=document(module.path)[kind]
                require(entries==null || entries is JsonArray) {"Invalid $kind configuration list"}
                lists[kind]=(entries as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
                (entries as? JsonArray)?.size ?: 0
            }
        }
        counts["pngAssets"]=manifest.assets.size
        return ResourcePackDetails(ResourcePackSummary(manifest.id,manifest.displayName,manifest.description,
            manifest.formatVersion,manifest.assets.size,path.toString()),counts,icon(manifest,lists))
    }

    /** Explicit authorship wins; old worlds get a stable relic/quest-item choice without changing their bytes. */
    private fun icon(manifest:WorldsmithPackManifest,lists:Map<String,List<JsonObject>>):ResourcePackIcon? {
        fun text(value:JsonObject,key:String)=value[key]?.jsonPrimitive?.contentOrNull
        val candidates=buildList {
            for(kind in listOf("item","block")) for(entry in lists["${kind}s"].orEmpty()) {
                val id=text(entry,"id") ?: continue
                val assetId=text(entry,"textureAsset") ?: continue
                val asset=manifest.assets.firstOrNull {it.id==assetId} ?: continue
                add(Triple(ContentKey(kind,id),asset,entry))
            }
        }
        manifest.representativeContent?.let { chosen ->
            return candidates.firstOrNull {it.first==chosen}?.let {ResourcePackIcon(it.first,it.second)}
        }
        val questItems=lists["quests"].orEmpty().flatMap {quest ->
            listOf("objectives","rewards").flatMap {field -> (quest[field] as? JsonArray).orEmpty()
                .mapNotNull {(it as? JsonObject)?.get("item")?.jsonPrimitive?.contentOrNull} }
        }.toSet()
        val selected=candidates.sortedWith(compareByDescending<Triple<ContentKey,ContentAsset,JsonObject>> { (key,_,entry) ->
            val rarity=when(text(entry,"rarity")) {"EPIC"->4;"RARE"->3;"UNCOMMON"->2;else->1}
            if(key.kind=="block") 0 else rarity*100 + (if(text(entry,"kind")=="RELIC") 40 else 0) +
                (if("worldsmith:item/${key.id}" in questItems) 20 else 0)
        }.thenBy {it.first.id}).firstOrNull() ?: return null
        return ResourcePackIcon(selected.first,selected.second)
    }

    private fun read(input:InputStream,limit:Int):String {
        require(limit>=0) {"Configuration text budget exhausted"}
        val bytes=input.readNBytes(limit+1)
        require(bytes.size<=limit) {"Configuration text exceeds its byte budget"}
        return String(bytes,Charsets.UTF_8)
    }
}
