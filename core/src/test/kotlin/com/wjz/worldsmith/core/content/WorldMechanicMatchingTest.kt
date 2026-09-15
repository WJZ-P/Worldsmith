package com.wjz.worldsmith.core.content

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldMechanicMatchingTest {
    private val origin = MechanicOffset()
    private val stone = MechanicBlockPredicate("minecraft:stone")
    private val gold = MechanicBlockPredicate("minecraft:gold_block")
    private val anchor = MechanicPatternCell(origin, stone)
    private val arm = MechanicPatternCell(MechanicOffset(2, 1, -1), gold)
    private fun rule() = WorldMechanicRule(
        "activate", WorldMechanicEvent.BLOCK_PLACED, listOf(anchor, arm),
        listOf(MechanicAction.SetBlock(origin, gold)),
    )
    private fun lookup(values: Map<MechanicOffset, MechanicBlockPredicate>) = MechanicLoadedBlockLookup { values[it] }

    @Test fun horizontalRotationPreservesVerticalOffsetAndMatchesNativeClockwiseConvention() {
        val offset = MechanicOffset(2, 3, -1)
        assertEquals(offset, WorldMechanicMatching.rotateY(offset, 0))
        assertEquals(MechanicOffset(1, 3, 2), WorldMechanicMatching.rotateY(offset, 1))
        assertEquals(MechanicOffset(-2, 3, 1), WorldMechanicMatching.rotateY(offset, 2))
        assertEquals(MechanicOffset(-1, 3, -2), WorldMechanicMatching.rotateY(offset, 3))
        assertEquals(offset, WorldMechanicMatching.rotateY(offset, 4))
        assertEquals(WorldMechanicMatching.rotateY(offset, 3), WorldMechanicMatching.rotateY(offset, -1))
        assertThrows(ArithmeticException::class.java) { WorldMechanicMatching.rotateY(MechanicOffset(Int.MIN_VALUE, 0, 0), 2) }
    }

    @Test fun placingAnyPatternCellFindsTheAnchorForEveryRotation() {
        val value = rule()
        val actualAnchor = MechanicOffset(10, 70, 20)
        for (turns in 0..3) {
            for (cell in value.pattern) {
                val relative = WorldMechanicMatching.rotateY(cell.offset, turns)
                val changed = MechanicOffset(actualAnchor.x + relative.x, actualAnchor.y + relative.y, actualAnchor.z + relative.z)
                assertTrue(MechanicMatchCandidate(actualAnchor, turns) in WorldMechanicMatching.candidateAnchors(value, changed))
            }
        }
        val candidates = WorldMechanicMatching.candidateAnchors(value, actualAnchor)
        assertEquals(8, candidates.size)
        assertEquals(candidates.size, candidates.distinct().size)
        assertThrows(UnsupportedOperationException::class.java) { (candidates as MutableList).clear() }
    }

    @Test fun candidateOrderDoesNotDependOnTheAuthoredCellArrayOrder() {
        val forward = rule()
        val reverse = forward.copy(pattern = forward.pattern.reversed())
        assertEquals(WorldMechanicMatching.candidateAnchors(forward, origin), WorldMechanicMatching.candidateAnchors(reverse, origin))
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), WorldMechanicMatching.candidateAnchors(forward, origin).map { it.quarterTurns })
    }

    @Test fun useBlockOnlyTargetsTheClickedAnchorAndFixedOrientationRulesNeverRotate() {
        val clicked = MechanicOffset(-10, 32, 200)
        val use = rule().copy(event = WorldMechanicEvent.USE_BLOCK)
        assertEquals((0..3).map { MechanicMatchCandidate(clicked, it) }, WorldMechanicMatching.candidateAnchors(use, clicked))
        assertEquals(listOf(MechanicMatchCandidate(clicked, 0)), WorldMechanicMatching.candidateAnchors(use.copy(rotateY = false), clicked))
        assertEquals(2, WorldMechanicMatching.candidateAnchors(rule().copy(rotateY = false), clicked).size)
    }

    @Test fun matchingReadsTheCompleteRotatedPatternAndUsesPartialNativeProperties() {
        val stairs = MechanicBlockPredicate("minecraft:oak_stairs", mapOf("facing" to "north"))
        val value = rule().copy(pattern = listOf(anchor.copy(block = stairs), arm))
        val actualAnchor = MechanicOffset(10, 70, 20)
        val world = lookup(mapOf(
            actualAnchor to stairs.copy(properties = mapOf("facing" to "east", "half" to "bottom")),
            MechanicOffset(11, 71, 22) to gold,
        ))
        val matcher = MechanicPredicateMatcher { expected, actual, turns ->
            val facing = listOf("north", "east", "south", "west")
            expected.block == actual.block && expected.properties.all { (key, text) ->
                val rotated = if (key == "facing" && text in facing) facing[(facing.indexOf(text) + turns) % 4] else text
                actual.properties[key] == rotated
            }
        }
        val budget = MechanicMatchBudget()
        assertTrue(WorldMechanicMatching.matches(value, MechanicMatchCandidate(actualAnchor, 1), world, budget, matcher))
        assertEquals(2, budget.blockReads)
        assertEquals(1, budget.candidatesChecked)
        assertFalse(budget.exhausted)
        assertFalse(WorldMechanicMatching.matches(value, MechanicMatchCandidate(actualAnchor, 0), world, predicateMatcher = matcher))
    }

    @Test fun defaultMatcherSupportsUnrotatedPartialPropertiesAndFailsClosedWithoutNativeRotation() {
        val expected = MechanicBlockPredicate("minecraft:oak_stairs", mapOf("facing" to "north"))
        val value = rule().copy(pattern = listOf(anchor.copy(block = expected)))
        val world = lookup(mapOf(origin to expected.copy(properties = mapOf("facing" to "north", "half" to "bottom"))))
        assertTrue(WorldMechanicMatching.matches(value, MechanicMatchCandidate(origin, 0), world))
        assertFalse(WorldMechanicMatching.matches(value, MechanicMatchCandidate(origin, 1), world), "Native property rotation must be explicit, not guessed")
        assertTrue(WorldMechanicMatching.matches(rule().copy(pattern = listOf(anchor)), MechanicMatchCandidate(origin, 3), lookup(mapOf(origin to stone))))
        assertFalse(WorldMechanicMatching.matches(value, MechanicMatchCandidate(origin, 0), lookup(mapOf(origin to expected.copy(properties = emptyMap())))))
    }

    @Test fun unloadedCellsAndMismatchesFailImmediatelyWithoutLookingAtRemainingCells() {
        var reads = 0
        val unloaded = MechanicLoadedBlockLookup { reads++; null }
        assertFalse(WorldMechanicMatching.matches(rule(), MechanicMatchCandidate(origin, 0), unloaded))
        assertEquals(1, reads)
        val mismatch = MechanicLoadedBlockLookup { reads++; gold }
        assertFalse(WorldMechanicMatching.matches(rule(), MechanicMatchCandidate(origin, 0), mismatch))
        assertEquals(2, reads)
    }

    @Test fun sharedCandidateBudgetBoundsAllAttemptsWithinAnEvent() {
        val budget = MechanicMatchBudget(maxCandidates = 1)
        val world = lookup(mapOf(origin to stone))
        val value = rule().copy(pattern = listOf(anchor))
        assertTrue(WorldMechanicMatching.matches(value, MechanicMatchCandidate(origin, 0), world, budget))
        assertFalse(WorldMechanicMatching.matches(value, MechanicMatchCandidate(origin, 1), world, budget))
        assertEquals(1, budget.candidatesChecked)
        assertEquals(1, budget.blockReads)
        assertTrue(budget.exhausted)
    }

    @Test fun readBudgetIsChargedBeforeWorldAccessAndRemainsExhaustedAcrossCandidates() {
        val budget = MechanicMatchBudget(maxBlockReads = 1)
        var reads = 0
        val world = MechanicLoadedBlockLookup { position -> reads++; if (position == origin) stone else gold }
        assertFalse(WorldMechanicMatching.matches(rule(), MechanicMatchCandidate(origin, 0), world, budget))
        assertEquals(1, reads)
        assertEquals(1, budget.blockReads)
        assertTrue(budget.exhausted)
        assertFalse(WorldMechanicMatching.matches(rule(), MechanicMatchCandidate(origin, 1), world, budget))
        assertEquals(1, reads)
        assertEquals(1, budget.candidatesChecked)
    }

    @Test fun budgetHardLimitsAndInvalidOrientationsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { MechanicMatchBudget(maxCandidates = 513) }
        assertThrows(IllegalArgumentException::class.java) { MechanicMatchBudget(maxBlockReads = 8193) }
        assertThrows(IllegalArgumentException::class.java) { MechanicMatchBudget(maxBlockReads = 0) }
        val world = MechanicLoadedBlockLookup { fail<MechanicBlockPredicate>("Invalid orientation must not read the world") }
        assertFalse(WorldMechanicMatching.matches(rule(), MechanicMatchCandidate(origin, 4), world))
        assertFalse(WorldMechanicMatching.matches(rule().copy(rotateY = false), MechanicMatchCandidate(origin, 1), world))
        assertThrows(IllegalArgumentException::class.java) {
            WorldMechanicMatching.matches(rule().copy(pattern = List(129) { anchor }), MechanicMatchCandidate(origin, 0), world)
        }
    }

    @Test fun integerCoordinateOverflowSkipsCandidatesAndFailsMatchingRatherThanWrapping() {
        val value = rule().copy(rotateY = false, pattern = listOf(anchor, anchor.copy(offset = MechanicOffset(x = -1))))
        val edge = MechanicOffset(Int.MAX_VALUE, 64, 0)
        assertEquals(listOf(MechanicMatchCandidate(edge, 0)), WorldMechanicMatching.candidateAnchors(value, edge))
        val beyond = rule().copy(rotateY = false, pattern = listOf(anchor.copy(offset = MechanicOffset(x = 1))))
        var reads = 0
        val world = MechanicLoadedBlockLookup { reads++; stone }
        assertFalse(WorldMechanicMatching.matches(beyond, MechanicMatchCandidate(edge, 0), world))
        assertEquals(0, reads)
    }

    @Test fun maximumSingleRuleCandidateEnumerationIsBounded() {
        val cells = (0 until 128).map { MechanicPatternCell(MechanicOffset(-8 + it % 17, -8 + it / 17, 0), stone) }
        assertEquals(512, WorldMechanicMatching.candidateAnchors(rule().copy(pattern = cells), origin).size)
    }

    @Test fun ruleOrderIsCanonicalFilteredByEventAndDeeplySnapshotted() {
        val rules = mutableListOf(rule().copy(id = "z"), rule().copy(id = "a"), rule().copy(id = "u", event = WorldMechanicEvent.USE_BLOCK))
        val mechanics = mutableListOf(
            WorldMechanicDefinition("z", "Z", rules = rules),
            WorldMechanicDefinition("a", "A", rules = listOf(rule().copy(id = "b"))),
        )
        val refs = WorldMechanicMatching.orderedRules(WorldMechanicLibrary(mechanics = mechanics), WorldMechanicEvent.BLOCK_PLACED)
        assertEquals(listOf("a/b", "z/a", "z/z"), refs.map { "${it.mechanic.id}/${it.rule.id}" })
        rules.clear(); mechanics.clear()
        assertEquals(3, refs.size)
        assertEquals(3, refs.last().mechanic.rules.size)
        assertThrows(UnsupportedOperationException::class.java) { (refs as MutableList).clear() }
    }
}
