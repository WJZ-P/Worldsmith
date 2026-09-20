package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityCapabilities
import com.wjz.worldsmith.core.ability.AbilityCompiler
import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.content.CreatureCategory
import com.wjz.worldsmith.core.content.QuestPrerequisiteMode
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.story.StoryConditions
import com.wjz.worldsmith.core.story.StoryFactRef
import com.wjz.worldsmith.core.story.StoryTruth
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.function.Function

/** Evidence for portable authoring and connected geometry, not a claim of native navigation or human playtime. */
class ImmersiveVillageExampleTest {
    @TempDir lateinit var temporary: Path

    @Test fun `three residents and both real marker destinations share a continuous authored route`() {
        val pack = ImmersiveVillageExample.create()
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertEquals(10, pack.manifest.formatVersion)
        assertEquals(3, pack.structures.schemaVersion)
        assertEquals(3, pack.story.characters.size)
        assertTrue(pack.creatures.creatures.all { it.category == CreatureCategory.PASSIVE && it.spawn.biomes.isEmpty() })
        val geometry = StructureGeometryCompiler.compile(pack.structures.structures.single().blueprint)
        val markers = geometry.interactions.filterIsInstance<StructureInteraction.StoryAnchor>()
        assertEquals(5, markers.size)
        assertTrue(markers.all { it.at in geometry.reachableFeet }, "Every actual place and resident must have a connected authored feet route")
        assertTrue(ImmersiveVillageExample.ENTRY_POSITION in geometry.reachableFeet)
        assertTrue(geometry.size.z > 32, "The slice crosses chunk boundaries instead of hiding inside one room")
        val lamp = geometry.voxels.single { it.position == ImmersiveVillageExample.LAMP_POSITION }
        assertEquals("minecraft:polished_deepslate", lamp.material.block)
        assertFalse(lamp.position in geometry.reachableFeet, "The changed lamp is above the traversable route")
    }

    @Test fun `knowledge is gradual and rumor remains explicitly attributed`() {
        val story = ImmersiveVillageExample.story()
        val facts = story.facts.associate { it.id to it.initial }.toMutableMap()
        val lookup = Function<StoryFactRef, AbilityValue> { facts.getValue(it.id) }
        fun known() = story.knowledge.filter { StoryConditions.test(it.discoverWhen, lookup) }
        assertTrue(known().isEmpty())
        facts[ImmersiveVillageExample.MET_KEEPER] = AbilityValue.BoolValue(true)
        assertEquals(listOf("keeper_account"), known().map { it.id })
        facts["heard_forager"] = AbilityValue.BoolValue(true)
        assertEquals(StoryTruth.LEGEND, known().single { it.id == "forest_legend" }.truth)
        facts[ImmersiveVillageExample.RUIN_SEEN] = AbilityValue.BoolValue(true)
        assertEquals(StoryTruth.FACT, known().single { it.id == "beacon_record" }.truth)
        assertFalse(known().any { it.id.startsWith("living_") })
        facts[ImmersiveVillageExample.RESOLUTION] = AbilityValue.NumberValue(2.0)
        facts[ImmersiveVillageExample.RETURNED] = AbilityValue.BoolValue(true)
        assertFalse(known().any { it.id.startsWith("living_") }, "A promised outcome is not evidence of physical application")
        facts[ImmersiveVillageExample.APPLIED] = AbilityValue.NumberValue(2.0)
        assertTrue(known().any { it.id == "living_memory" })
        assertFalse(known().any { it.id == "living_light" })
    }

