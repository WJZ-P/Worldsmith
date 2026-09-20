package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.authoring.AuthoringContext
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoryAnchorTest {
    private fun blueprint()=StructureBlueprint(id="courtyard",size=BuildPos(40,4,8),palette=mapOf("stone" to BuildMaterial("minecraft:stone")),build=listOf(
        BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(39,0,7),"stone"),BuildOperation.Clear("air",BuildPos(0,1,0),BuildPos(39,3,7))),
        interactions=listOf(StructureInteraction.StoryAnchor(BuildPos(33,1,2),"village","keeper")))
    @Test fun `typed marker requires real feet headroom and floor and round trips without identity changes`() {
        val b=blueprint();assertTrue(StructureValidator.validateBlueprint(b).isEmpty())
        assertEquals(b,WorldsmithJson.decode<StructureBlueprint>(WorldsmithJson.encode(b)))
        val blocked=b.copy(build=b.build+BuildOperation.SetBlock("wall",BuildPos(33,2,2),"stone"))
        assertTrue(StructureValidator.validateBlueprint(blocked).any { it.code=="STORY_ANCHOR_CLEARANCE" })
        val geometry=StructureGeometryCompiler.compile(b).copy(drawingSource=true)
        val tiles=StructureTiling.tiles(geometry)
        val marker=tiles.flatMap { tile -> tile.geometry.interactions.map { tile.offset to it } }.single()
        assertEquals(BuildPos(32,0,0),marker.first)
        assertEquals(StructureInteraction.StoryAnchor(BuildPos(1,1,2),"village","keeper"),marker.second)
    }
    @Test fun `structure format three is explicit and older modules reject markers`() {
        val s=WorldStructureDefinition("village_structure",blueprint(),StructurePlacement(biomes=listOf("ashfall_plain")))
        assertThrows(IllegalArgumentException::class.java) { StructurePackIO.files(StructureLibrary(2,listOf(s))) }
        val files=StructurePackIO.files(StructureLibrary(3,listOf(s)))
        val index=WorldsmithJson.decode<StructureIndex>(files.getValue(StructurePackIO.INDEX_FILE))
        assertEquals(3,index.schemaVersion)
        assertEquals(s.blueprint.interactions,StructurePackIO.load(index,files).structures.single().blueprint.interactions)
    }
    @Test fun `SDK component instance transforms coordinates but preserves global story IDs`() {
        val child=AuthoringContext(DrawContext(1L,emptyMap(),DrawLimits.DEFAULT))
        child.canvas(Box.of(0,0,0,3,3,3)).pen("air").fill(Box.of(0,0,0,3,3,3))
        child.canvas().pen("stone").fill(Box.of(0,0,0,3,0,3))
        child.storyAnchor(Vec3i(1,1,1),"village","keeper")
        val parent=AuthoringContext(DrawContext(2L,emptyMap(),DrawLimits.DEFAULT));parent.canvas(Box.of(-8,0,-8,8,5,8))
        val transform=GridTransform.rotateY(1).andThen(GridTransform.translate(4,0,2));parent.instance("wing",child.snapshot(),transform)
        val marker=(parent.snapshot().semantics().getValue("interactions") as List<*>).single() as Map<*,*>
        assertEquals("village",marker["place"]);assertEquals("keeper",marker["character"])
        val transformed=transform.apply(Vec3i(1,1,1));assertEquals(mapOf("x" to transformed.x(),"y" to transformed.y(),"z" to transformed.z()),marker["at"])
    }
    @Test fun `resident markers do not fabricate an independent place instance`() {
        val b=blueprint();val s=WorldStructureDefinition("village_structure",b,StructurePlacement(listOf("ashfall_plain")))
        val story=StoryLibrary(places=listOf(StoryPlace("village","Village","A valley settlement","village_structure")),
            characters=listOf(StoryCharacter("keeper","Keeper","keeper_creature","village")))
        val p=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").copy(structures=StructureLibrary(3,listOf(s)),story=story)
        assertTrue(StoryPackValidation.validate(p).any { it.code=="STORY_PLACE_UNANCHORED" })
        val anchored=s.copy(blueprint=b.copy(interactions=b.interactions+StructureInteraction.StoryAnchor(BuildPos(2,1,2),"village")))
        assertTrue(StoryPackValidation.validate(p.copy(structures=StructureLibrary(3,listOf(anchored)))).isEmpty())
    }
}
