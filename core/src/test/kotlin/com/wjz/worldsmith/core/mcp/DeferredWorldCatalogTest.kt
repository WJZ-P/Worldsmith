package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import com.wjz.worldsmith.core.model.WorldsmithModuleFile
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The new-world catalog must list unprepared managed bundles without loading geometry, PNGs or native registries. */
class DeferredWorldCatalogTest {
    @TempDir lateinit var temp: Path
    @Test fun `both world packs are discoverable from metadata without a prepared preset`() {
        val root=temp.resolve("packs")
        for ((id,name) in listOf("a".repeat(64) to "Oathfire", "b".repeat(64) to "Nine Provinces")) {
            val directory=Files.createDirectories(root.resolve(id))
            val manifest=WorldsmithPackManifest(WorldContentBundleIO.FORMAT_VERSION,id,name,"A world waiting to be explored.",modules=WorldContentBundleIO.REQUIRED_MODULES.associateWith { WorldsmithModuleFile(if(it in setOf("quests","blocks","story"))2 else 1,"$it.json") })
            Files.writeString(directory.resolve("worldsmith.json"),WorldsmithJson.encode(manifest))
        }
        val packs=ResourcePackExchange(root).listPacks()
        assertEquals(setOf("Oathfire","Nine Provinces"),packs.map {it.displayName}.toSet())
        assertEquals(2,packs.size)
        assertTrue(packs.all { !Files.exists(Path.of(it.path).resolve("terrain.json")) },"Catalog read must not require native preparation or content files")
    }
    @Test fun `deferred creation is not a native activation receipt`() {
        val selected=PublicationStatus("WAITING_CREATION")
        assertFalse(selected.complete);assertFalse(selected.nativeValidated)
        assertFalse(selected.activated);assertFalse(selected.selectedForCreation)
    }
}
