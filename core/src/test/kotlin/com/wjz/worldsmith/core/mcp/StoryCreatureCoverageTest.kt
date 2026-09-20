package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.story.*
import com.wjz.worldsmith.core.structure.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoryCreatureCoverageTest {
    private fun pack() = ImmersiveVillageExample.create()
    private fun residentIds(p: WorldsmithPack) = p.story.characters.map { it.creature }.toSet()
    private fun withoutMarkers(p: WorldsmithPack, remove: (StructureInteraction.StoryAnchor) -> Boolean) = p.copy(
        structures = p.structures.copy(structures = p.structures.structures.map { s ->
            s.copy(blueprint = s.blueprint.copy(interactions = s.blueprint.interactions.filterNot { it is StructureInteraction.StoryAnchor && remove(it) }))
        }))

    @Test fun `frozen placed residents count without inventing natural spawns`() {
        val p = pack(); val actual = WorldDesignCoverage.frozen(p)
        assertTrue(actual.structureCreatures.containsAll(residentIds(p)))
        residentIds(p).forEach { id ->
            assertFalse(id in actual.naturalCreatures)
            assertTrue(actual.links.any { it.relation == DesignRelation.CONTAINS_ENCOUNTER && it.to == ContentKey("creature", id) })
        }
    }

    @Test fun `story declarations alone and orphan resident markers do not count`() {
        val p = pack()
        for (broken in listOf(withoutMarkers(p) { it.character != null }, withoutMarkers(p) { it.character == null })) {
            assertTrue(WorldDesignCoverage.frozen(broken).structureCreatures.intersect(residentIds(p)).isEmpty())
        }
    }

    @Test fun `only initially satisfied resident conditions prove an initial encounter route`() {
        val p = pack()
        val condition = StoryCondition.Compare(StoryFactRef("resident_gate"), StoryComparison.EQ, AbilityValue.BoolValue(true))
        fun gated(initial: Boolean) = p.copy(story = p.story.copy(
            facts = p.story.facts + StoryFact("resident_gate", StoryFactScope.WORLD, StoryFactType.BOOL, AbilityValue.BoolValue(initial)),
            characters = p.story.characters.map { it.copy(spawnWhen = condition) }))
        assertTrue(WorldDesignCoverage.frozen(gated(false)).structureCreatures.intersect(residentIds(p)).isEmpty())
        assertTrue(WorldDesignCoverage.frozen(gated(true)).structureCreatures.containsAll(residentIds(p)))
    }

    @Test fun `disabled sites and mismatched home declarations do not count`() {
        val p = pack()
        val disabled = p.copy(structures = p.structures.copy(structures = p.structures.structures.map { it.copy(
            placement = it.placement.copy(region = StructureRegion("disabled", chance = 0.0))) }))
        val wrongHome = p.copy(story = p.story.copy(places = p.story.places.map { it.copy(structure = "another_structure") }))
        for (broken in listOf(disabled, wrongHome)) assertTrue(WorldDesignCoverage.frozen(broken).structureCreatures.intersect(residentIds(p)).isEmpty())
    }

    @Test fun `unemitted assembly members are not a frozen resident route`() {
        val p = pack(); val original = p.structures.structures.first()
        val emptyRoot = StructureBlueprint(id = "empty_root", size = BuildPos(3, 3, 3), palette = mapOf("stone" to BuildMaterial("minecraft:stone")),
            build = listOf(BuildOperation.Fill("floor", BuildPos(0,0,0), BuildPos(2,0,2), "stone")))
        val unused = original.copy(blueprint = emptyRoot, assembly = StructureAssembly(
            pieces = mapOf(original.blueprint.id to original.blueprint), pools = mapOf("unused" to listOf(AssemblyChoice(original.blueprint.id))), variants = 1))
        val broken = p.copy(structures = p.structures.copy(structures = listOf(unused)))
        assertTrue(WorldDesignCoverage.frozen(broken).structureCreatures.intersect(residentIds(p)).isEmpty())
    }
}
