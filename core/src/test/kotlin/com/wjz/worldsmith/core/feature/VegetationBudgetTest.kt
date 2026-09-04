package com.wjz.worldsmith.core.feature

import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.TreeCrownShape
import com.wjz.worldsmith.core.model.TreeCrownSpec
import com.wjz.worldsmith.core.model.TreeBranchSpec
import com.wjz.worldsmith.core.model.TreeDistribution
import com.wjz.worldsmith.core.model.TreeHeight
import com.wjz.worldsmith.core.model.TreeSpec
import com.wjz.worldsmith.core.model.TreeSubstrate
import com.wjz.worldsmith.core.model.TreeTrunkShape
import com.wjz.worldsmith.core.model.TreeTrunkSpec
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
            val cost = VegetationBudget.attemptsPerChunk(feature(recipe), 0.5)
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
            VegetationBudget.attemptsPerChunk(feature(FeatureRecipe.ORE_VEIN), 0.5),
        )
        assertTrue(VegetationBudget.veinCount(1.0) > VegetationBudget.veinCount(0.1))
    }

    @Test
    fun `zero density means no placement for every count family`() {
        assertEquals(0, VegetationBudget.patchCount(0.0))
        assertEquals(0, VegetationBudget.veinCount(0.0))
        TreeDistribution.entries.forEach { distribution ->
            assertEquals(0, VegetationBudget.treeMaximumCount(distribution, 0.0), distribution.name)
        }
    }

    @Test
    fun `forest modes grow from one scattered attempt into noisy dense stands`() {
        assertEquals(1, VegetationBudget.treeMaximumCount(TreeDistribution.SCATTERED, 0.7))
        assertTrue(
            VegetationBudget.treeMaximumCount(TreeDistribution.GROVE, 0.7) >
                VegetationBudget.treeMaximumCount(TreeDistribution.SCATTERED, 0.7),
        )
        assertTrue(VegetationBudget.treeBelowNoiseCount(TreeDistribution.FOREST, 0.7) > 0)
        assertTrue(
            VegetationBudget.treeMaximumCount(TreeDistribution.DENSE_FOREST, 0.7) >
                VegetationBudget.treeMaximumCount(TreeDistribution.FOREST, 0.7),
        )
    }

    @Test
    fun `many large branch crowns cost more than one ordinary tree`() {
        val ordinary = TreeSpec(
            TreeTrunkSpec(TreeTrunkShape.STRAIGHT, TreeHeight(8, 10)),
            TreeCrownSpec(TreeCrownShape.ROUND, radius = 3, height = 4),
            TreeDistribution.FOREST,
            TreeSubstrate.NATURAL_SOIL,
        )
        val elaborate = TreeSpec(
            TreeTrunkSpec(
                TreeTrunkShape.BRANCHING,
                TreeHeight(18, 22),
                thickness = 2,
                branches = TreeBranchSpec(7, 7, 0.6),
            ),
            TreeCrownSpec(TreeCrownShape.CLUSTERED, radius = 7, height = 10, density = 0.95),
            TreeDistribution.FOREST,
            TreeSubstrate.NATURAL_SOIL,
        )

        assertTrue(VegetationBudget.treeWorkPerTree(elaborate) > VegetationBudget.treeWorkPerTree(ordinary) * 20)
        val expensive = FeatureDefinition("expensive", FeatureRecipe.TREE, density = 1.0, tree = elaborate)
        assertTrue(
            VegetationBudget.attemptsPerChunk(expensive, 1.0) > VegetationBudget.MAX_ATTEMPTS_PER_CHUNK,
            "a dense forest of maximum branch crowns should cross the chunk budget",
        )
    }

    @Test
    fun `multiple stems and root flare are charged rather than cosmetic`() {
        val crown = TreeCrownSpec(TreeCrownShape.COLUMNAR, radius = 5, height = 9, density = 0.9)
        val straight = TreeSpec(
            TreeTrunkSpec(TreeTrunkShape.STRAIGHT, TreeHeight(14, 16), thickness = 2),
            crown,
            TreeDistribution.FOREST,
            TreeSubstrate.NATURAL_SOIL,
        )
        val flared = straight.copy(trunk = straight.trunk.copy(flare = 2))
        val multiStem = straight.copy(
            trunk = straight.trunk.copy(shape = TreeTrunkShape.MULTI_STEM, thickness = 1, stems = 4),
        )

        assertTrue(VegetationBudget.treeWorkPerTree(flared) > VegetationBudget.treeWorkPerTree(straight))
        assertTrue(VegetationBudget.treeWorkPerTree(multiStem) > VegetationBudget.treeWorkPerTree(straight))
    }

    private fun feature(recipe: FeatureRecipe) = FeatureDefinition(
        id = "sample",
        recipe = recipe,
        density = 0.5,
        tree = if (recipe.isTree) {
            TreeSpec(
                TreeTrunkSpec(TreeTrunkShape.STRAIGHT, TreeHeight(6, 8)),
                TreeCrownSpec(TreeCrownShape.ROUND, radius = 3, height = 4),
                TreeDistribution.SCATTERED,
                TreeSubstrate.NATURAL_SOIL,
            )
        } else {
            null
        },
    )
}
