package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.feature.VegetationBudget
import com.wjz.worldsmith.core.model.BoulderSpec
import com.wjz.worldsmith.core.model.ColumnSpec
import com.wjz.worldsmith.core.model.FallenLogSpec
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureFluid
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.FeaturePatchSpec
import com.wjz.worldsmith.core.model.FeaturePlacementConditions
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.FeatureSubstrate
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.OreVeinSpec
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureShapeAndPlacementTest {
    @Test
    fun `non-tree geometry and placement survive json exactly`() {
        val features = listOf(
            feature(FeatureRecipe.GROUND_PATCH).copy(
                patch = FeaturePatchSpec(7, 4),
                placement = FeaturePlacementConditions(72, 140, FeatureSubstrate.NATURAL_SOIL, FeatureFluid.DRY),
            ),
            feature(FeatureRecipe.BOULDER).copy(boulder = BoulderSpec(4, 3)),
            feature(FeatureRecipe.ORE_VEIN).copy(
                oreVein = OreVeinSpec(48, 0.6),
                placement = FeaturePlacementConditions(-48, 24),
            ),
            feature(FeatureRecipe.CAVE_PATCH).copy(patch = FeaturePatchSpec(5, 3, 2, 20)),
            feature(FeatureRecipe.HANGING_PATCH).copy(
                patch = FeaturePatchSpec(4, 2, 1, 18),
                column = ColumnSpec(3, 11),
            ),
            feature(FeatureRecipe.DEAD_TREE).copy(column = ColumnSpec(4, 9)),
            feature(FeatureRecipe.FALLEN_LOG).copy(fallenLog = FallenLogSpec(7, 13)),
        )
        val library = FeatureLibrary(features = features)

        val decoded = WorldsmithJson.decode<FeatureLibrary>(WorldsmithJson.encode(library))

        assertEquals(library, decoded)
        assertEquals(emptyList<Diagnostic>(), FeatureLibraryValidator.validate(decoded))
    }

    @Test
    fun `recipe-specific controls reject wrong owners and impossible bounds`() {
        val malformed = feature(FeatureRecipe.GROUND_PATCH).copy(
            patch = FeaturePatchSpec(40, 12, 3, 40),
            boulder = BoulderSpec(0, 20),
            oreVein = OreVeinSpec(80, 2.0),
            column = ColumnSpec(12, 2),
            fallenLog = FallenLogSpec(0, 20),
            placement = FeaturePlacementConditions(300, -100),
        )

        val codes = FeatureLibraryValidator.validate(FeatureLibrary(features = listOf(malformed))).map { it.code }

        assertTrue("PATCH_ATTEMPTS_OUT_OF_RANGE" in codes, codes.toString())
        assertTrue("PATCH_SPREAD_OUT_OF_RANGE" in codes, codes.toString())
        assertTrue("UNUSED_PATCH_VERTICAL_SPREAD" in codes, codes.toString())
        assertTrue("UNUSED_BOULDER_SPEC" in codes, codes.toString())
        assertTrue("UNUSED_ORE_VEIN_SPEC" in codes, codes.toString())
        assertTrue("UNUSED_COLUMN_SPEC" in codes, codes.toString())
        assertTrue("UNUSED_FALLEN_LOG_SPEC" in codes, codes.toString())
        assertTrue("REVERSED_FEATURE_HEIGHT" in codes, codes.toString())
    }

    @Test
    fun `ids and tags are namespaced identifiers`() {
        val malformed = FeatureDefinition(
            "bad_material", FeatureRecipe.GROUND_PATCH,
            block = MaterialSelector(
                semanticRole = "flora",
                preferredIds = listOf("stone", "minecraft:short grass"),
                requiredTags = listOf("#minecraft:dirt", "Bad:tag"),
            ),
            density = 0.2,
        )

        val codes = FeatureLibraryValidator.validate(FeatureLibrary(features = listOf(malformed))).map { it.code }

        assertEquals(2, codes.count { it == "INVALID_BLOCK_ID" })
        assertEquals(2, codes.count { it == "INVALID_BLOCK_TAG_ID" })
    }

    @Test
    fun `cluster geometry is charged to the biome budget`() {
        val one = feature(FeatureRecipe.CAVE_PATCH)
        val cluster = one.copy(patch = FeaturePatchSpec(attempts = 8, horizontalSpread = 3))
        val boulder = feature(FeatureRecipe.BOULDER).copy(boulder = BoulderSpec(blobs = 6, spread = 2))

        assertEquals(
            VegetationBudget.attemptsPerChunk(one, 0.5) * 8,
            VegetationBudget.attemptsPerChunk(cluster, 0.5),
        )
        assertEquals(6.0 / VegetationBudget.rarity(0.5), VegetationBudget.attemptsPerChunk(boulder, 0.5))
    }

    private fun feature(recipe: FeatureRecipe) = FeatureDefinition(
        id = "sample_${recipe.name.lowercase()}",
        recipe = recipe,
        block = MaterialSelector("material", listOf("minecraft:stone")),
        density = 0.35,
    )
}
