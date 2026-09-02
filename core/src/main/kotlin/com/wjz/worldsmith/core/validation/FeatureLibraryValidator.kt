package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.TreeSilhouette
import com.wjz.worldsmith.core.model.TreeSpec

object FeatureLibraryValidator {
    internal val ID = Regex("^[a-z0-9_.-]+$")
    private const val MAX_WEIGHTED_ENTRIES = 8
    private const val MAX_MATERIAL_WEIGHT = 64
    private const val MAX_TREE_BASE_HEIGHT = 32
    private const val MAX_TREE_HEIGHT_VARIATION = 24
    private const val MAX_TREE_HEIGHT = MAX_TREE_BASE_HEIGHT + MAX_TREE_HEIGHT_VARIATION
    private const val MIN_CROWN_RADIUS = 1
    private const val MAX_CROWN_RADIUS = 8

    fun validate(library: FeatureLibrary): List<Diagnostic> = buildList {
        if (library.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported feature library schema ${library.schemaVersion}"))
        }

        library.features.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error("features[$id]", "DUPLICATE_FEATURE", "Feature id must be unique"))
        }

        library.features.forEachIndexed { index, feature ->
            val path = "features[$index]"
            if (!ID.matches(feature.id)) {
                add(error("$path.id", "INVALID_FEATURE_ID", "Feature id must match ${ID.pattern}"))
            }
            if (feature.density !in 0.0..1.0) {
                add(error("$path.density", "DENSITY_OUT_OF_RANGE", "Feature density must be between 0 and 1"))
            }
            if (feature.materials.isNotEmpty() && feature.block != null) {
                add(
                    error(
                        "$path.materials",
                        "AMBIGUOUS_MATERIALS",
                        "A feature uses the materials map or the block shorthand, not both",
                    ),
                )
            }
            when {
                feature.recipe.isTree && feature.tree == null -> add(
                    error(
                        "$path.tree",
                        "MISSING_TREE_SPEC",
                        "The TREE recipe must name its silhouette in a tree object",
                    ),
                )
                !feature.recipe.isTree && feature.tree != null -> add(
                    error(
                        "$path.tree",
                        "UNUSED_TREE_SPEC",
                        "The ${feature.recipe} recipe never reads a tree object",
                    ),
                )
            }
            feature.tree?.let { addAll(validateTree("$path.tree", it)) }
            addAll(validateRoles(path, feature))
            feature.allMaterials.forEach { (role, selector) ->
                val rolePath = if (feature.materials.isEmpty()) "$path.block" else "$path.materials.$role"
                addAll(validateMaterial(rolePath, selector))
                if (selector.weighted.isNotEmpty() && !feature.recipe.supportsWeighted) {
                    add(
                        error(
                            "$rolePath.weighted",
                            "WEIGHTED_NOT_SUPPORTED",
                            "The ${feature.recipe} recipe is given one block state by Minecraft rather than a " +
                                "provider, so a weighted list would silently collapse to its first entry",
                        ),
                    )
                }
            }
        }
    }

    private fun validateTree(path: String, tree: TreeSpec): List<Diagnostic> = buildList {
        tree.height?.let { height ->
            if (height.min > height.max) {
                add(error("$path.height", "REVERSED_TREE_HEIGHT", "Tree height minimum must not exceed its maximum"))
            }
            if (height.min < tree.silhouette.minimumHeight || height.min > MAX_TREE_BASE_HEIGHT) {
                add(
                    error(
                        "$path.height.min",
                        "TREE_HEIGHT_OUT_OF_RANGE",
                        "${tree.silhouette} height must start between ${tree.silhouette.minimumHeight} and $MAX_TREE_BASE_HEIGHT",
                    ),
                )
            }
            if (height.max > MAX_TREE_HEIGHT || height.max - height.min > MAX_TREE_HEIGHT_VARIATION) {
                add(
                    error(
                        "$path.height.max",
                        "TREE_HEIGHT_OUT_OF_RANGE",
                        "Tree height may reach $MAX_TREE_HEIGHT with at most $MAX_TREE_HEIGHT_VARIATION blocks of variation",
                    ),
                )
            }
        }
        tree.crownRadius?.let { radius ->
            if (radius !in MIN_CROWN_RADIUS..MAX_CROWN_RADIUS) {
                add(
                    error(
                        "$path.crownRadius",
                        "CROWN_RADIUS_OUT_OF_RANGE",
                        "Crown radius must be between $MIN_CROWN_RADIUS and $MAX_CROWN_RADIUS",
                    ),
                )
            }
        }
        val effectiveMinHeight = tree.height?.min ?: tree.silhouette.defaultMinHeight
        val effectiveRadius = tree.crownRadius ?: tree.silhouette.defaultCrownRadius
        val minimumForCrown = when (tree.silhouette) {
            TreeSilhouette.BROADLEAF -> effectiveRadius + 2
            TreeSilhouette.BLOSSOM -> effectiveRadius + 3
            TreeSilhouette.WEEPING -> effectiveRadius + 2
            TreeSilhouette.CONIFER, TreeSilhouette.UMBRELLA -> 3
            TreeSilhouette.SHRUB -> 1
        }
        if (effectiveMinHeight < minimumForCrown) {
            add(
                error(
                    path,
                    "TREE_CROWN_EXCEEDS_HEIGHT",
                    "${tree.silhouette} with crown radius $effectiveRadius needs a minimum height of at least $minimumForCrown",
                ),
            )
        }
        tree.hangingLeaves?.let { chance ->
            if (chance !in 0.0..1.0) {
                add(error("$path.hangingLeaves", "HANGING_LEAVES_OUT_OF_RANGE", "Hanging leaves must be between 0 and 1"))
            }
            if (tree.silhouette != TreeSilhouette.BLOSSOM && tree.silhouette != TreeSilhouette.WEEPING) {
                add(
                    error(
                        "$path.hangingLeaves",
                        "HANGING_LEAVES_NOT_SUPPORTED",
                        "Only BLOSSOM and WEEPING crowns consume hangingLeaves",
                    ),
                )
            }
        }
        tree.decorations.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { decoration ->
            add(error("$path.decorations", "DUPLICATE_TREE_DECORATION", "$decoration is listed more than once"))
        }
    }

    /**
     * Reports a feature that answers a different question than its recipe asks.
     *
     * <p>A missing role leaves the compiler with nothing to build that part of
     * the shape from; a role the recipe never reads is effort an author spent
     * that changes nothing in the world and nothing tells them so.
     */
    private fun validateRoles(path: String, feature: FeatureDefinition): List<Diagnostic> = buildList {
        val declared = feature.allMaterials.keys
        val required = feature.recipe.roles
        (required - declared).sorted().forEach { role ->
            add(
                error(
                    "$path.materials.$role",
                    "MISSING_MATERIAL_ROLE",
                    "The ${feature.recipe} recipe is built from ${required.sorted().joinToString(", ")} " +
                        "but $role was not given",
                ),
            )
        }
        (declared - required).sorted().forEach { role ->
            add(
                error(
                    "$path.materials.$role",
                    "UNUSED_MATERIAL_ROLE",
                    "The ${feature.recipe} recipe never reads $role, so this material would never be placed",
                ),
            )
        }
    }

    internal fun validateMaterial(path: String, selector: MaterialSelector): List<Diagnostic> = buildList {
        if (selector.semanticRole.isBlank()) {
            add(error("$path.semanticRole", "EMPTY_SEMANTIC_ROLE", "Material selector must name a semantic role"))
        }
        if (selector.weighted.isEmpty()) {
            if (selector.preferredIds.isEmpty() && selector.requiredTags.isEmpty()) {
                add(error(path, "EMPTY_MATERIAL", "Material selector must list preferred ids or required tags"))
            }
            return@buildList
        }
        if (selector.preferredIds.isNotEmpty() || selector.requiredTags.isNotEmpty()) {
            add(
                error(
                    path,
                    "AMBIGUOUS_MATERIAL",
                    "A weighted selector picks between its entries; it must not also list ids or tags of its own",
                ),
            )
        }
        if (selector.weighted.size > MAX_WEIGHTED_ENTRIES) {
            add(
                error(
                    "$path.weighted",
                    "TOO_MANY_ALTERNATIVES",
                    "A weighted selector may hold at most $MAX_WEIGHTED_ENTRIES entries",
                ),
            )
        }
        selector.weighted.forEachIndexed { index, entry ->
            if (entry.weight !in 1..MAX_MATERIAL_WEIGHT) {
                add(
                    error(
                        "$path.weighted[$index].weight",
                        "WEIGHT_OUT_OF_RANGE",
                        "A weight must be between 1 and $MAX_MATERIAL_WEIGHT",
                    ),
                )
            }
            if (entry.material.weighted.isNotEmpty()) {
                add(
                    error(
                        "$path.weighted[$index].material",
                        "NESTED_WEIGHTED_MATERIAL",
                        "A weighted entry is one material; nest one level only",
                    ),
                )
            }
            addAll(validateMaterial("$path.weighted[$index].material", entry.material))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
