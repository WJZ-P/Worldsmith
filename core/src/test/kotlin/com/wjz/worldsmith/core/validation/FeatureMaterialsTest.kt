package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialRole
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.WeightedMaterial
import com.wjz.worldsmith.core.model.TreeSilhouette
import com.wjz.worldsmith.core.model.TreeSpec
import com.wjz.worldsmith.core.model.TreeDistribution
import com.wjz.worldsmith.core.model.TreeSubstrate
import com.wjz.worldsmith.core.model.TreeDecoration
import com.wjz.worldsmith.core.model.TreeHeight
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Minecraft has no material palette: each feature configuration names its own
 * fields, and each of those takes a provider that may itself hold several
 * blocks. These are the two halves of that, and the failures they catch are
 * both silent - a role nothing reads is effort that never reaches the world,
 * and a weighted list handed to a config that wants one block state would be
 * collapsed to its first entry without a word.
 */
class FeatureMaterialsTest {
    @Test
    fun `a tree needs both of the materials it is built from`() {
        val missingFoliage = library(
            FeatureDefinition(
                "oak", FeatureRecipe.TREE,
                materials = mapOf(MaterialRole.TRUNK to selector("wood", "minecraft:oak_log")),
                density = 0.2,
                tree = tree(TreeSilhouette.BROADLEAF),
            ),
        )

        val codes = FeatureLibraryValidator.validate(missingFoliage).map { it.code }

        assertTrue("MISSING_MATERIAL_ROLE" in codes, codes.toString())
    }

    @Test
    fun `tree shape belongs to TREE and is required there`() {
        val missing = FeatureDefinition(
            "missing_shape", FeatureRecipe.TREE,
            materials = mapOf(
                MaterialRole.TRUNK to selector("wood", "minecraft:oak_log"),
                MaterialRole.FOLIAGE to selector("leaves", "minecraft:oak_leaves"),
            ),
            density = 0.3,
        )
        val misplaced = FeatureDefinition(
            "rock_with_crown", FeatureRecipe.BOULDER,
            block = selector("rock", "minecraft:stone"),
            density = 0.3,
            tree = tree(TreeSilhouette.UMBRELLA),
        )

        assertTrue(FeatureLibraryValidator.validate(library(missing)).any { it.code == "MISSING_TREE_SPEC" })
        assertTrue(FeatureLibraryValidator.validate(library(misplaced)).any { it.code == "UNUSED_TREE_SPEC" })
    }

    @Test
    fun `one TREE recipe carries every silhouette through json`() {
        TreeSilhouette.entries.forEach { silhouette ->
            val tree = FeatureDefinition(
                "tree_${silhouette.name.lowercase()}",
                FeatureRecipe.TREE,
                materials = mapOf(
                    MaterialRole.TRUNK to selector("wood", "minecraft:oak_log"),
                    MaterialRole.FOLIAGE to selector("leaves", "minecraft:oak_leaves"),
                ),
                density = 0.3,
                tree = tree(silhouette),
            )

            assertEquals(tree, WorldsmithJson.decode<FeatureLibrary>(WorldsmithJson.encode(library(tree))).features.single())
        }
    }