    @Test fun `manual exclusive commitments converge without requiring the unchosen ending`() {
        val quests = ImmersiveVillageExample.quests().quests
        val paths = quests.filter { it.exclusiveGroup == "beacon_choice" }
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.manualAccept && it.prerequisites == listOf(ImmersiveVillageExample.ROAD) })
        assertEquals(setOf(1.0, 2.0), paths.map { (it.onAccept.single().value as AbilityValue.NumberValue).value }.toSet())
        assertTrue(paths.all { it.onAccept.single().fact.id == ImmersiveVillageExample.ROUTE })
        val merged = quests.single { it.id == ImmersiveVillageExample.RETURN }
        assertEquals(QuestPrerequisiteMode.ANY, merged.prerequisiteMode)
        assertEquals(paths.map { it.id }.toSet(), merged.prerequisites.toSet())
        assertTrue(quests.single { it.id == "hearth_forager" }.optional)
    }

    @Test fun `lost key can be recast with real item costs without resetting the story`() {
        val story = ImmersiveVillageExample.story()
        val recovery = story.trades.single { it.id == "replace_key" }
        assertEquals("minecraft:copper_ingot", recovery.inputs.single().item)
        assertEquals(3, recovery.inputs.single().count)
        assertEquals("worldsmith:item/${ImmersiveVillageExample.KEY}", recovery.outputs.single().item)
        assertEquals(0, recovery.maxUsesPerPlayer)
        assertFalse(recovery.changes.any { it.fact.id == ImmersiveVillageExample.RESOLUTION || it.fact.id == ImmersiveVillageExample.ROUTE })
        assertTrue(story.characters.all { it.respawnTicks != null && it.routines.size == 2 })
    }

    @Test fun `resident death records history and recovery requires a fresh live conversation`() {
        val story = ImmersiveVillageExample.story()
        val keeper = story.characters.single { it.id == ImmersiveVillageExample.KEEPER }
        assertEquals(1200, keeper.respawnTicks)
        assertEquals("keeper_was_lost", keeper.onDeath.single().fact.id)
        val facts = story.facts.associate { it.id to it.initial }.toMutableMap()
        val lookup = Function<StoryFactRef, AbilityValue> { facts.getValue(it.id) }
        val recovery = ImmersiveVillageExample.quests().quests.single { it.id == "hearth_reweave" }
        val recovered = story.knowledge.single { it.id == "keeper_rewoven" }
        facts[ImmersiveVillageExample.MET_KEEPER] = AbilityValue.BoolValue(true)
        assertFalse(StoryConditions.test(recovery.discoverWhen, lookup))
        facts["keeper_was_lost"] = keeper.onDeath.single().value
        assertTrue(StoryConditions.test(recovery.discoverWhen, lookup))
        assertTrue(StoryConditions.test(story.knowledge.single { it.id == "stone_oath" }.discoverWhen, lookup))
        assertFalse(StoryConditions.test(recovered.discoverWhen, lookup), "A historic initial meeting is not proof of a post-death reunion")
        val reunion = story.dialogues.single { it.id == "keeper_story" }.nodes.single { it.id == "greeting" }.options.single { it.id == "after_loss" }
        assertEquals("heard_after_loss", reunion.changes.single().fact.id)
        facts["heard_after_loss"] = reunion.changes.single().value
        assertTrue(StoryConditions.test(recovered.discoverWhen, lookup))
        assertEquals(AbilityValue.BoolValue(true), facts["keeper_was_lost"], "Reunion must not erase death history")
        assertTrue(recovery.optional)
        assertTrue(ImmersiveVillageExample.blueprint().interactions.filterIsInstance<StructureInteraction.Sign>().any { "石裔留誓" in it.front })
    }

    @Test fun `physical outcomes use durable projections while programs are disposable presentation`() {
        val programs = ImmersiveVillageExample.programs().programs
        assertEquals(2, programs.size)
        for (program in programs) {
            val compiled = AbilityCompiler.compile(program, AbilityCapabilities.standard())
            assertTrue(compiled.usedCapabilities.keys.containsAll(listOf("fx.sound", "fx.telegraph")))
            assertFalse(compiled.usedCapabilities.keys.contains("world.set_block"))
            assertTrue(program.source.contains("\"place_origin\""))
            assertEquals(program.source, ImmersiveVillageExample.source(program.id))
        }
        val projections=ImmersiveVillageExample.story().projections
        assertEquals(setOf("minecraft:sea_lantern", "minecraft:amethyst_block"), projections.map { it.blocks.first().desired.block }.toSet())
        assertTrue(projections.all { it.blocks.first().offset == com.wjz.worldsmith.core.story.StoryOffset(0, 3, 2) && it.blocks.size == 2 })
        assertTrue(projections.all { it.onApplied.any { change -> change.fact.id == ImmersiveVillageExample.APPLIED } })
    }

    @Test fun `opposite and late promises require a personal witness and returning requires a claimed route`() {
        val story=ImmersiveVillageExample.story();val facts=story.facts.associate { it.id to it.initial }.toMutableMap()
        val lookup=Function<StoryFactRef,AbilityValue> { facts.getValue(it.id) }
        val quiet=ImmersiveVillageExample.quests().quests.single { it.id==ImmersiveVillageExample.REMEMBER }
        val objective=(quiet.objectives.single() as com.wjz.worldsmith.core.content.QuestObjective.Fact).condition
        facts[ImmersiveVillageExample.ROUTE]=AbilityValue.NumberValue(2.0)
        facts[ImmersiveVillageExample.RESOLUTION]=AbilityValue.NumberValue(1.0)
        facts[ImmersiveVillageExample.APPLIED]=AbilityValue.NumberValue(1.0)
        facts[ImmersiveVillageExample.RUIN_SEEN]=AbilityValue.BoolValue(true)
        assertTrue(StoryConditions.test(quiet.availableWhen,lookup),"Another player's outcome must not lock an accepted promise")
        assertFalse(StoryConditions.test(objective,lookup),"A global result alone must not complete somebody else's promise")
        val greeting=story.dialogues.single { it.id=="keeper_story" }.nodes.single { it.id=="greeting" }
        assertTrue(StoryConditions.test(greeting.options.single { it.id=="witness_light" }.condition,lookup))
        facts[ImmersiveVillageExample.WITNESSED]=AbilityValue.BoolValue(true)
        assertTrue(StoryConditions.test(objective,lookup))
        val returning=greeting.options.single { it.id=="return_light" }
        assertFalse(StoryConditions.test(returning.condition,lookup),"Return dialogue must follow the actual reward claim")
        facts[ImmersiveVillageExample.ROUTE_CLAIMED]=AbilityValue.BoolValue(true)
        assertTrue(StoryConditions.test(returning.condition,lookup))
        assertEquals(AbilityValue.NumberValue(2.0),facts[ImmersiveVillageExample.ROUTE])
    }

    @Test fun `all story identities sources and assets round trip in a real immutable archive`() {
        val pack = ImmersiveVillageExample.create()
        val path = temporary.resolve("hearth-village.wspack")
        val written = WorldsmithResourceArchive.write(pack, path)
        val restored = WorldsmithResourceArchive.read(path)
        assertEquals(written.archiveSha256, restored.info.archiveSha256)
        assertEquals(pack.computedId, restored.pack.computedId)
        assertEquals(pack.story, restored.pack.story)
        assertEquals(pack.abilities, restored.pack.abilities)
        assertEquals(pack.structures.structures.single().blueprint.interactions, restored.pack.structures.structures.single().blueprint.interactions)
        assertEquals(pack.computedId, WorldContentBundleIO.encode(restored.pack).manifest.id)
    }
}
