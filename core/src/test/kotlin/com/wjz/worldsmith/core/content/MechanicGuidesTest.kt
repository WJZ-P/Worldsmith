package com.wjz.worldsmith.core.content

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MechanicGuidesTest {
    private fun definition(): WorldMechanicDefinition {
        val stone = MechanicBlockPredicate("minecraft:stone")
        val gold = MechanicBlockPredicate("minecraft:gold_block")
        return WorldMechanicDefinition("altar", "暮石祭台", rules = listOf(WorldMechanicRule(
            "assemble", WorldMechanicEvent.BLOCK_PLACED,
            listOf(MechanicPatternCell(MechanicOffset(), gold),
                MechanicPatternCell(MechanicOffset(-1, 1, 0), stone, true),
                MechanicPatternCell(MechanicOffset(1, 1, 0), stone, true),
                MechanicPatternCell(MechanicOffset(0, 1, 1), MechanicBlockPredicate("minecraft:air"))),
            listOf(MechanicAction.SpawnCreature("guardian", MechanicOffset(0, 1, 1))))),
            description = "残页记着守卫的唤醒之法。")
    }

    @Test fun `guide preserves actual effects and builds shared coordinate frame from the rule`() {
        val source = definition()
        val guide = MechanicGuides.describe(source)
        assertEquals(source.id, guide.mechanicId); assertEquals(source.displayName, guide.title)
        assertEquals(source.description, guide.description)
        val rule = guide.rules.single()
        assertEquals(source.rules.single(), rule.rule)
        assertEquals(listOf(0, 1), rule.layers.map { it.y })
        assertEquals(listOf(-1, 1, 0, 1), listOf(rule.minX, rule.maxX, rule.minZ, rule.maxZ))
        assertEquals(1, rule.palette.first { it.block.block == "minecraft:gold_block" }.count)
        assertEquals(2, rule.palette.first { it.block.block == "minecraft:stone" }.consumeCount)
    }

    @Test fun `required air and omitted cells are distinct without inventing hidden constraints`() {
        val rule = MechanicGuides.describe(definition()).rules.single()
        assertEquals(".", MechanicGuides.symbolAt(rule, 0, 1, 1))
        assertNull(MechanicGuides.symbolAt(rule, -1, 0, 1))
        assertNotNull(MechanicGuides.symbolAt(rule, 0, 0, 0))
        assertTrue(rule.palette.none { it.block.block == "minecraft:air" })
    }

    @Test fun `partial native properties stay distinct while costs remain separate from building palette`() {
        val north = MechanicBlockPredicate("minecraft:oak_stairs", mapOf("facing" to "north"))
        val south = MechanicBlockPredicate("minecraft:oak_stairs", mapOf("facing" to "south"))
        val source = definition().copy(rules = listOf(WorldMechanicRule("offer", WorldMechanicEvent.USE_BLOCK,
            listOf(MechanicPatternCell(MechanicOffset(), north), MechanicPatternCell(MechanicOffset(1), south)),
            listOf(MechanicAction.GiveItem("minecraft:diamond")), heldItem = MechanicItemCost("minecraft:emerald", 3))))
        val guide = MechanicGuides.describe(source).rules.single()
        assertEquals(2, guide.palette.size); assertEquals(3, guide.rule.heldItem!!.count)
        assertTrue(guide.palette.none { it.block.block == "minecraft:emerald" })
        assertNotEquals(MechanicGuides.symbolAt(guide, 0, 0, 0), MechanicGuides.symbolAt(guide, 1, 0, 0))
    }

    @Test fun `describing is detached deeply immutable and does not alter source order`() {
        val properties = linkedMapOf("facing" to "north")
        val cells = mutableListOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:oak_stairs", properties)))
        val sourceRule = WorldMechanicRule("use", WorldMechanicEvent.USE_BLOCK, cells,
            listOf(MechanicAction.SetBlock(MechanicOffset(), MechanicBlockPredicate("minecraft:air"))))
        val source = definition().copy(rules = listOf(sourceRule))
        val result = MechanicGuides.describe(source)
        properties["facing"] = "south"; cells.clear()
        assertEquals("north", result.rules.single().palette.single().block.properties["facing"])
        assertThrows(UnsupportedOperationException::class.java) { (result.rules as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.rules.single().layers.single().cells as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.rules.single().palette.single().block.properties as MutableMap).clear() }
    }

    @Test fun `rule changes immediately change guide quantities outcomes and required air`() {
        val source = definition()
        val first = MechanicGuides.describe(source)
        val changedRule = source.rules.single().copy(cooldownTicks = 60, pattern = source.rules.single().pattern +
            MechanicPatternCell(MechanicOffset(0, 2, 0), MechanicBlockPredicate("minecraft:stone"), true))
        val next = MechanicGuides.describe(source.copy(rules = listOf(changedRule)))
        assertEquals(20, first.rules.single().rule.cooldownTicks)
        assertEquals(60, next.rules.single().rule.cooldownTicks)
        assertEquals(3, next.rules.single().palette.first { it.block.block == "minecraft:stone" }.count)
        assertEquals(listOf(0, 1, 2), next.rules.single().layers.map { it.y })
    }

    @Test fun `invalid or oversized sources are not silently converted into plausible instructions`() {
        val source = definition()
        assertThrows(IllegalArgumentException::class.java) { MechanicGuides.describe(source.copy(rules = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { MechanicGuides.describe(source.copy(rules = listOf(source.rules.single().copy(
            pattern = source.rules.single().pattern + MechanicPatternCell(MechanicOffset(9), MechanicBlockPredicate("minecraft:stone")))))) }
    }
}
