package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureFluid
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.FeaturePlacementConditions
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.FeatureSubstrate
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.TreeSpec
import com.wjz.worldsmith.core.model.TreeCrownShape
import com.wjz.worldsmith.core.model.TreeTrunkShape

object FeatureLibraryValidator {
    internal val ID = Regex("^[a-z0-9_.-]+$")
    internal val NAMESPACED_ID = Regex("^[a-z0-9_.-]+:[a-z0-9/._-]+$")
    private const val MAX_WEIGHTED_ENTRIES = 8
    private const val MAX_MATERIAL_WEIGHT = 64
    private const val MAX_TREE_BASE_HEIGHT = 32
    private const val MAX_TREE_HEIGHT_VARIATION = 24
    private const val MAX_TREE_HEIGHT = 36
    private const val MAX_TREE_TOTAL_HEIGHT = 44
    private const val MIN_CROWN_RADIUS = 1
    private const val MAX_CROWN_RADIUS = 8
    private const val MAX_CROWN_HEIGHT = 12
    private const val MIN_WORLD_Y = -64
    private const val MAX_WORLD_Y = 319

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
                        "The TREE recipe must provide trunk and crown rules in a tree object",
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
            addAll(validateShape(path, feature))
            addAll(validatePlacement("$path.placement", feature))
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

    private fun validateShape(path: String, feature: FeatureDefinition): List<Diagnostic> = buildList {
        val patchRecipes = setOf(
            FeatureRecipe.GROUND_PATCH,
            FeatureRecipe.CAVE_PATCH,
            FeatureRecipe.SURFACE_LAYER,
            FeatureRecipe.AQUATIC_PATCH,
            FeatureRecipe.HANGING_PATCH,
        )
        feature.patch?.let { patch ->
            if (feature.recipe !in patchRecipes) {
                add(error("$path.patch", "UNUSED_PATCH_SPEC", "The ${feature.recipe} recipe never reads patch geometry"))
            }
            if (patch.attempts !in 1..32) {
                add(error("$path.patch.attempts", "PATCH_ATTEMPTS_OUT_OF_RANGE", "Patch attempts must be between 1 and 32"))
            }
            if (patch.horizontalSpread !in 0..8) {
                add(error("$path.patch.horizontalSpread", "PATCH_SPREAD_OUT_OF_RANGE", "Horizontal patch spread must be between 0 and 8"))
            }
            if (patch.verticalSpread !in 0..8) {
                add(error("$path.patch.verticalSpread", "PATCH_VERTICAL_SPREAD_OUT_OF_RANGE", "Vertical patch spread must be between 0 and 8"))
            }
            if (patch.scanDepth !in 1..32) {
                add(error("$path.patch.scanDepth", "PATCH_SCAN_DEPTH_OUT_OF_RANGE", "Cave scan depth must be between 1 and 32"))
            }
            if (patch.attempts > 1 && patch.horizontalSpread == 0 && patch.verticalSpread == 0) {
                add(error("$path.patch", "PATCH_ATTEMPTS_NEED_SPREAD", "Repeated patch attempts need horizontal or vertical spread to affect more than one block"))
            }
            if (feature.recipe !in setOf(FeatureRecipe.CAVE_PATCH, FeatureRecipe.HANGING_PATCH)) {
                if (patch.verticalSpread != 0) {
                    add(error("$path.patch.verticalSpread", "UNUSED_PATCH_VERTICAL_SPREAD", "Only cave and hanging patches use vertical spread"))
                }
                if (patch.scanDepth != 12) {
                    add(error("$path.patch.scanDepth", "UNUSED_PATCH_SCAN_DEPTH", "Only cave and hanging patches use scan depth"))
                }
            }
        }
        feature.boulder?.let { boulder ->
            if (feature.recipe != FeatureRecipe.BOULDER) {
                add(error("$path.boulder", "UNUSED_BOULDER_SPEC", "Only BOULDER reads boulder geometry"))
            }
            if (boulder.blobs !in 1..8) {
                add(error("$path.boulder.blobs", "BOULDER_BLOBS_OUT_OF_RANGE", "A boulder formation may contain 1 to 8 blobs"))
            }
            if (boulder.spread !in 0..8) {
                add(error("$path.boulder.spread", "BOULDER_SPREAD_OUT_OF_RANGE", "Boulder spread must be between 0 and 8"))
            }
        }
        feature.oreVein?.let { vein ->
            if (feature.recipe != FeatureRecipe.ORE_VEIN) {
                add(error("$path.oreVein", "UNUSED_ORE_VEIN_SPEC", "Only ORE_VEIN reads ore vein geometry"))
            }
            if (vein.size !in 1..64) {
                add(error("$path.oreVein.size", "ORE_SIZE_OUT_OF_RANGE", "Ore vein size must be between 1 and 64"))
            }
            if (vein.discardChanceOnAirExposure !in 0.0..1.0) {
                add(error("$path.oreVein.discardChanceOnAirExposure", "ORE_EXPOSURE_OUT_OF_RANGE", "Ore exposure discard chance must be between 0 and 1"))
            }
        }
        feature.column?.let { column ->
            if (feature.recipe != FeatureRecipe.DEAD_TREE && feature.recipe != FeatureRecipe.HANGING_PATCH) {
                add(error("$path.column", "UNUSED_COLUMN_SPEC", "Only DEAD_TREE and HANGING_PATCH read column length"))
            }
            if (column.minLength > column.maxLength) {
                add(error("$path.column", "REVERSED_COLUMN_LENGTH", "Column minimum length must not exceed its maximum"))
            }
            if (column.minLength !in 1..16 || column.maxLength !in 1..16) {
                add(error("$path.column", "COLUMN_LENGTH_OUT_OF_RANGE", "Column length must stay between 1 and 16"))
            }
        }
        feature.fallenLog?.let { log ->
            if (feature.recipe != FeatureRecipe.FALLEN_LOG) {
                add(error("$path.fallenLog", "UNUSED_FALLEN_LOG_SPEC", "Only FALLEN_LOG reads fallen-log length"))
            }
            if (log.minLength > log.maxLength) {
                add(error("$path.fallenLog", "REVERSED_FALLEN_LOG_LENGTH", "Fallen-log minimum length must not exceed its maximum"))
            }
            if (log.minLength !in 1..14 || log.maxLength !in 1..14) {
                add(error("$path.fallenLog", "FALLEN_LOG_LENGTH_OUT_OF_RANGE", "Fallen-log length must stay between 1 and 14"))
            }
        }
    }

