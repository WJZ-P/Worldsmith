package com.wjz.worldsmith.core.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldMechanicValidationTest {
    private val origin = MechanicOffset()
    private val stone = MechanicBlockPredicate("minecraft:stone")
    private val anchor = MechanicPatternCell(origin, stone)
    private val setBlock = MechanicAction.SetBlock(origin, MechanicBlockPredicate("minecraft:gold_block"))
    private fun rule(id: String = "activate") = WorldMechanicRule(id, WorldMechanicEvent.USE_BLOCK, listOf(anchor), listOf(setBlock))
    private fun mechanic(rules: List<WorldMechanicRule> = listOf(rule()), id: String = "altar") = WorldMechanicDefinition(id, "Altar", rules = rules)
    private fun library(vararg rules: WorldMechanicRule) = WorldMechanicLibrary(mechanics = listOf(mechanic(rules.toList())))
    private fun codes(library: WorldMechanicLibrary) = WorldMechanicValidation.validate(library).map { it.code }.toSet()

    @Test fun emptyLibraryAndOneShotNativeDeviceAreValid() {
        assertTrue(WorldMechanicValidation.validate(WorldMechanicLibrary()).isEmpty())
        assertTrue(WorldMechanicValidation.validate(library(rule())).isEmpty())
        assertEquals(setOf("mechanics.schema"), codes(WorldMechanicLibrary(schemaVersion = 2)))
    }

    @Test fun serializationUsesTheClosedActionKindsAndExactContractFields() {
        val value = library(rule().copy(
            heldItem = MechanicItemCost("worldsmith:item/offering", 2),
            actions = listOf(setBlock, MechanicAction.SpawnCreature("guardian", MechanicOffset(y = 1)), MechanicAction.GiveItem("minecraft:diamond")),
        ))
        val json = Json { encodeDefaults = true }
        val document = json.encodeToString(value)
        assertTrue(document.contains("\"type\":\"set_block\""))
        assertTrue(document.contains("\"type\":\"spawn_creature\""))
        assertTrue(document.contains("\"type\":\"give_item\""))
        assertTrue(document.contains("\"event\":\"USE_BLOCK\""))
        assertEquals(value, json.decodeFromString<WorldMechanicLibrary>(document))
        assertTrue(WorldMechanicValidation.validate(value).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { json.decodeFromString<WorldMechanicLibrary>(document.replace("set_block", "run_command")) }
    }

    @Test fun idsStatesAndRuleIdsAreNormalizedAndUniqueWithinTheirScope() {
        for (id in listOf("", "A", "path/id", "../x", "a..b", "a.", "x".repeat(65))) assertFalse(WorldMechanicValidation.validId(id), id)
        assertTrue(WorldMechanicValidation.validId("altar-1_ritual"))
        val bad = WorldMechanicLibrary(mechanics = listOf(
            mechanic(listOf(rule(), rule().copy(fromState = "missing"))).copy(states = listOf("idle", "idle"), initialState = "missing"),
            mechanic(),
        ))
        assertTrue(codes(bad).containsAll(setOf("mechanics.duplicate", "mechanics.duplicate_rule", "mechanics.duplicate_state", "mechanics.initial_state", "mechanics.state_reference")))
        val independent = WorldMechanicLibrary(mechanics = listOf(mechanic(id = "a"), mechanic(id = "b")))
        assertTrue(WorldMechanicValidation.validate(independent).isEmpty(), "Rules are local to their mechanic, not one global id namespace")
    }

    @Test fun everyRuleIndependentlyNeedsItsOwnNonAirAnchor() {
        val away = rule("away").copy(pattern = listOf(anchor.copy(offset = MechanicOffset(x = 1))), actions = listOf(MechanicAction.GiveItem("minecraft:diamond")))
        assertTrue("mechanics.anchor" in codes(library(rule(), away)))
        for (air in listOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")) {
            val value = rule().copy(pattern = listOf(anchor.copy(block = MechanicBlockPredicate(air))))
            assertTrue("mechanics.anchor" in codes(library(value)), air)
        }
    }

    @Test fun patternPositionsAreUniqueAndOffsetsAreBounded() {
        assertTrue("mechanics.duplicate_cell" in codes(library(rule().copy(pattern = listOf(anchor, anchor)))))
        for (offset in listOf(MechanicOffset(x = 9), MechanicOffset(y = -9), MechanicOffset(z = Int.MAX_VALUE))) {
            assertTrue("mechanics.offset" in codes(library(rule().copy(pattern = listOf(anchor, anchor.copy(offset = offset))))))
        }
        assertTrue(WorldMechanicValidation.validate(library(rule().copy(pattern = listOf(anchor, anchor.copy(offset = MechanicOffset(-8, 8, -8)))))).isEmpty())
    }

    @Test fun blockAndItemReferencesRejectRawHostsTagsInlineStatesAndTraversal() {
        for (value in listOf("minecraft:stone[facing=north]", "#minecraft:logs", "minecraft:../stone", "worldsmith:content/block/stone/00", "worldsmith:content/item/basic/00", "worldsmith:item/thing")) {
            assertFalse(WorldMechanicValidation.validBlockReference(value), value)
        }
        for (value in listOf("minecraft:air", "minecraft:cave_air", "worldsmith:content/block/stone/00", "worldsmith:content/item/basic/00", "worldsmith:item/a/b", "worldsmith:content/a/b")) {
            assertFalse(WorldMechanicValidation.validItemReference(value), value)
        }
        for (value in listOf("minecraft:stone", "worldsmith:content/altar", "another_mod:some/block")) assertTrue(WorldMechanicValidation.validBlockReference(value), value)
        for (value in listOf("minecraft:diamond", "worldsmith:content/altar", "worldsmith:item/offering")) assertTrue(WorldMechanicValidation.validItemReference(value), value)
    }

    @Test fun nativePartialPropertiesAreBoundedAndCustomPropertiesAreEmpty() {
        val stairs = MechanicBlockPredicate("minecraft:oak_stairs", mapOf("facing" to "north", "half" to "bottom"))
        assertTrue(WorldMechanicValidation.validate(library(rule().copy(pattern = listOf(anchor.copy(block = stairs))))).isEmpty())
        val custom = stairs.copy(block = "worldsmith:content/altar")
        val value = rule().copy(pattern = listOf(anchor.copy(block = custom)), actions = listOf(MechanicAction.SetBlock(origin, custom)))
        assertEquals(2, WorldMechanicValidation.validate(library(value)).count { it.code == "mechanics.custom_block_properties" })
        val invalid = stairs.copy(properties = mapOf("Facing" to "north", "facing" to "north[bad]"))
        assertTrue("mechanics.property" in codes(library(rule().copy(pattern = listOf(anchor.copy(block = invalid))))))
        val excessive = stairs.copy(properties = (0..16).associate { "p$it" to "true" })
        assertTrue("mechanics.property_count" in codes(library(rule().copy(pattern = listOf(anchor.copy(block = excessive))))))
    }

    @Test fun explicitHandOfferingsAreUseOnlyAndAllItemStacksAreBounded() {
        val offer = rule().copy(heldItem = MechanicItemCost("minecraft:diamond"))
        assertTrue(WorldMechanicValidation.validate(library(offer)).isEmpty())
        assertTrue("mechanics.held_event" in codes(library(offer.copy(event = WorldMechanicEvent.BLOCK_PLACED))))
        for (count in listOf(0, -1, 65, Int.MAX_VALUE)) {
            val value = offer.copy(heldItem = MechanicItemCost("minecraft:diamond", count), actions = listOf(MechanicAction.GiveItem("minecraft:diamond", count)))
            assertEquals(2, WorldMechanicValidation.validate(library(value)).count { it.code == "mechanics.item_count" })
        }
        assertTrue("mechanics.item_reference" in codes(library(offer.copy(heldItem = MechanicItemCost("minecraft:air")))))
    }

    @Test fun writesRequireDeclaredNonConsumedTargetsAndOneWritePerPosition() {
        val consumed = rule().copy(pattern = listOf(anchor.copy(consume = true)))
        assertTrue("mechanics.consume_write" in codes(library(consumed)))
        val outside = rule().copy(actions = listOf(MechanicAction.SetBlock(MechanicOffset(x = 1), stone)))
        assertTrue("mechanics.write_outside_pattern" in codes(library(outside)))
        assertTrue("mechanics.duplicate_write" in codes(library(rule().copy(actions = listOf(setBlock, setBlock)))))
    }

    @Test fun freeSameStateRewardsAndFakeAirCostsAreRejected() {
        val reward = rule().copy(toState = "idle", actions = listOf(MechanicAction.GiveItem("minecraft:diamond")))
        assertTrue("mechanics.free_reward_cycle" in codes(library(reward)))
        val airCost = MechanicPatternCell(MechanicOffset(y = 1), MechanicBlockPredicate("minecraft:air"), true)
        assertTrue(codes(library(reward.copy(pattern = listOf(anchor, airCost)))).containsAll(setOf("mechanics.consume_air", "mechanics.free_reward_cycle")))
        assertTrue(WorldMechanicValidation.validate(library(reward.copy(heldItem = MechanicItemCost("minecraft:emerald")))).isEmpty())
        assertTrue(WorldMechanicValidation.validate(library(reward.copy(pattern = listOf(anchor.copy(consume = true))))).isEmpty())
        assertTrue(WorldMechanicValidation.validate(library(rule().copy(toState = "idle"))).isEmpty(), "No reward means no free reward cycle")
    }

    @Test fun freeMultiStateRewardLoopsAlsoNeedAnActualCostOnTheCycle() {
        val reward = rule().copy(actions = listOf(MechanicAction.GiveItem("minecraft:diamond")))
        val reset = rule("reset").copy(fromState = "active", toState = "idle")
        assertTrue("mechanics.free_reward_cycle" in codes(library(reward, reset)))
        assertFalse("mechanics.free_reward_cycle" in codes(library(reward, reset.copy(heldItem = MechanicItemCost("minecraft:emerald")))))
    }

    @Test fun summonMustAdvanceStateAndMayOccurOnlyOncePerRule() {
        val spawn = MechanicAction.SpawnCreature("guardian", MechanicOffset(y = 1))
        assertTrue("mechanics.spawn_count" in codes(library(rule().copy(actions = listOf(spawn, spawn)))))
        val sameState = rule().copy(toState = "idle", heldItem = MechanicItemCost("minecraft:diamond"), actions = listOf(spawn))
        assertTrue("mechanics.spawn_state" in codes(library(sameState)))
        assertTrue(WorldMechanicValidation.validate(library(rule().copy(actions = listOf(spawn)))).isEmpty())
    }

    @Test fun reachableSummonCyclesAreRejectedEvenWhenAResetHasAnOffering() {
        val spawn = rule().copy(actions = listOf(MechanicAction.SpawnCreature("guardian", MechanicOffset(y = 1))))
        val reset = rule("reset").copy(fromState = "active", toState = "idle", heldItem = MechanicItemCost("minecraft:diamond"))
        assertTrue("mechanics.spawn_cycle" in codes(library(spawn, reset)))
        val terminal = reset.copy(toState = "done")
        val ending = WorldMechanicLibrary(mechanics = listOf(mechanic(listOf(spawn, terminal)).copy(states = listOf("idle", "active", "done"))))
        assertTrue(WorldMechanicValidation.validate(ending).isEmpty())
    }

    @Test fun unreachableSummonCycleIsNotMistakenForAnInitialStateReachableOne() {
        val dormant = rule().copy(fromState = "a", toState = "b", actions = listOf(MechanicAction.SpawnCreature("guardian", origin)))
        val reset = rule("reset").copy(fromState = "b", toState = "a", heldItem = MechanicItemCost("minecraft:diamond"))
        val value = WorldMechanicLibrary(mechanics = listOf(mechanic(listOf(dormant, reset)).copy(states = listOf("idle", "a", "b"))))
        assertFalse("mechanics.spawn_cycle" in codes(value))
    }

    @Test fun namesDescriptionsCooldownsAndBiomeFiltersAreBounded() {
        val value = WorldMechanicLibrary(mechanics = listOf(mechanic(listOf(rule().copy(cooldownTicks = 0, biomes = listOf("ashlands", "ashlands", "../bad")))).copy(
            displayName = "a".repeat(129), description = "a".repeat(2049),
        )))
        assertTrue(codes(value).containsAll(setOf("mechanics.name", "mechanics.description", "mechanics.cooldown", "mechanics.duplicate_biome", "mechanics.biome_reference")))
        assertTrue(WorldMechanicValidation.validate(library(rule().copy(cooldownTicks = 72000))).isEmpty())
        assertTrue("mechanics.cooldown" in codes(library(rule().copy(cooldownTicks = 72001))))
    }

    @Test fun perObjectAndWholeLibraryBudgetsRejectOversizedInputs() {
        assertTrue("mechanics.capacity" in codes(WorldMechanicLibrary(mechanics = (0..64).map { mechanic(id = "m$it") })))
        assertTrue("mechanics.rule_count" in codes(library(*(0..16).map { rule("r$it") }.toTypedArray())))
        assertTrue("mechanics.cell_count" in codes(library(rule().copy(pattern = List(129) { anchor }))))
        assertTrue("mechanics.action_count" in codes(library(rule().copy(actions = List(17) { setBlock }))))
        val tooManyRules = WorldMechanicLibrary(mechanics = (0..16).map { index -> mechanic((0..15).map { rule("r$it") }, "m$index") })
        assertTrue("mechanics.total_rules" in codes(tooManyRules))
        val cells = listOf(anchor) + (0..126).map { index -> anchor.copy(offset = MechanicOffset(-8 + index % 17, 1 + index / 17, 0)) }
        val manyCells = WorldMechanicLibrary(mechanics = (0..2).map { index -> mechanic((0..10).map { rule("r$it").copy(pattern = cells) }, "m$index") })
        assertTrue("mechanics.total_cells" in codes(manyCells))
    }

    @Test fun freezeSnapshotsAndProtectsEveryNestedCollectionAgainstJavaMutation() {
        val patternProperties = linkedMapOf("facing" to "north")
        val actionProperties = linkedMapOf("facing" to "south")
        val cells = mutableListOf(anchor.copy(block = MechanicBlockPredicate("minecraft:oak_stairs", patternProperties)))
        val actions = mutableListOf<MechanicAction>(MechanicAction.SetBlock(origin, MechanicBlockPredicate("minecraft:oak_stairs", actionProperties)))
        val biomes = mutableListOf("ashlands")
        val rules = mutableListOf(rule().copy(pattern = cells, actions = actions, biomes = biomes))
        val states = mutableListOf("idle", "active")
        val mechanics = mutableListOf(mechanic(rules).copy(states = states))
        val frozen = WorldMechanicValidation.freeze(WorldMechanicLibrary(mechanics = mechanics))
        val frozenMechanic = frozen.mechanics.single()
        val frozenRule = frozenMechanic.rules.single()
        patternProperties["facing"] = "east"; actionProperties.clear(); cells.clear(); actions.clear(); biomes.clear(); rules.clear(); states.clear(); mechanics.clear()
        assertEquals("north", frozenRule.pattern.single().block.properties["facing"])
        assertEquals("south", (frozenRule.actions.single() as MechanicAction.SetBlock).block.properties["facing"])
        assertEquals(listOf("ashlands"), frozenRule.biomes)
        assertEquals(listOf("idle", "active"), frozenMechanic.states)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.mechanics as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenMechanic.rules as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenMechanic.states as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenRule.pattern as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenRule.actions as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenRule.biomes as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozenRule.pattern.single().block.properties as MutableMap)["facing"] = "west" }
        assertThrows(UnsupportedOperationException::class.java) { ((frozenRule.actions.single() as MechanicAction.SetBlock).block.properties as MutableMap).clear() }
    }
}
