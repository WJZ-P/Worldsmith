package com.wjz.worldsmith.core.feature

import com.wjz.worldsmith.core.model.FeatureRecipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The budget is the one place that knows what a density costs, and both the
 * validator and the Minecraft-facing compiler read it. A recipe missing from it
 * would either fail to compile here or be spent for free by the validator,
 * which is how a pack gets past the cap and makes generation crawl.
 */
class VegetationBudgetTest {
    @Test
    fun `every recipe has a cost`() {
        FeatureRecipe.entries.forEach { recipe ->
            val cost = VegetationBudget.attemptsPerChunk(recipe, 0.5)
            assertTrue(cost > 0.0, "$recipe costs nothing, so a biome could take unlimited copies of it")
        }
    }

    @Test
    fun `underground recipes are counted per chunk rather than as rarity`() {
        // An ore vein is many small attempts, not one rare landmark; costing it
        // like a boulder would let a pack ask for sixteen veins and be charged
        // for a thirty-second of one.
        assertEquals(
            VegetationBudget.veinCount(0.5).toDouble(),
            VegetationBudget.attemptsPerChunk(FeatureRecipe.ORE_VEIN, 0.5),
        )
        assertTrue(VegetationBudget.veinCount(1.0) > VegetationBudget.veinCount(0.1))
        assertEquals(1, VegetationBudget.veinCount(0.0), "a density of zero still has to place something or nothing")
    }
}
