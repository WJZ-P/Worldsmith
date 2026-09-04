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

/** Path followed by the centre of a Worldsmith-authored trunk. */
@Serializable
enum class TreeTrunkShape {
    STRAIGHT,
    BENT,
    TWISTED,
    /** A thick lower stem that deliberately narrows into a one-block upper stem. */
    TAPERED,
    /** A stem that changes drift direction at irregular intervals. */
    CROOKED,
    FORKED,
    BRANCHING,
    /** Several complete stems share one root origin and separate as they rise. */
    MULTI_STEM,
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
    COLUMNAR,
    PAGODA,
    WINDSWEPT,
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

/** What the block immediately supporting a non-tree feature must be. */
@Serializable
enum class FeatureSubstrate {
    /** Keep the recipe's established placement rule. */
    RECIPE_DEFAULT,
    NATURAL_SOIL,
    SAND,
    STONE,
    ANY_SOLID,
}

/** Which fluid environment accepts the origin of a non-tree feature. */
@Serializable
enum class FeatureFluid {
    /** Keep the recipe's established dry, aquatic, or underground rule. */
    RECIPE_DEFAULT,
    DRY,
    SUBMERGED,
    SHALLOW_WATER,
    /** Accept either air or water; solid origins such as ore still use their recipe target. */
    ANY,
}

/**
 * Conditions shared by non-tree scatter recipes.
 *
 * Absolute height is deliberately a filter after the recipe has found its
 * surface or cave attachment. It therefore changes where a feature is allowed,
 * not the distribution of the terrain beneath it.
 */
@Serializable
data class FeaturePlacementConditions(
    val minY: Int? = null,
    val maxY: Int? = null,
    val substrate: FeatureSubstrate = FeatureSubstrate.RECIPE_DEFAULT,
    val fluid: FeatureFluid = FeatureFluid.RECIPE_DEFAULT,
)

/** Several nearby blocks produced by one patch attempt. */
@Serializable
data class FeaturePatchSpec(
    val attempts: Int = 1,
    val horizontalSpread: Int = 0,
    val verticalSpread: Int = 0,
    /** Distance searched for a cave floor or ceiling; ignored by surface patches. */
    val scanDepth: Int = 12,
)

/** A formation made from one or more overlapping vanilla-style rock blobs. */
@Serializable
data class BoulderSpec(
    val blobs: Int = 1,
    val spread: Int = 0,
)

/** Shape of one ore vein before density decides how many are attempted. */
@Serializable
data class OreVeinSpec(
    val size: Int = 33,
    val discardChanceOnAirExposure: Double = 0.0,
)

/** Inclusive length range for a vertical bare or hanging column. */
@Serializable
data class ColumnSpec(
    val minLength: Int,
    val maxLength: Int,
)

/** Inclusive length range for the horizontal part of a fallen tree. */
@Serializable
data class FallenLogSpec(
    val minLength: Int = 3,
    val maxLength: Int = 6,
)

/** Inclusive nominal height range sampled before the crown is placed. */
@Serializable
data class TreeHeight(
    val min: Int,
    val max: Int,
)

@Serializable
data class TreeBranchSpec(
    val count: Int,
    val length: Int,
    /** Fraction of trunk height below which no branch starts. */
    val start: Double,
    /** Chance for each outward branch step to also rise by one block. */
    val upwardBias: Double = 0.5,
    /** Angular spread: 0 keeps branches near one direction, 1 distributes them around the trunk. */
    val spread: Double = 1.0,
    /** Maximum fraction by which an individual branch may be shortened. */
    val lengthVariation: Double = 0.0,
)

@Serializable
data class TreeTrunkSpec(
    val shape: TreeTrunkShape,
    val height: TreeHeight,
    val thickness: Int = 1,
    val bend: Double = 0.0,
    val branches: TreeBranchSpec? = null,
    /** Upper fraction that narrows from a 2x2 footprint to 1x1. */
    val taper: Double = 0.0,
    /** Horizontal root-flare reach from the base, from zero to two blocks. */
    val flare: Int = 0,
    /** Number of complete stems for [TreeTrunkShape.MULTI_STEM]; one for every other shape. */
    val stems: Int = 1,
)

@Serializable
data class TreeCrownSpec(
    val shape: TreeCrownShape,
    val radius: Int,
    val height: Int,
    /** Interior fill chance after the geometric volume is selected. */
    val density: Double = 0.85,
    /** Extra thinning toward the crown boundary. */
    val irregularity: Double = 0.25,
    val hangingLeaves: Double = 0.0,
)

/** Configuration owned only by [FeatureRecipe.TREE]. */
@Serializable
data class TreeSpec(
    val trunk: TreeTrunkSpec,
    val crown: TreeCrownSpec,
    val distribution: TreeDistribution,
    val substrate: TreeSubstrate,
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
    /** Cluster geometry for patch recipes, including hanging columns. */
    val patch: FeaturePatchSpec? = null,
    val boulder: BoulderSpec? = null,
    val oreVein: OreVeinSpec? = null,
    val column: ColumnSpec? = null,
    val fallenLog: FallenLogSpec? = null,
    val placement: FeaturePlacementConditions = FeaturePlacementConditions(),
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
