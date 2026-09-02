package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialRole
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.VegetationRecipe
import com.wjz.worldsmith.core.model.WeightedMaterial
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
                "oak", VegetationRecipe.TREE,
                materials = mapOf(MaterialRole.TRUNK to selector("wood", "minecraft:oak_log")),
                density = 0.2,
            ),
        )

        val codes = FeatureLibraryValidator.validate(missingFoliage).map { it.code }

        assertTrue("MISSING_MATERIAL_ROLE" in codes, codes.toString())
    }

    @Test
    fun `a material the recipe never reads is rejected rather than ignored`() {
        val extra = library(
            FeatureDefinition(
                "tuft", VegetationRecipe.GROUND_PATCH,
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
            "tuft", VegetationRecipe.GROUND_PATCH, selector("grass", "minecraft:short_grass"), density = 0.3,
        )
        val explicit = FeatureDefinition(
            "tuft", VegetationRecipe.GROUND_PATCH,
            materials = mapOf(MaterialRole.BLOCK to selector("grass", "minecraft:short_grass")),
            density = 0.3,
        )

        assertEquals(shorthand.allMaterials, explicit.allMaterials)
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(library(shorthand)))
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(library(explicit)))

        val both = FeatureDefinition(
            "tuft", VegetationRecipe.GROUND_PATCH, selector("grass", "minecraft:short_grass"),
            materials = mapOf(MaterialRole.BLOCK to selector("other", "minecraft:fern")),
            density = 0.3,
        )
        assertTrue(FeatureLibraryValidator.validate(library(both)).any { it.code == "AMBIGUOUS_MATERIALS" })
    }

    @Test
    fun `a meadow may be several plants rather than one repeated`() {
        val mixed = library(
            FeatureDefinition(
                "meadow", VegetationRecipe.GROUND_PATCH,
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
                "seam", VegetationRecipe.ORE_VEIN,
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
                "muddle", VegetationRecipe.GROUND_PATCH,
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

    private fun selector(role: String, id: String) = MaterialSelector(role, listOf(id))
}
