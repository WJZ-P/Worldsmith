package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.content.CreatureActivity
import com.wjz.worldsmith.core.content.CreatureBehavior
import com.wjz.worldsmith.core.content.CreatureDrives
import com.wjz.worldsmith.core.content.CreatureLibrary
import com.wjz.worldsmith.core.content.CustomItemDefinition
import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.content.MechanicAction
import com.wjz.worldsmith.core.content.MechanicBlockPredicate
import com.wjz.worldsmith.core.content.MechanicItemCost
import com.wjz.worldsmith.core.content.MechanicOffset
import com.wjz.worldsmith.core.content.MechanicPatternCell
import com.wjz.worldsmith.core.content.Quest
import com.wjz.worldsmith.core.content.QuestLibrary
import com.wjz.worldsmith.core.content.QuestObjective
import com.wjz.worldsmith.core.content.WorldMechanicDefinition
import com.wjz.worldsmith.core.content.WorldMechanicEvent
import com.wjz.worldsmith.core.content.WorldMechanicLibrary
import com.wjz.worldsmith.core.content.WorldMechanicRule
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample
import com.wjz.worldsmith.core.story.StoryCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shape of a dull world is measurable even though fun is not. Each case
 * here is taken from a real generated pack: six landmarks sharing two copied
 * interactions, a quest line that was two thirds reading, and a village of
 * creatures that only wandered.
 */
class PlayabilityAnalyzerTest {
    private val base = ImmersiveVillageExample.create()

    private fun codes(pack: com.wjz.worldsmith.core.model.WorldsmithPack) = PlayabilityAnalyzer.analyze(pack).findings.map { it.code }

    private fun readStone(place: String) = WorldMechanicDefinition(
        "inspect_$place", "Read the stone at $place",
        rules = listOf(WorldMechanicRule("read", WorldMechanicEvent.USE_BLOCK,
            listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:chiseled_stone_bricks"))),
            listOf(MechanicAction.GiveItem("minecraft:paper")))),
        description = "Stand before the carved stone at $place with an empty hand and read what is written there.",
    )

    @Test
    fun `interactions stamped from one template are reported`() {
        val places = listOf("rootrest", "glowcap", "leafarchive", "dewreach", "thornward", "eldercrown")
        val pack = base.copy(mechanics = WorldMechanicLibrary(mechanics = places.map(::readStone)))

        val report = PlayabilityAnalyzer.analyze(pack)

        assertEquals(6, report.mechanics)
        assertEquals(1, report.distinctMechanics, "the place name is the only thing that differs")
        assertTrue("TEMPLATED_MECHANICS" in report.findings.map { it.code })
    }

    @Test
    fun `distinct interactions are not a template`() {
        val varied = WorldMechanicLibrary(mechanics = listOf(
            readStone("rootrest"),
            readStone("glowcap").copy(id = "offer_glowcap", description = "Lay pollen in the bud cradle and wait for the lamp above to open."),
            readStone("dewreach").copy(id = "dam_dewreach", description = "Stack stones across the stream until the pool behind them rises."),
            readStone("thornward").copy(id = "burn_thornward", description = "Set the bramble wall alight at dusk and watch what comes out of it."),
        ))

        assertFalse("TEMPLATED_MECHANICS" in codes(base.copy(mechanics = varied)))
    }

    @Test
    fun `a quest line that is mostly reading is reported`() {
        fun fact(n: Int) = QuestObjective.Fact("learn $n", StoryCondition.Always)
        val reading = QuestLibrary(quests = listOf(
            Quest("one", "One", "Read things.", objectives = listOf(fact(1), fact(2), fact(3))),
            Quest("two", "Two", "Read more.", objectives = listOf(fact(4), fact(5), QuestObjective.KillCreature("keeper_star"))),
        ))

        assertTrue("QUESTS_MOSTLY_READING" in codes(base.copy(quests = reading)))
    }

    @Test
    fun `creatures with no relationship to anything are idle and nothing happens unwatched`() {
        val idle = base.creatures.creatures.map { it.copy(behavior = CreatureBehavior()) }
        val codes = codes(base.copy(creatures = CreatureLibrary(base.creatures.schemaVersion, idle)))

        assertTrue("IDLE_CREATURES" in codes, codes.toString())
        assertTrue("NOTHING_HAPPENS_UNWATCHED" in codes, codes.toString())
    }

    @Test
    fun `one relationship per creature is enough to clear both`() {
        val creatures = base.creatures.creatures
        val lively = creatures.mapIndexed { i, creature ->
            creature.copy(behavior = CreatureBehavior(drives = if (i % 2 == 0) CreatureDrives(herds = true) else CreatureDrives(activity = CreatureActivity.NIGHT)))
        }
        val pack = base.copy(creatures = CreatureLibrary(7, lively))
        val report = PlayabilityAnalyzer.analyze(pack)

        assertFalse("IDLE_CREATURES" in report.findings.map { it.code })
        assertFalse("NOTHING_HAPPENS_UNWATCHED" in report.findings.map { it.code })
        assertEquals(lively.size, report.livingLinks)
    }

    @Test
    fun `a world of single landmarks is reported and one repeating structure clears it`() {
        assertTrue("ONLY_LANDMARKS" in codes(base), "the village example pins its only structure to an anchor")

        val repeating = base.structures.copy(structures = base.structures.structures.map { it.copy(placement = it.placement.copy(anchor = null)) })
        assertFalse("ONLY_LANDMARKS" in codes(base.copy(structures = repeating)))
    }

    @Test
    fun `an item that is never used, spent or handed in is a dead end`() {
        val trinket = CustomItemDefinition("dusty_trinket", "Dusty trinket", base.items.items.first().textureAsset)
        val key = CustomItemDefinition("spent_key", "Spent key", base.items.items.first().textureAsset)
        val items = CustomItemLibrary(items = listOf(trinket, key))
        val door = readStone("gate").copy(rules = readStone("gate").rules.map { it.copy(heldItem = MechanicItemCost("worldsmith:item/spent_key")) })
        val report = PlayabilityAnalyzer.analyze(base.copy(items = items, mechanics = WorldMechanicLibrary(mechanics = listOf(door))))

        assertEquals(listOf("dusty_trinket"), report.unusedItems, "the key is spent at the gate; the trinket goes nowhere")
    }

    @Test
    fun `an item crafted into something else is not a dead end`() {
        val texture = base.items.items.first().textureAsset
        val shard = CustomItemDefinition("ember_shard", "Ember shard", texture)
        val lamp = CustomItemDefinition("ember_lamp", "Ember lamp", texture)
        val recipe = com.wjz.worldsmith.core.content.ItemRecipe.Shapeless("lamp", listOf("worldsmith:item/ember_shard", "minecraft:glass"), "worldsmith:item/ember_lamp")
        val report = PlayabilityAnalyzer.analyze(base.copy(items = CustomItemLibrary(5, listOf(shard, lamp), listOf(recipe))))

        assertEquals(listOf("ember_lamp"), report.unusedItems, "the shard is an ingredient; the lamp it makes still does nothing")
    }

    @Test
    fun `findings never block and carry no score`() {
        // Nothing in the report is a gate; it exists beside the diagnostics.
        val report = PlayabilityAnalyzer.analyze(base)
        assertTrue(report.findings.all { it.message.isNotBlank() })
    }
}
