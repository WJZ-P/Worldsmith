package com.wjz.worldsmith.core.feature

import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.TreeDistribution
import com.wjz.worldsmith.core.model.TreeSpec
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The density-to-placement mapping, owned by core so the validator and the
 * Minecraft-facing compiler cannot disagree.
 *
 * <p>Ground cover becomes a per-chunk attempt count; sparse props become a
 * rarity filter. [attemptsPerChunk] converts both back to a comparable number so
 * a biome's total load can be capped. Without that cap a pack can quietly make
 * world generation crawl, and the symptom (a slow world) looks nothing like the
 * cause (one over-eager density value).
 */
object VegetationBudget {
    const val MAX_PATCH_COUNT: Int = 24
    const val MAX_RARITY: Int = 32
    const val MAX_VEIN_COUNT: Int = 16

    /** Feature attempts a single biome may spend in one chunk. */
    const val MAX_ATTEMPTS_PER_CHUNK: Double = 64.0

    @JvmStatic
    fun patchCount(density: Double): Int = if (density <= 0.0) 0 else max(1, (density * MAX_PATCH_COUNT).roundToInt())

    @JvmStatic
    fun rarity(density: Double): Int = max(1, ((1.0 - density) * MAX_RARITY).roundToInt())

    /** Ore veins are cheap per attempt but many, so they get their own scale. */
    @JvmStatic
    fun veinCount(density: Double): Int = if (density <= 0.0) 0 else max(1, (density * MAX_VEIN_COUNT).roundToInt())

    @JvmStatic
    fun attemptsPerChunk(feature: FeatureDefinition, density: Double): Double = when (feature.recipe) {
        FeatureRecipe.GROUND_PATCH, FeatureRecipe.SURFACE_LAYER, FeatureRecipe.AQUATIC_PATCH ->
            patchCount(density).toDouble()
        FeatureRecipe.ORE_VEIN, FeatureRecipe.CAVE_PATCH, FeatureRecipe.HANGING_PATCH ->
            veinCount(density).toDouble()
        // Listed rather than defaulted: a new recipe should not silently inherit
        // a cost, because being charged wrongly is how a pack slips past the cap.
        FeatureRecipe.DEAD_TREE, FeatureRecipe.BOULDER, FeatureRecipe.FALLEN_LOG ->
            if (density <= 0.0) 0.0 else 1.0 / rarity(density)
        FeatureRecipe.TREE -> treeMaximumCount(
            feature.tree?.distribution ?: TreeDistribution.SCATTERED,
            density,
        ) * (feature.tree?.let(::treeWorkPerTree) ?: 1.0)
    }

    /** Noise boundary shared by the compiler and the budget documentation. */
    @JvmStatic
    fun treeNoiseThreshold(distribution: TreeDistribution): Double = when (distribution) {
        TreeDistribution.SCATTERED -> 0.0
        TreeDistribution.GROVE -> 0.15
        TreeDistribution.FOREST -> -0.20
        TreeDistribution.DENSE_FOREST -> -0.35
    }

    /** Count in the quieter side of the forest noise. */
    @JvmStatic
    fun treeBelowNoiseCount(distribution: TreeDistribution, density: Double): Int = when (distribution) {
        TreeDistribution.SCATTERED, TreeDistribution.GROVE -> 0
        TreeDistribution.FOREST -> scaledCount(density, 4)
        TreeDistribution.DENSE_FOREST -> scaledCount(density, 8)
    }

    /** Count in the wooded side of the forest noise. */
    @JvmStatic
    fun treeAboveNoiseCount(distribution: TreeDistribution, density: Double): Int = when (distribution) {
        TreeDistribution.SCATTERED -> if (density <= 0.0) 0 else 1
        TreeDistribution.GROVE -> scaledCount(density, 6)
        TreeDistribution.FOREST -> scaledCount(density, 10)
        TreeDistribution.DENSE_FOREST -> scaledCount(density, 16)
    }

    @JvmStatic
    fun treeMaximumCount(distribution: TreeDistribution, density: Double): Int =
        max(treeBelowNoiseCount(distribution, density), treeAboveNoiseCount(distribution, density))

    /**
     * Approximate placement work relative to one ordinary tree.
     *
     * Every branch tip receives a crown, so branch count and crown volume are
     * multiplicative rather than cosmetic. Charging that work here makes the
     * existing per-chunk cap protect generation from a dense forest of maximum
     * sized, many-crowned trees.
     */
    @JvmStatic
    fun treeWorkPerTree(tree: TreeSpec): Double {
        val branchCount = tree.trunk.branches?.count ?: 0
        val branchBlocks = branchCount * (tree.trunk.branches?.length ?: 0)
        val trunkBlocks = tree.trunk.height.max * tree.trunk.thickness * tree.trunk.thickness
        val crownBox = (2 * tree.crown.radius + 1) * (2 * tree.crown.radius + 1) * tree.crown.height
        val crownWork = (branchCount + 1) * crownBox * tree.crown.density
        return max(1.0, (trunkBlocks + branchBlocks + crownWork) / 512.0)
    }

    private fun scaledCount(density: Double, maximum: Int): Int =
        if (density <= 0.0) 0 else max(1, (density * maximum).roundToInt())
}