    private fun validatePlacement(path: String, feature: FeatureDefinition): List<Diagnostic> = buildList {
        val placement = feature.placement
        if (feature.recipe == FeatureRecipe.TREE && placement != FeaturePlacementConditions()) {
            add(error(path, "TREE_PLACEMENT_IS_SEPARATE", "TREE keeps its distribution and substrate inside the tree object"))
        }
        placement.minY?.let { minY ->
            if (minY !in MIN_WORLD_Y..MAX_WORLD_Y) {
                add(error("$path.minY", "FEATURE_HEIGHT_OUT_OF_RANGE", "Feature height must stay within $MIN_WORLD_Y..$MAX_WORLD_Y"))
            }
        }
        placement.maxY?.let { maxY ->
            if (maxY !in MIN_WORLD_Y..MAX_WORLD_Y) {
                add(error("$path.maxY", "FEATURE_HEIGHT_OUT_OF_RANGE", "Feature height must stay within $MIN_WORLD_Y..$MAX_WORLD_Y"))
            }
        }
        if (placement.minY != null && placement.maxY != null && placement.minY > placement.maxY) {
            add(error(path, "REVERSED_FEATURE_HEIGHT", "Feature minimum Y must not exceed maximum Y"))
        }
        if (feature.recipe == FeatureRecipe.ORE_VEIN) {
            if (placement.substrate != FeatureSubstrate.RECIPE_DEFAULT) {
                add(error("$path.substrate", "ORE_TARGET_IS_NOT_SUBSTRATE", "ORE_VEIN replaces its stone target; it does not use a supporting substrate"))
            }
            if (placement.fluid != FeatureFluid.RECIPE_DEFAULT && placement.fluid != FeatureFluid.ANY) {
                add(error("$path.fluid", "ORE_FLUID_NOT_SUPPORTED", "ORE_VEIN is embedded in solid rock and only accepts RECIPE_DEFAULT or ANY"))
            }
        }
    }