    @Test
    fun `single material trunk recipes map block shorthand onto TRUNK`() {
        val bare = FeatureDefinition(
            "bare", FeatureRecipe.DEAD_TREE,
            block = selector("wood", "minecraft:oak_log"),
            density = 0.2,
        )

        assertEquals(setOf(MaterialRole.TRUNK), bare.allMaterials.keys)
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(library(bare)))
    }

    @Test
    fun `tree geometry rejects ranges and fields its silhouette does not consume`() {
        val malformed = FeatureDefinition(
            "malformed_tree",
            FeatureRecipe.TREE,
            materials = mapOf(
                MaterialRole.TRUNK to selector("wood", "minecraft:oak_log"),
                MaterialRole.FOLIAGE to selector("leaves", "minecraft:oak_leaves"),
            ),
            density = 0.5,
            tree = TreeSpec(
                silhouette = TreeSilhouette.BROADLEAF,
                distribution = TreeDistribution.FOREST,
                substrate = TreeSubstrate.ANY_SOLID,
                height = TreeHeight(20, 10),
                crownRadius = 12,
                hangingLeaves = 0.5,
                decorations = listOf(TreeDecoration.VINES, TreeDecoration.VINES),
            ),
        )

        val codes = FeatureLibraryValidator.validate(library(malformed)).map { it.code }

        assertTrue("REVERSED_TREE_HEIGHT" in codes, codes.toString())
        assertTrue("CROWN_RADIUS_OUT_OF_RANGE" in codes, codes.toString())
        assertTrue("HANGING_LEAVES_NOT_SUPPORTED" in codes, codes.toString())
        assertTrue("DUPLICATE_TREE_DECORATION" in codes, codes.toString())

        val sunkenCrown = malformed.copy(
            id = "sunken_crown",
            tree = malformed.tree!!.copy(
                height = TreeHeight(4, 6),
                crownRadius = 8,
                hangingLeaves = null,
                decorations = emptyList(),
            ),
        )
        assertTrue(
            FeatureLibraryValidator.validate(library(sunkenCrown)).any { it.code == "TREE_CROWN_EXCEEDS_HEIGHT" },
        )
    }

    @Test
    fun `a material the recipe never reads is rejected rather than ignored`() {
        val extra = library(
            FeatureDefinition(
                "tuft", FeatureRecipe.GROUND_PATCH,
                materials = mapOf(
                    MaterialRole.BLOCK to selector("grass", "minecraft:short_grass"),
                    MaterialRole.FOLIAGE to selector("leaves", "minecraft:oak_leaves"),
                ),
                density = 0.3,
            ),
        )

        val codes = FeatureLibraryValidator.validate(extra).map { it.code }

        assertTrue("UNUSED_MATERIAL_ROLE" in codes, codes.toString())
    }

    @Test
    fun `the shorthand and the map are the same thing and cannot both be written`() {
        val shorthand = FeatureDefinition(
            "tuft", FeatureRecipe.GROUND_PATCH, selector("grass", "minecraft:short_grass"), density = 0.3,
        )
        val explicit = FeatureDefinition(
            "tuft", FeatureRecipe.GROUND_PATCH,
            materials = mapOf(MaterialRole.BLOCK to selector("grass", "minecraft:short_grass")),
            density = 0.3,
        )

        assertEquals(shorthand.allMaterials, explicit.allMaterials)
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(library(shorthand)))
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(library(explicit)))

        val both = FeatureDefinition(
            "tuft", FeatureRecipe.GROUND_PATCH, selector("grass", "minecraft:short_grass"),
            materials = mapOf(MaterialRole.BLOCK to selector("other", "minecraft:fern")),
            density = 0.3,
        )
        assertTrue(FeatureLibraryValidator.validate(library(both)).any { it.code == "AMBIGUOUS_MATERIALS" })
    }

    @Test
    fun `a meadow may be several plants rather than one repeated`() {
        val mixed = library(
            FeatureDefinition(
                "meadow", FeatureRecipe.GROUND_PATCH,
                MaterialSelector(
                    semanticRole = "meadow_flora",
                    weighted = listOf(
                        WeightedMaterial(selector("grass", "minecraft:short_grass"), 8),
                        WeightedMaterial(selector("dandelion", "minecraft:dandelion"), 2),
                    ),
                ),
                density = 0.4,
            ),
        )

        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(mixed))
    }

    @Test
    fun `a weighted list is refused where Minecraft only takes one block state`() {
        // An ore vein's configuration holds a BlockState, not a provider, so the
        // list would quietly become its first entry.
        val vein = library(
            FeatureDefinition(
                "seam", FeatureRecipe.ORE_VEIN,
                MaterialSelector(
                    semanticRole = "seam",
                    weighted = listOf(
                        WeightedMaterial(selector("iron", "minecraft:iron_ore"), 3),
                        WeightedMaterial(selector("gold", "minecraft:gold_ore"), 1),
                    ),
                ),
                density = 0.3,
            ),
        )

        assertTrue(FeatureLibraryValidator.validate(vein).any { it.code == "WEIGHTED_NOT_SUPPORTED" })
    }

    @Test
    fun `a weighted selector carries no material of its own and does not nest`() {
        val confused = library(
            FeatureDefinition(
                "muddle", FeatureRecipe.GROUND_PATCH,
                MaterialSelector(
                    semanticRole = "muddle",
                    preferredIds = listOf("minecraft:short_grass"),
                    weighted = listOf(
                        WeightedMaterial(
                            MaterialSelector(
                                semanticRole = "nested",
                                weighted = listOf(WeightedMaterial(selector("fern", "minecraft:fern"))),
                            ),
                        ),
                    ),
                ),
                density = 0.3,
            ),
        )

        val codes = FeatureLibraryValidator.validate(confused).map { it.code }

        assertTrue("AMBIGUOUS_MATERIAL" in codes, codes.toString())
        assertTrue("NESTED_WEIGHTED_MATERIAL" in codes, codes.toString())
    }

    private fun library(vararg features: FeatureDefinition) = FeatureLibrary(features = features.toList())

    private fun tree(silhouette: TreeSilhouette) =
        TreeSpec(silhouette, TreeDistribution.GROVE, TreeSubstrate.NATURAL_SOIL)

    private fun selector(role: String, id: String) = MaterialSelector(role, listOf(id))
}
