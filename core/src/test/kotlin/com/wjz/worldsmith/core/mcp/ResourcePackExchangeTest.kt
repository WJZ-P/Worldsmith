package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Deterministic exchange/CAS tests; no worker, game launch, networking or archived Java execution. */
class ResourcePackExchangeTest {
    @TempDir lateinit var root:Path
    private val base by lazy {WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")}
    private val packDirectory get()=root.resolve("packs")
    private val exchange by lazy {ResourcePackExchange(packDirectory)}
    private fun fixture(itemName:String="Reusable token"):WorldsmithPack {
        val png=ContentTextureMcp.pixels(listOf("#536f91"),List(16) {List(16) {0}})
        return WorldContentBundleIO.create("Reusable world","Exchange fixture",base.terrain,base.biomes,base.features,base.structures,base.theme,
            assets=mapOf(png.descriptor.id to png.bytes),items=CustomItemLibrary(items=listOf(CustomItemDefinition("token",itemName,png.descriptor.id))))
    }
    private fun inbox(pack:WorldsmithPack,name:String="world.wspack"):Path=exchange.inboxDirectory().resolve(name).also {WorldsmithResourceArchive.write(pack,it)}

    @Test fun `import publishes original bundle and reusable PNG blobs without activation`() {
        val pack=fixture();val path=inbox(pack)
        val inspected=exchange.inspect(path.fileName.toString())
        assertEquals("INSPECTED",inspected.status)
        assertFalse(Files.exists(packDirectory.resolve(pack.manifest.id)))
        val imported=exchange.importPack(path.fileName.toString())
        assertEquals("IMPORTED",imported.status)
        assertEquals(pack.manifest.id,imported.bundleId)
        assertFalse(imported.activated);assertFalse(imported.sourceExecuted);assertFalse(imported.reusedExisting)
        assertEquals(1,imported.counts["pngAssets"])
        val loaded=WorldsmithPackLoader.loadDirectory(Path.of(requireNotNull(imported.savedPackPath)))
        assertEquals(pack.manifest.id,loaded.computedId)
        assertEquals(pack.items,loaded.items)
        val id=pack.manifest.assets.single().id
        assertArrayEquals(pack.assets.getValue(id),Files.readAllBytes(root.resolve("content-assets/${id.take(2)}/$id.blob")))
        assertTrue(Files.isRegularFile(path),"Input archive remains available after import")
    }

    @Test fun `content dedup retains the already saved metadata and archive input`() {
        val pack=fixture()
        val existing=pack.copy(manifest=pack.manifest.copy(displayName="Existing name",description="Existing description"))
        val saved=ManagedPackStore(packDirectory).importValidated(existing)
        val before=Files.readAllBytes(saved.resolve("worldsmith.json"))
        inbox(pack)
        val result=exchange.importPack("world.wspack")
        assertTrue(result.reusedExisting);assertTrue(result.existingMetadataRetained)
        assertEquals("Existing name",result.displayName);assertEquals("Existing description",result.description)
        assertArrayEquals(before,Files.readAllBytes(saved.resolve("worldsmith.json")))
        assertEquals(result.bundleId,exchange.importPack("world.wspack").bundleId)
    }

    @Test fun `export is one deterministic reusable file and preserves conflicting existing files`() {
        val pack=fixture();ManagedPackStore(packDirectory).importValidated(pack)
        val exported=exchange.exportPack(pack.manifest.id)
        assertEquals("${pack.manifest.id}.wspack",exported.filename)
        assertEquals(pack.manifest.id,WorldsmithResourceArchive.read(Path.of(exported.path)).pack.computedId)
        val again=exchange.exportPack(pack.manifest.id)
        assertTrue(again.reusedExisting)
        assertEquals(exported.archiveInfo.archiveSha256,again.archiveInfo.archiveSha256)
        val different=fixture("Different token");ManagedPackStore(packDirectory).importValidated(different)
        val bytes=Files.readAllBytes(Path.of(exported.path))
        assertThrows(Exception::class.java) {exchange.exportPack(different.manifest.id,exported.filename)}
        assertArrayEquals(bytes,Files.readAllBytes(Path.of(exported.path)))
    }

    @Test fun `legacy import preserves format while ordinary managed writer stays current only`() {
        val draft=base.copy(manifest=base.manifest.copy(formatVersion=3,modules=base.manifest.modules.filterKeys {it in WorldContentBundleIO.LEGACY_MODULES}),
            items=CustomItemLibrary(),quests=QuestLibrary(),creatures=CreatureLibrary())
        val encoded=WorldContentBundleIO.encode(draft)
        val legacy=draft.copy(manifest=encoded.manifest,computedId=encoded.manifest.id)
        val store=ManagedPackStore(packDirectory)
        assertThrows(IllegalArgumentException::class.java) {store.persist(encoded.manifest,encoded.texts,encoded.binaries)}
        inbox(legacy,"legacy.wspack")
        val result=exchange.importPack("legacy.wspack")
        assertEquals(3,result.formatVersion)
        assertEquals(legacy.manifest.id,result.bundleId)
        assertEquals(3,WorldsmithPackLoader.loadDirectory(Path.of(result.savedPackPath!!)).manifest.formatVersion)
    }

    @Test fun `inbox names and listings stay bounded to regular basename files`() {
        assertTrue(ResourcePackExchange.isSafeFilename("月蚀资源.wspack"))
        for(name in listOf("../world.wspack","..\\world.wspack","C:\\world.wspack","https://host/world.wspack","CON.wspack","bad..name.wspack")) {
            assertFalse(ResourcePackExchange.isSafeFilename(name),name)
            assertThrows(IllegalArgumentException::class.java) {exchange.inspect(name)}
        }
        Files.writeString(exchange.inboxDirectory().resolve("a.wspack"),"listing does not decode")
        Files.writeString(exchange.inboxDirectory().resolve("b.wspack"),"listing does not decode")
        Files.writeString(exchange.inboxDirectory().resolve("ignore.txt"),"not an archive")
        Files.createDirectory(exchange.inboxDirectory().resolve("folder.wspack"))
        val listing=exchange.listInbox(1)
        assertEquals(listOf("a.wspack"),listing.entries.map {it.filename})
        assertTrue(listing.truncated)
        assertThrows(IllegalArgumentException::class.java) {exchange.listInbox(257)}
    }

    @Test fun `linked inbox files are rejected rather than imported from outside`() {
        val target=root.resolve("outside.wspack");WorldsmithResourceArchive.write(fixture(),target)
        val linked=exchange.inboxDirectory().resolve("linked.wspack")
        val made=runCatching {Files.createSymbolicLink(linked,target);true}.getOrDefault(false)
        assumeTrue(made,"Host does not permit symbolic links")
        assertFalse(exchange.listInbox().entries.any {it.filename=="linked.wspack"})
        assertThrows(IllegalArgumentException::class.java) {exchange.importPack("linked.wspack")}
        assertTrue(Files.isRegularFile(target))
        val outsidePacks=Files.createDirectory(root.resolve("outside-packs"))
        val linkedPacks=Files.createSymbolicLink(root.resolve("linked-packs"),outsidePacks)
        assertThrows(IllegalArgumentException::class.java) {ManagedPackStore(linkedPacks).importValidated(fixture())}
        assertEquals(0L,Files.list(outsidePacks).use {it.count()},"Canonicalizing a short path must not permit a real linked store root")
    }

    @Test fun `invalid archives publish no pack and do not change an existing library`() {
        val pack=fixture();ManagedPackStore(packDirectory).importValidated(pack)
        val before=Files.readAllBytes(packDirectory.resolve(pack.manifest.id).resolve("worldsmith.json"))
        Files.writeString(exchange.inboxDirectory().resolve("broken.wspack"),"not a ZIP archive")
        assertThrows(Exception::class.java) {exchange.importPack("broken.wspack")}
        assertEquals(listOf(pack.manifest.id),exchange.listPacks().map {it.bundleId})
        assertArrayEquals(before,Files.readAllBytes(packDirectory.resolve(pack.manifest.id).resolve("worldsmith.json")))
    }

    @Test fun `texture batch shares one revision preserves modules and rejects stale or partial attachment`() {
        val pack=fixture();ManagedPackStore(packDirectory).importValidated(pack)
        val sessions=WorkflowSessions(directory=root.resolve("drafts"));val session=sessions.begin("Reuse textures")
        val content=WorldContentMcpService(ManagedPackStore(packDirectory),false,sessions,root.resolve("content-assets"))
        val api=ResourcePackMcpService(exchange,content).tools().single {it.name=="worldsmith_attach_pack_textures"}
        fun attach(revision:Long,ids:List<String>?=null)=api.handler(buildJsonObject {
            put("sessionId",session.id);put("expectedRevision",revision);put("packId",pack.manifest.id)
            ids?.let {put("assetIds",McpJson.encode(it))}
        })
        assertTrue(attach(0,listOf(pack.manifest.assets.single().id,"0".repeat(64))).isError)
        assertEquals(session,sessions.find(session.id))
        val first=attach(0);assertFalse(first.isError,first.text)
        val saved=sessions.find(session.id)!!
        assertEquals(1L,saved.revision);assertEquals(1,saved.contentAssets.size)
        assertEquals(session.contentModules,saved.contentModules);assertTrue(saved.structures.isEmpty());assertNull(saved.packId)
        assertFalse(first.structuredContent.getValue("modulesChanged").jsonPrimitive.boolean)
        assertFalse(first.structuredContent.getValue("sourceExecuted").jsonPrimitive.boolean)
        assertTrue(attach(0).isError);assertEquals(saved,sessions.find(session.id))
        assertFalse(attach(1).isError);assertEquals(saved,sessions.find(session.id),"Reattaching identical bytes is idempotent at the current revision")
    }

    @Test fun `manifest catalog stays cheap but corrupted source identity never supplies claimed pack textures`() {
        val pack=fixture();val directory=ManagedPackStore(packDirectory).importValidated(pack)
        Files.writeString(directory.resolve(pack.manifest.modulePath("terrain")),WorldsmithJson.encode(pack.terrain.copy(seed=981L)))
        assertEquals(listOf(pack.manifest.id),exchange.listPacks().map {it.bundleId},"Catalog only reads manifest metadata")
        assertThrows(IllegalArgumentException::class.java) {exchange.loadPack(pack.manifest.id)}
        val sessions=WorkflowSessions();val session=sessions.begin("Do not attach tampered sources")
        val content=WorldContentMcpService(ManagedPackStore(packDirectory),false,sessions,root.resolve("content-assets"))
        val api=ResourcePackMcpService(exchange,content).tools().single {it.name=="worldsmith_attach_pack_textures"}
        val result=api.handler(buildJsonObject {put("sessionId",session.id);put("expectedRevision",0);put("packId",pack.manifest.id)})
        assertTrue(result.isError);assertTrue(result.text.contains("immutable content address"))
        assertEquals(session,sessions.find(session.id))
        assertThrows(IllegalArgumentException::class.java) {content.attachPackTextures(session.id,0,pack.copy(computedId="0".repeat(64)))}
    }
}