    private fun validateTree(path: String, tree: TreeSpec): List<Diagnostic> = buildList {
        val trunk = tree.trunk
        val crown = tree.crown
        val height = trunk.height
        if (height.min > height.max) {
            add(error("$path.trunk.height", "REVERSED_TREE_HEIGHT", "Tree height minimum must not exceed its maximum"))
        }
        if (height.min !in 1..MAX_TREE_BASE_HEIGHT || height.max !in 1..MAX_TREE_HEIGHT ||
            height.max - height.min > MAX_TREE_HEIGHT_VARIATION
        ) {
            add(
                error(
                    "$path.trunk.height",
                    "TREE_HEIGHT_OUT_OF_RANGE",
                    "Tree height must stay within 1..$MAX_TREE_HEIGHT, start by $MAX_TREE_BASE_HEIGHT, " +
                        "and vary by at most $MAX_TREE_HEIGHT_VARIATION blocks",
                ),
            )
        }
        if (trunk.thickness !in 1..2) {
            add(error("$path.trunk.thickness", "TRUNK_THICKNESS_OUT_OF_RANGE", "Trunk thickness must be 1 or 2"))
        }
        if (trunk.bend !in 0.0..1.0) {
            add(error("$path.trunk.bend", "TRUNK_BEND_OUT_OF_RANGE", "Trunk bend must be between 0 and 1"))
        }
        if (trunk.bend != 0.0 && trunk.shape != TreeTrunkShape.BENT && trunk.shape != TreeTrunkShape.TWISTED) {
            add(error("$path.trunk.bend", "UNUSED_TRUNK_BEND", "Only BENT and TWISTED trunk paths consume bend"))
        }
        val branches = trunk.branches
        if ((trunk.shape == TreeTrunkShape.FORKED || trunk.shape == TreeTrunkShape.BRANCHING) && branches == null) {
            add(error("$path.trunk.branches", "MISSING_TREE_BRANCHES", "${trunk.shape} requires a branches object"))
        }
        branches?.let { branch ->
            val minimumCount = if (trunk.shape == TreeTrunkShape.FORKED) 2 else 1
            if (branch.count !in minimumCount..8) {
                add(error("$path.trunk.branches.count", "BRANCH_COUNT_OUT_OF_RANGE", "Branch count must be between $minimumCount and 8"))
            }
            if (branch.length !in 1..8) {
                add(error("$path.trunk.branches.length", "BRANCH_LENGTH_OUT_OF_RANGE", "Branch length must be between 1 and 8"))
            }
            if (branch.start !in 0.2..0.95) {
                add(error("$path.trunk.branches.start", "BRANCH_START_OUT_OF_RANGE", "Branch start must be between 0.2 and 0.95"))
            }
            if (branch.upwardBias !in 0.0..1.0) {
                add(error("$path.trunk.branches.upwardBias", "BRANCH_BIAS_OUT_OF_RANGE", "Branch upward bias must be between 0 and 1"))
            }
        }

        if (crown.radius !in MIN_CROWN_RADIUS..MAX_CROWN_RADIUS) {
            add(error("$path.crown.radius", "CROWN_RADIUS_OUT_OF_RANGE", "Crown radius must be between 1 and $MAX_CROWN_RADIUS"))
        }
        if (crown.height !in 1..MAX_CROWN_HEIGHT) {
            add(error("$path.crown.height", "CROWN_HEIGHT_OUT_OF_RANGE", "Crown height must be between 1 and $MAX_CROWN_HEIGHT"))
        }
        if (crown.density !in 0.1..1.0) {
            add(error("$path.crown.density", "CROWN_DENSITY_OUT_OF_RANGE", "Crown density must be between 0.1 and 1"))
        }
        if (crown.irregularity !in 0.0..1.0) {
            add(error("$path.crown.irregularity", "CROWN_IRREGULARITY_OUT_OF_RANGE", "Crown irregularity must be between 0 and 1"))
        }
        if (crown.hangingLeaves !in 0.0..1.0) {
            add(error("$path.crown.hangingLeaves", "HANGING_LEAVES_OUT_OF_RANGE", "Hanging leaves must be between 0 and 1"))
        }

        val downwardReach = when (crown.shape) {
            TreeCrownShape.ROUND -> crown.height / 2
            TreeCrownShape.CONICAL, TreeCrownShape.LAYERED -> crown.height - 1
            TreeCrownShape.UMBRELLA -> minOf(3, crown.height - 1)
            TreeCrownShape.WEEPING -> crown.height - maxOf(1, crown.height / 3)
            TreeCrownShape.CLUSTERED -> crown.height / 4
        } + if (crown.hangingLeaves > 0.0) 2 else 0
        if (height.min <= downwardReach) {
            add(
                error(
                    path,
                    "TREE_CROWN_EXCEEDS_HEIGHT",
                    "The crown reaches $downwardReach blocks downward, so minimum tree height must be greater than that",
                ),
            )
        }
        val branchRise = branches?.takeIf { it.upwardBias > 0.0 }?.length ?: 0
        val crownRise = when (crown.shape) {
            TreeCrownShape.ROUND -> crown.height / 2
            TreeCrownShape.CONICAL, TreeCrownShape.LAYERED -> 0
            TreeCrownShape.UMBRELLA -> 1
            TreeCrownShape.WEEPING -> maxOf(1, crown.height / 3)
            TreeCrownShape.CLUSTERED -> crown.height / 4
        }
        if (height.max + branchRise + crownRise > MAX_TREE_TOTAL_HEIGHT) {
            add(
                error(
                    path,
                    "TREE_TOTAL_HEIGHT_OUT_OF_RANGE",
                    "Trunk, rising branches and crown may reach at most $MAX_TREE_TOTAL_HEIGHT blocks; larger trees are structures",
                ),
            )
        }
        branches?.let { branch ->
            val lowestAttachment = ((height.min - 1) * branch.start).toInt() + 1
            if (lowestAttachment <= downwardReach) {
                add(
                    error(
                        "$path.trunk.branches.start",
                        "BRANCH_CROWN_REACHES_GROUND",
                        "The first branch crown reaches the ground; raise branch start or reduce crown height",
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
        selector.preferredIds.forEachIndexed { index, id ->
            if (!NAMESPACED_ID.matches(id)) {
                add(error("$path.preferredIds[$index]", "INVALID_BLOCK_ID", "Block id must be a namespaced identifier such as minecraft:stone"))
            }
        }
        selector.requiredTags.forEachIndexed { index, id ->
            if (!NAMESPACED_ID.matches(id)) {
                add(error("$path.requiredTags[$index]", "INVALID_BLOCK_TAG_ID", "Block tag id must be namespaced without a leading #, such as minecraft:logs"))
            }
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
