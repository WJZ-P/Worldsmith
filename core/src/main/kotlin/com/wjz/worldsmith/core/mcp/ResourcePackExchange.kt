package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAssetStore
import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.pack.ResourceArchiveInfo
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable data class ResourcePackInboxEntry(val filename:String,val byteLength:Long,val modifiedMillis:Long)
@Serializable data class ResourcePackInboxListing(val directory:String,val entries:List<ResourcePackInboxEntry>,val truncated:Boolean=false)
@Serializable data class ResourcePackSummary(
    val bundleId:String,val displayName:String,val description:String,val formatVersion:Int,val assetCount:Int,val path:String,
)
@Serializable data class ResourcePackReceipt(
    val operation:String,val status:String,val filename:String,val path:String,
    val bundleId:String,val displayName:String,val description:String,val formatVersion:Int,
    val archiveInfo:ResourceArchiveInfo,val moduleIds:List<String>,val assetIds:List<String>,val counts:Map<String,Int>,
    val savedPackPath:String?=null,val reusedExisting:Boolean=false,val existingMetadataRetained:Boolean=false,
    val activated:Boolean=false,val sourceExecuted:Boolean=false,
    val validationScope:String="Core archive integrity, bundle identity, typed modules and references; native activation is separate",
)

/** Java/UI/MCP shared exchange. Only inbox basenames and managed hashes cross this API, never arbitrary import paths. */
class ResourcePackExchange(packDirectory:Path) {
    private val packDirectory:Path
    private val exchangeDirectory:Path
    private val inbox:Path
    private val exports:Path
    private val store:ManagedPackStore
    private val assets:ContentAssetStore
    init {
        val requested=packDirectory.toAbsolutePath().normalize()
        Files.createDirectories(requested.parent)
        val parent=requested.parent.toRealPath()
        this.packDirectory=regularDirectory(parent.resolve(requested.fileName))
        exchangeDirectory=regularDirectory(parent.resolve("resource-packs"))
        inbox=regularDirectory(exchangeDirectory.resolve("inbox"))
        exports=regularDirectory(exchangeDirectory.resolve("exports"))
        store=ManagedPackStore(this.packDirectory)
        assets=ContentAssetStore(regularDirectory(parent.resolve("content-assets")),ContentAssetValidation.MAX_ASSET_BYTES)
    }

    fun inboxDirectory():Path=inbox
    fun exportsDirectory():Path=exports

    /** Filename listing only; selecting Inspect performs full archive validation. */
    @JvmOverloads fun listInbox(limit:Int=64):ResourcePackInboxListing {
        require(limit in 1..MAX_LISTED) {"List limit must be 1..$MAX_LISTED"}
        requireDirectory(inbox)
        val paths=Files.list(inbox).use {it.limit(MAX_SCANNED.toLong()+1).toList()}
        val entries=paths.take(MAX_SCANNED).asSequence().filter {isSafeFilename(it.fileName.toString()) && regularFile(it,inbox)}
            .map {ResourcePackInboxEntry(it.fileName.toString(),Files.size(it),Files.getLastModifiedTime(it,NOFOLLOW_LINKS).toMillis())}
            .sortedBy {it.filename}.take(limit+1).toList()
        return ResourcePackInboxListing(inbox.toString(),entries.take(limit),paths.size>MAX_SCANNED || entries.size>limit)
    }

