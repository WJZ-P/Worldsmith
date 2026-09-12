package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResourcePackIconPreviewTest {
    @TempDir lateinit var root:Path
    private val base by lazy {WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")}
    private val exchange by lazy {ResourcePackExchange(root.resolve("packs"))}

    private fun fixture():WorldsmithPack {
        val ordinary=ContentTextureMcp.pixels(listOf("#638b62"),List(16) {List(16) {0}})
        val relic=ContentTextureMcp.pixels(listOf("#dbb766"),List(16) {List(16) {0}})
        return WorldContentBundleIO.create("The Ember Road","Follow the ember beacons through the mountains.",base.terrain,base.biomes,base.features,base.structures,base.theme,
            assets=mapOf(ordinary.descriptor.id to ordinary.bytes,relic.descriptor.id to relic.bytes),
            blocks=CustomBlockLibrary(blocks=listOf(CustomBlockDefinition("beacon_stone","Beacon Stone",textureAsset=ordinary.descriptor.id))),
            items=CustomItemLibrary(items=listOf(
                CustomItemDefinition("leaf","River Leaf",ordinary.descriptor.id),
                CustomItemDefinition("oath_seal","Ember Oath",relic.descriptor.id,kind=CustomItemKind.RELIC,rarity=CustomItemRarity.EPIC)
            )))
    }

    private fun store(pack:WorldsmithPack):Path=ManagedPackStore(root.resolve("packs")).importValidated(pack)

    @Test fun `legacy icon selection prefers a signature relic and does not rewrite any pack text`() {
        val pack=fixture(); val directory=store(pack)
        val before=Files.readAllBytes(directory.resolve("worldsmith.json"))
        val details=exchange.describePack(pack.manifest.id)
        assertEquals(ContentKey("item","oath_seal"),details.icon!!.content)
        val pixels=ResourcePackIconPreview.read(directory,details.icon!!)
        assertEquals(32,pixels.width);assertEquals(32,pixels.height);assertEquals(1024,pixels.argb.size)
        assertTrue(pixels.argb.all {it==0xffdbb766.toInt()})
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("worldsmith.json")))
        assertEquals(pack.manifest.id,WorldsmithPackLoader.loadDirectory(directory).computedId)
    }

    @Test fun `an authored block reference wins over relic heuristics in directories and archives`() {
        val source=fixture(); val pack=source.copy(manifest=source.manifest.copy(representativeContent=ContentKey("block","beacon_stone")))
        val directory=store(pack)
        val managed=exchange.describePack(pack.manifest.id)
        assertEquals(ContentKey("block","beacon_stone"),managed.icon!!.content)
        val archive=exchange.inboxDirectory().resolve("ember.wspack");WorldsmithResourceArchive.write(pack,archive)
        val archived=exchange.describeInbox("ember.wspack")
        assertEquals(managed.icon,archived.icon)
        assertArrayEquals(ResourcePackIconPreview.read(directory,managed.icon!!).argb,ResourcePackIconPreview.read(archive,archived.icon!!).argb)
    }

    @Test fun `a descriptor cannot redirect preview reads outside its own content addressed asset`() {
        val pack=fixture(); val directory=store(pack); val icon=exchange.describePack(pack.manifest.id).icon!!
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePackIconPreview.read(directory,icon.copy(asset=icon.asset.copy(path="../outside.png")))
        }
        val image=directory.resolve(icon.asset.path!!); val damaged=Files.readAllBytes(image)
        damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte();Files.write(image,damaged)
        assertThrows(IllegalArgumentException::class.java) {ResourcePackIconPreview.read(directory,icon)}
        // Counts remain cheap metadata even when the separate image preview fails.
        assertEquals(2,exchange.describePack(pack.manifest.id).counts["items"])
    }

    @Test fun `different worlds resolve their own preview assets instead of using the active world`() {
        val pack=fixture();val directory=store(pack);val first=exchange.describePack(pack.manifest.id).icon!!
        val second=first.copy(asset=pack.manifest.assets.first {it.id!=first.asset.id},content=ContentKey("item","leaf"))
        assertFalse(ResourcePackIconPreview.read(directory,first).argb.contentEquals(ResourcePackIconPreview.read(directory,second).argb))
    }
}
