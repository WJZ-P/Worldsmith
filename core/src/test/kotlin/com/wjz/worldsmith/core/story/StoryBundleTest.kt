package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValues
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.pack.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StoryBundleTest {
    @TempDir lateinit var temp: Path
    private fun story(initial: Boolean=false)=StoryLibrary(facts=listOf(StoryFact("bridge_fixed",StoryFactScope.WORLD,StoryFactType.BOOL,AbilityValues.bool(initial))),
        knowledge=listOf(StoryKnowledge("bridge_history","The crossing","Old stone keeps a village together.",discoverWhen=StoryCondition.Compare(StoryFactRef("bridge_fixed"),StoryComparison.EQ,AbilityValues.bool(true)))),
        trades=listOf(StoryTrade("bread","Bread",listOf(StoryItemAmount("minecraft:emerald")),listOf(StoryItemAmount("minecraft:bread",2)))))
    private fun pack(initial: Boolean=false)=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").let { b ->
        WorldContentBundleIO.create("Story valley","The bridge awaits repair",b.terrain,b.biomes,b.features,b.structures,b.theme,story=story(initial)) }
    @Test fun `required story module survives portable archive with exact identity and references`() {
        val p=pack();val files=WorldContentBundleIO.encode(p)
        assertEquals(10,files.manifest.formatVersion);assertEquals("story/story.json",files.manifest.modulePath("story"));assertEquals(2,files.manifest.modules.getValue("quests").schemaVersion)
        assertEquals(2,files.manifest.modules.getValue("story").schemaVersion)
        assertTrue(WorldsmithPackValidator.validate(p).isEmpty(),WorldsmithPackValidator.validate(p).toString())
        val path=temp.resolve("story.wspack");WorldsmithResourceArchive.write(p,path);val read=WorldsmithResourceArchive.read(path).pack
        assertEquals(p.story,read.story);assertEquals(p.computedId,read.computedId)
        val catalog=ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(read),ExistingWorldContentModules.nativeCapabilities())
        assertTrue(catalog.catalogValid,catalog.diagnostics.toString());assertTrue(catalog.capabilitiesSatisfied)
        assertTrue(catalog.catalog.entries.single { it.key==ContentKey("knowledge","bridge_history") }.references.any { it.target==ContentKey("story_fact","bridge_fixed") })
    }
    @Test fun `story content changes hash and old or missing domain fails before module reads`() {
        val p=pack();assertNotEquals(p.computedId,pack(true).computedId)
        val files=WorldContentBundleIO.encode(p)
        assertThrows(IllegalArgumentException::class.java) { WorldsmithHashUtil.computeGenerationId(files.manifest,files.texts-files.manifest.modulePath("story"),files.binaries) }
        for(manifest in listOf(files.manifest.copy(formatVersion=8),files.manifest.copy(modules=files.manifest.modules-"story"))) {
            val reads=mutableListOf<String>()
            assertThrows(IllegalArgumentException::class.java) { WorldsmithPackLoader.load(WorldsmithPackSource { path -> reads+=path;check(path=="worldsmith.json");WorldsmithJson.encode(manifest) }) }
            assertEquals(listOf("worldsmith.json"),reads)
        }
    }
}