    /** Manifest-only catalog, not a validation receipt or repeated geometry/PNG scan. */
    @JvmOverloads fun listPacks(limit:Int=64):List<ResourcePackSummary> {
        require(limit in 1..MAX_LISTED) {"List limit must be 1..$MAX_LISTED"}
        requireDirectory(packDirectory)
        val paths=Files.list(packDirectory).use {it.limit(MAX_SCANNED.toLong()).toList()}
        return paths.asSequence().filter {PACK_ID.matches(it.fileName.toString()) && Files.isDirectory(it,NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(it) && it.toRealPath()==it}.sortedBy {it.fileName.toString()}.mapNotNull { directory ->
            runCatching {readManifest(directory).let {manifest ->
                require(manifest.id==directory.fileName.toString()) {"Manifest address differs from its directory"}
                ResourcePackSummary(manifest.id,manifest.displayName,manifest.description,manifest.formatVersion,manifest.assets.size,directory.toString())
            } }.getOrNull()
        }.take(limit).toList()
    }

    fun inspect(filename:String):ResourcePackReceipt {
        val path=inboxFile(filename)
        val read=WorldsmithResourceArchive.read(path)
        return receipt("inspect","INSPECTED",path,read.pack,read.info)
    }

    /** Immutable PNG blobs may remain after a failed publication, but no partial pack/session becomes visible. */
    @Synchronized fun importPack(filename:String):ResourcePackReceipt {
        val path=inboxFile(filename)
        val read=WorldsmithResourceArchive.read(path)
        val pack=read.pack
        val existed=store.managed(pack.manifest.id)!=null
        val bytes=pack.assets
        pack.manifest.assets.forEach { descriptor ->
            require(descriptor.id==descriptor.sha256 && descriptor.mediaType=="image/png") {"Reusable textures require content-addressed PNG handles"}
            val saved=assets.put(bytes.getValue(descriptor.id),"image/png")
            require(saved.id==descriptor.id) {"Imported texture identity changed"}
        }
        val target=store.importValidated(pack)
        val actual=readManifest(target)
        return receipt("import","IMPORTED",path,pack,read.info).copy(displayName=actual.displayName,description=actual.description,
            savedPackPath=target.toString(),reusedExisting=existed,
            existingMetadataRetained=actual.displayName!=pack.manifest.displayName || actual.description!=pack.manifest.description)
    }

    @JvmOverloads @Synchronized fun exportPack(id:String,filename:String?=null):ResourcePackReceipt {
        val pack=loadPack(id)
        val name=filename ?: "$id.wspack"
        require(isSafeFilename(name)) {"Use one plain .wspack filename, not a path or URL"}
        requireDirectory(exports)
        val target=exports.resolve(name)
        val existed=Files.exists(target,NOFOLLOW_LINKS)
        if(existed)require(regularFile(target,exports)) {"Existing export must be a regular unlinked file"}
        val info=WorldsmithResourceArchive.write(pack,target)
        return receipt("export","EXPORTED",target,pack,info).copy(savedPackPath=store.managed(id)?.toString(),reusedExisting=existed)
    }

    internal fun loadPack(id:String):WorldsmithPack {
        require(PACK_ID.matches(id)) {"Use a managed bundle's lowercase SHA-256 id"}
        requireDirectory(packDirectory)
        val path=requireNotNull(store.managed(id)) {"Unknown managed bundle; import it or select an existing pack"}
        require(path.toRealPath()==path) {"Managed bundle must not be linked outside its library"}
        return WorldsmithPackLoader.loadDirectory(path).also {pack->
            require(pack.manifest.id==id && pack.computedId==id) {"Saved bundle contents differ from their immutable content address"}
        }
    }

    private fun receipt(operation:String,status:String,path:Path,pack:WorldsmithPack,info:ResourceArchiveInfo)=ResourcePackReceipt(
        operation,status,path.fileName.toString(),path.toString(),pack.manifest.id,pack.manifest.displayName,pack.manifest.description,
        pack.manifest.formatVersion,info,pack.manifest.modules.keys.sorted(),pack.manifest.assets.map {it.id}.sorted(),linkedMapOf(
            "biomes" to pack.biomes.biomes.size,"features" to pack.features.features.size,"structures" to pack.structures.structures.size,
            "blocks" to pack.blocks.blocks.size,"items" to pack.items.items.size,"creatures" to pack.creatures.creatures.size,
            "quests" to pack.quests.quests.size,"pngAssets" to pack.manifest.assets.size,"frozenDrawings" to pack.structures.artifacts.size,
        ))

    private fun inboxFile(filename:String):Path {
        require(isSafeFilename(filename)) {"Use one plain .wspack inbox filename, not a path or URL"}
        requireDirectory(inbox)
        return inbox.resolve(filename).also {require(regularFile(it,inbox)) {"Inbox archive is missing, linked or not a regular file"}}
    }
    private fun regularFile(path:Path,parent:Path)=Files.isRegularFile(path,NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && path.toRealPath().parent==parent
    private fun requireDirectory(path:Path) {
        require(Files.isDirectory(path,NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && path.toRealPath()==path) {"Exchange directories must remain regular and unlinked"}
    }
    private fun regularDirectory(path:Path):Path {Files.createDirectories(path);requireDirectory(path);return path}
    private fun readManifest(directory:Path):WorldsmithPackManifest {
        val file=directory.resolve("worldsmith.json")
        require(regularFile(file,directory) && Files.size(file) in 1..MAX_MANIFEST_BYTES.toLong()) {"Missing, linked or oversized pack manifest"}
        val bytes=Files.newInputStream(file).use {it.readNBytes(MAX_MANIFEST_BYTES+1)}
        require(bytes.size<=MAX_MANIFEST_BYTES) {"Pack manifest exceeds catalog budget"}
        return WorldsmithJson.decode<WorldsmithPackManifest>(String(bytes,Charsets.UTF_8)).also(WorldContentBundleIO::validateManifest)
    }

    companion object {
        const val MAX_LISTED=256
        private const val MAX_SCANNED=4096
        private const val MAX_MANIFEST_BYTES=512*1024
        private val PACK_ID=Regex("[a-f0-9]{64}")
        private val FILENAME=Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}_. -]{0,119}\\.wspack",RegexOption.IGNORE_CASE)
        private val RESERVED=Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]",RegexOption.IGNORE_CASE)
        @JvmStatic fun isSafeFilename(filename:String)=filename.length<=128 && FILENAME.matches(filename) && ".." !in filename &&
            !RESERVED.matches(filename.substringBefore('.').trimEnd())
    }
}
