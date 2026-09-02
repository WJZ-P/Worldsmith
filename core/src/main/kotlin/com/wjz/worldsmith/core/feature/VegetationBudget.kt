package com.wjz.worldsmith.core.feature

import com.wjz.worldsmith.core.model.FeatureRecipe
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

    /** Attempts per chunk a single biome may spend on vegetation. */
    const val MAX_ATTEMPTS_PER_CHUNK: Double = 64.0

    @JvmStatic
    fun patchCount(density: Double): Int = max(1, (density * MAX_PATCH_COUNT).roundToInt())

    @JvmStatic
    fun rarity(density: Double): Int = max(1, ((1.0 - density) * MAX_RARITY).roundToInt())

    /** Ore veins are cheap per attempt but many, so they get their own scale. */
    @JvmStatic
    fun veinCount(density: Double): Int = max(1, (density * MAX_VEIN_COUNT).roundToInt())

    @JvmStatic
    fun attemptsPerChunk(recipe: FeatureRecipe, density: Double): Double = when (recipe) {
        FeatureRecipe.GROUND_PATCH, FeatureRecipe.SURFACE_LAYER, FeatureRecipe.AQUATIC_PATCH ->
            patchCount(density).toDouble()
        FeatureRecipe.ORE_VEIN, FeatureRecipe.CAVE_PATCH, FeatureRecipe.HANGING_PATCH ->
            veinCount(density).toDouble()
        // Listed rather than defaulted: a new recipe should not silently inherit
        // a cost, because being charged wrongly is how a pack slips past the cap.
        FeatureRecipe.DEAD_TREE, FeatureRecipe.BOULDER, FeatureRecipe.FALLEN_LOG,
        FeatureRecipe.TREE, FeatureRecipe.CONIFER, FeatureRecipe.BLOSSOM_TREE,
        FeatureRecipe.WEEPING_TREE, FeatureRecipe.UMBRELLA_TREE, FeatureRecipe.SHRUB ->
            1.0 / rarity(density)
    }
}
