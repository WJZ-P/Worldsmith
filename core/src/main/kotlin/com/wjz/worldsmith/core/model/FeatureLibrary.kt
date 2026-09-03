package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

/**
 * Semantic feature shapes the target compiler knows how to build. A pack may
 * tune bounded, meaningful controls such as tree height and forest pattern; it
 * never names Minecraft placer classes or their version-specific codecs.
 */
/**
 * Which material a recipe is asking for, when it asks for more than one.
 *
 * <p>Minecraft has no general notion of a material palette: every feature
 * configuration names its own fields, and the names differ - a tree has
 * `trunk_provider` and `foliage_provider`, a huge fungus has `cap_provider` and
 * `stem_provider`, a block column just has `provider`. These are the semantic
 * names a pack uses; the compiler maps each one onto whatever the target
 * configuration happens to call it.
 *
 * <p>Only roles some recipe actually consumes are listed. A role no recipe
 * reads is a word an author can spend effort on for no effect, which is the
 * same failure as a recipe no contract documents.
 */
@Serializable
enum class MaterialRole {
    /** The single material of a recipe that only needs one. */
    BLOCK,
    TRUNK,
    FOLIAGE,
}

enum class FeatureRecipe {
    GROUND_PATCH,
    DEAD_TREE,
    BOULDER,
    ORE_VEIN,
    CAVE_PATCH,
    SURFACE_LAYER,
    AQUATIC_PATCH,
    HANGING_PATCH,
    TREE,
    FALLEN_LOG,
    ;

    /** True for anything Minecraft builds from a trunk and a crown of leaves. */
    val isTree: Boolean
        get() = this == TREE

    /** Exactly the roles this recipe reads. Anything else is an author's wasted effort. */
    val roles: Set<MaterialRole>
        get() = when {
            isTree -> setOf(MaterialRole.TRUNK, MaterialRole.FOLIAGE)
            this == DEAD_TREE || this == FALLEN_LOG -> setOf(MaterialRole.TRUNK)
            else -> setOf(MaterialRole.BLOCK)
        }

    /**
     * Whether a weighted material means anything here.
     *
     * <p>An ore vein and a boulder are given one block state by Minecraft's own
     * configuration rather than a provider, so a weighted list would be silently
     * collapsed to its first entry - worse than being told it cannot be used.
     */
    val supportsWeighted: Boolean
        get() = this != ORE_VEIN && this != BOULDER
}

/** The broad topology of a tree, independent from the blocks it is made of. */
@Serializable
enum class TreeSilhouette {
    BROADLEAF,
    CONIFER,
    BLOSSOM,
    WEEPING,
    UMBRELLA,
    SHRUB,
    ;

    /** Smallest height for which the vanilla placer behind this silhouette is well formed. */
    val minimumHeight: Int
        get() = when (this) {
            BROADLEAF, SHRUB -> 1
            CONIFER, WEEPING, UMBRELLA -> 3
            BLOSSOM -> 5
        }

    val defaultMinHeight: Int
        get() = when (this) {
            BROADLEAF -> 4
            CONIFER -> 6
            BLOSSOM -> 7
            WEEPING, UMBRELLA -> 5
            SHRUB -> 1
        }

    val defaultMaxHeight: Int
        get() = when (this) {
            BROADLEAF -> 6
            CONIFER -> 9
            BLOSSOM -> 8
            WEEPING -> 8
            UMBRELLA -> 9
            SHRUB -> 1
        }

    val defaultCrownRadius: Int
        get() = when (this) {
            BLOSSOM -> 4
            WEEPING -> 3
            BROADLEAF, CONIFER, UMBRELLA, SHRUB -> 2
        }
}

/** Path followed by the centre of a Worldsmith-authored trunk. */
@Serializable
enum class TreeTrunkShape {
    STRAIGHT,
    BENT,
    TWISTED,
    FORKED,
    BRANCHING,
}

/** Volume rule used by the Worldsmith foliage placer around every branch tip. */
@Serializable
enum class TreeCrownShape {
    ROUND,
    CONICAL,
    LAYERED,
    UMBRELLA,
    WEEPING,
    CLUSTERED,
}

/** How trees occupy chunks rather than what one tree looks like. */
@Serializable
enum class TreeDistribution {
    SCATTERED,
    GROVE,
    FOREST,
    DENSE_FOREST,
}

/** What kind of ground accepts the tree's origin. */
@Serializable
enum class TreeSubstrate {
    NATURAL_SOIL,
    SAND,
    SHALLOW_WATER,
    ANY_SOLID,
}

/** Optional details placed after the trunk and crown have succeeded. */
@Serializable
enum class TreeDecoration {
    VINES,
    LEAF_LITTER,
}

/** Inclusive nominal height range sampled before the crown is placed. */
@Serializable
data class TreeHeight(
    val min: Int,
    val max: Int,
)

/** Configuration owned only by [FeatureRecipe.TREE]. */
@Serializable
data class TreeSpec(
    val silhouette: TreeSilhouette,
    val distribution: TreeDistribution,
    val substrate: TreeSubstrate,
    val height: TreeHeight? = null,
    val crownRadius: Int? = null,
    val hangingLeaves: Double? = null,
    val decorations: List<TreeDecoration> = emptyList(),
)

/**
 * One reusable feature. Several biomes may reference the same definition, which
 * is the point: the shape and material are declared once and compiled once.
 */
@Serializable
data class FeatureDefinition(
    val id: String,
    val recipe: FeatureRecipe,
    /** Shorthand for a recipe that reads one material; mapped to that recipe's sole role. */
    val block: MaterialSelector? = null,
    val materials: Map<MaterialRole, MaterialSelector> = emptyMap(),
    val density: Double,
    val tree: TreeSpec? = null,
) {
    /**
     * Every material this feature declares, whichever form the author wrote.
     *
     * <p>Read this rather than [block]: the shorthand and the map would
     * otherwise be two code paths and the unexercised one would rot, which is
     * exactly how the placement shorthand would have gone wrong.
     */
    val allMaterials: Map<MaterialRole, MaterialSelector>
        get() = when {
            materials.isNotEmpty() -> materials
            block != null && recipe.roles.size == 1 -> mapOf(recipe.roles.single() to block)
            else -> emptyMap()
        }
}

@Serializable
data class FeatureLibrary(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val features: List<FeatureDefinition> = emptyList(),
)
