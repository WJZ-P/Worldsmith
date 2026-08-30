package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomeBehavior
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomeFeatureRef
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.ClimateSlot
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.SurfaceLayers
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.model.VegetationRecipe
import com.wjz.worldsmith.core.model.WaterFog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiomePlanValidatorTest {
    @Test
    fun `a plan using semantic slots reports no diagnostics`() {
        assertTrue(BiomePlanValidator.validate(plan(), library()).isEmpty())
    }

    @Test
    fun `a prompt may intentionally leave semantic bands undeclared`() {
        val incomplete = plan().let { it.copy(biomes = it.biomes.filterNot { biome -> biome.id == "flats_hot" }) }

        assertTrue(BiomePlanValidator.validate(incomplete, library()).isEmpty())
    }

    @Test
    fun `overlapping semantic slots are not treated as a global quota violation`() {
        val overlapping = plan().let {
            it.copy(biomes = it.biomes + biome("second_shore", ClimateSlot(ReliefBand.COAST), BiomeArchetypeRole.BEACH))
        }

        assertTrue(BiomePlanValidator.validate(overlapping, library()).isEmpty())
    }

    @Test
    fun `a raw climate box is a first class placement`() {
        val raw = plan().let {
            it.copy(
                biomes = it.biomes.map { biome ->
                    if (biome.id == "abyss") biome.copy(slot = null, climate = ClimateBox()) else biome
                },
            )
        }

        assertTrue(BiomePlanValidator.validate(raw, library()).isEmpty())
    }

    @Test
    fun `declaring both a slot and a raw climate is rejected`() {
        val ambiguous = plan().let {
            it.copy(biomes = it.biomes.map { biome -> if (biome.id == "abyss") biome.copy(climate = ClimateBox()) else biome })
        }

        assertTrue(BiomePlanValidator.validate(ambiguous, library()).any { it.code == "AMBIGUOUS_CLIMATE" })
    }

    @Test
    fun `declaring neither a slot nor a raw climate is rejected`() {
        val missing = plan().let {
            it.copy(biomes = it.biomes.map { biome -> if (biome.id == "abyss") biome.copy(slot = null) else biome })
        }

        assertTrue(BiomePlanValidator.validate(missing, library()).any { it.code == "MISSING_CLIMATE" })
    }

    @Test
    fun `bands with a gap between them are reported`() {
        val gapped = plan().let {
            it.copy(
                biomes = it.biomes.map { biome ->
                    if (biome.id == "flats_cold") {
                        biome.copy(slot = ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.COLD, TemperatureBand.HOT)))
                    } else {
                        biome
                    }
                },
            )
        }

        val codes = BiomePlanValidator.validate(gapped, library()).map { it.code }

        assertTrue("NON_CONTIGUOUS_BANDS" in codes)
    }

    @Test
    fun `a reference to an undeclared feature is reported`() {
        val dangling = plan().let {
            it.copy(
                biomes = it.biomes.map { biome ->
                    if (biome.id == "abyss") biome.copy(features = listOf(BiomeFeatureRef("kelp_forest"))) else biome
                },
            )
        }

        assertTrue(BiomePlanValidator.validate(dangling, library()).any { it.code == "UNKNOWN_FEATURE" })
    }

    @Test
    fun `a biome that overspends its per chunk vegetation budget is reported`() {
        val greedy = plan().let {
            it.copy(
                biomes = it.biomes.map { biome ->
                    if (biome.id == "abyss") {
                        biome.copy(
                            features = listOf(
                                BiomeFeatureRef("ash_scrub", 1.0),
                                BiomeFeatureRef("second_scrub", 1.0),
                                BiomeFeatureRef("third_scrub", 1.0),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        assertTrue(BiomePlanValidator.validate(greedy, library()).any { it.code == "VEGETATION_BUDGET_EXCEEDED" })
    }

    @Test
    fun `malformed environment values are reported`() {
        val broken = plan().let {
            it.copy(
                biomes = it.biomes.map { biome ->
                    if (biome.id == "abyss") {
                        biome.copy(
                            environment = biome.environment.copy(
                                skyColor = "8C7A63",
                                waterFog = WaterFog("#090C0D", 40.0f, 10.0f),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        val codes = BiomePlanValidator.validate(broken, library()).map { it.code }

        assertTrue("INVALID_COLOR" in codes)
        assertTrue("REVERSED_RANGE" in codes)
    }

    private fun library() = FeatureLibrary(
        features = listOf(
            FeatureDefinition("ash_scrub", VegetationRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), 0.45),
            FeatureDefinition("second_scrub", VegetationRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), 0.45),
            FeatureDefinition("third_scrub", VegetationRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), 0.45),
        ),
    )

    private fun plan() = BiomePlan(
        biomes = listOf(
            biome("abyss", ClimateSlot(ReliefBand.DEEP_WATER), BiomeArchetypeRole.DEEP_OCEAN),
            biome("shallows", ClimateSlot(ReliefBand.SHALLOW_WATER), BiomeArchetypeRole.OCEAN),
            biome("shore", ClimateSlot(ReliefBand.COAST), BiomeArchetypeRole.BEACH),
            biome("peaks", ClimateSlot(ReliefBand.PEAKS), BiomeArchetypeRole.MOUNTAIN),
            biome("highland", ClimateSlot(ReliefBand.HIGHLAND), BiomeArchetypeRole.HILL),
            biome("flats_cold", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.COLD)), BiomeArchetypeRole.LOWLAND),
            biome("flats_temperate", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.TEMPERATE)), BiomeArchetypeRole.LOWLAND),
            biome("flats_hot", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.HOT)), BiomeArchetypeRole.LOWLAND),
        ),
    )

    private fun biome(id: String, slot: ClimateSlot, archetype: BiomeArchetypeRole) = BiomeDefinition(
        id = id,
        displayName = id.replace('_', ' '),
        archetype = archetype,
        slot = slot,
        behavior = BiomeBehavior(temperature = 0.5f, downfall = 0.4f, hasPrecipitation = true),
        surface = SurfaceLayers(
            top = material("surface_top", "minecraft:coarse_dirt"),
            under = material("surface_under", "minecraft:dirt"),
            deep = material("surface_deep", "minecraft:tuff"),
        ),
        environment = BiomeEnvironment(
            grassColor = "#7A6C55",
            foliageColor = "#6B5F49",
            waterColor = "#4A5340",
            skyColor = "#8C7A63",
            fogColor = "#9C8A73",
        ),
    )

    private fun material(role: String, id: String) = MaterialSelector(semanticRole = role, preferredIds = listOf(id))
}
