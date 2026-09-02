package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

/**
 * Vegetation shapes the target compiler knows how to build. The pack picks a
 * recipe and a material; it never describes placement geometry itself.
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

enum class VegetationRecipe {
    GROUND_PATCH,
    DEAD_TREE,
    BOULDER,
    ORE_VEIN,
    CAVE_PATCH,
    SURFACE_LAYER,
    TREE,
    ;

    /** Exactly the roles this recipe reads. Anything else is an author's wasted effort. */
    val roles: Set<MaterialRole>
        get() = when (this) {
            TREE -> setOf(MaterialRole.TRUNK, MaterialRole.FOLIAGE)
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

/**
 * One reusable feature. Several biomes may reference the same definition, which
 * is the point: the shape and material are declared once and compiled once.
 */
@Serializable
data class FeatureDefinition(
    val id: String,
    val recipe: VegetationRecipe,
    /** Shorthand for a recipe that reads one material; equivalent to `materials.BLOCK`. */
    val block: MaterialSelector? = null,
    val materials: Map<MaterialRole, MaterialSelector> = emptyMap(),
    val density: Double,
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
            block != null -> mapOf(MaterialRole.BLOCK to block)
            else -> emptyMap()
        }
}

@Serializable
data class FeatureLibrary(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val features: List<FeatureDefinition> = emptyList(),
)
