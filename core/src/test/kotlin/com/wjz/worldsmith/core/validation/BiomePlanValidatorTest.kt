package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomeBehavior
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomeFog
import com.wjz.worldsmith.core.model.BiomeSky
import com.wjz.worldsmith.core.model.BiomeTint
import com.wjz.worldsmith.core.model.BiomeFeatureRef
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.ClimateSlot
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.SurfaceAltitude
import com.wjz.worldsmith.core.model.SurfaceConditions
import com.wjz.worldsmith.core.model.SurfaceDefinition
import com.wjz.worldsmith.core.model.SurfaceLayer
import com.wjz.worldsmith.core.model.SurfaceNoise
import com.wjz.worldsmith.core.model.SurfaceNoiseBand
import com.wjz.worldsmith.core.model.SurfaceRuleDefinition
import com.wjz.worldsmith.core.model.SurfaceStack
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

        val diagnostics = BiomePlanValidator.validate(incomplete, library())

        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        assertTrue(diagnostics.any { it.code == "UNCLAIMED_CLIMATE_CELL" }, "the gap should still be reported")
    }

    @Test
    fun `overlapping semantic slots are not treated as a global quota violation`() {
        val overlapping = plan().let {
            it.copy(biomes = it.biomes + biome("second_shore", ClimateSlot(ReliefBand.COAST), BiomeArchetypeRole.BEACH))
        }

        val diagnostics = BiomePlanValidator.validate(overlapping, library())

        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        assertTrue(diagnostics.any { it.code == "OVERLAPPING_CLIMATE_CELL" }, "the clash should still be reported")
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

        val diagnostics = BiomePlanValidator.validate(raw, library())

        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        assertTrue(
            diagnostics.any { it.code == "CLIMATE_COVERAGE_UNPROVEN" },
            "a raw box means coverage cannot be checked, which the author should know",
        )
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
                                sky = biome.environment.sky.copy(
                                    color = "8C7A63",
                                    cloudColor = "#C0A090",
                                    starBrightness = 1.4f,
                                ),
                                fog = biome.environment.fog.copy(
                                    water = WaterFog("#090C0D", 40.0f, 10.0f),
                                ),
                                light = biome.environment.light.copy(skyFactor = -0.2f),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        val codes = BiomePlanValidator.validate(broken, library()).map { it.code }

        assertTrue("INVALID_COLOR" in codes, "a six-digit sky colour missing its hash is rejected")
        assertTrue("REVERSED_RANGE" in codes)
        // Cloud colour carries alpha, so a plain #RRGGBB is wrong there even
        // though the same string is valid everywhere else.
        assertEquals(2, codes.count { it == "INVALID_COLOR" })
        assertEquals(2, codes.count { it == "UNIT_RANGE_OUT_OF_BOUNDS" })
    }

    @Test
    fun `surface grammar reports invalid layers and conditions`() {
        val brokenRule = SurfaceRuleDefinition(
            id = "BAD RULE",
            conditions = SurfaceConditions(
                altitude = SurfaceAltitude(min = 200, max = 100),
                noise = SurfaceNoiseBand(SurfaceNoise.PATCH, min = 0.8, max = -0.8),
            ),
            stack = SurfaceStack(
                layers = listOf(SurfaceLayer(material("bad_layer", "minecraft:gravel"), 17)),
                foundation = material("foundation", "minecraft:stone"),
            ),
        )
        val broken = plan().let { plan ->
            plan.copy(
                biomes = plan.biomes.mapIndexed { index, biome ->
                    if (index == 0) {
                        biome.copy(
                            surface = SurfaceDefinition(
                                base = SurfaceStack(emptyList(), material("foundation", "minecraft:stone")),
                                rules = listOf(brokenRule, brokenRule),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        val codes = BiomePlanValidator.validate(broken, library()).map { it.code }.toSet()

        assertTrue("EMPTY_SURFACE_STACK" in codes)
        assertTrue("DUPLICATE_SURFACE_RULE" in codes)
        assertTrue("INVALID_SURFACE_RULE_ID" in codes)
        assertTrue("REVERSED_RANGE" in codes)
        assertTrue("LAYER_DEPTH_OUT_OF_RANGE" in codes)
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
        surface = SurfaceDefinition(
            base = SurfaceStack(
                layers = listOf(
                    SurfaceLayer(material("surface_top", "minecraft:coarse_dirt"), 1),
                    SurfaceLayer(material("surface_under", "minecraft:dirt"), 3),
                ),
                foundation = material("surface_deep", "minecraft:tuff"),
            ),
            rules = emptyList(),
        ),
        environment = BiomeEnvironment(
            tint = BiomeTint(grass = "#7A6C55", foliage = "#6B5F49", water = "#4A5340"),
            fog = BiomeFog(color = "#9C8A73"),
            sky = BiomeSky(color = "#8C7A63"),
        ),
    )

    /**
     * Coverage is reported, never enforced: a prompt asking for three biomes is
     * not wrong, but the author still has to know that everything else is
     * resolved by nearest neighbour rather than by them.
     */
    @Test
    fun `unclaimed climate squares are reported without blocking the pack`() {
        val sparse = plan().let { it.copy(biomes = it.biomes.take(1)) }

        val diagnostics = BiomePlanValidator.validate(sparse, library())
        val unclaimed = diagnostics.single { it.code == "UNCLAIMED_CLIMATE_CELL" }

        assertEquals(DiagnosticSeverity.WARNING, unclaimed.severity)
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        assertTrue("30 of 36" in unclaimed.message, unclaimed.message)
    }

    @Test
    fun `two biomes claiming one square are named`() {
        val clashing = plan().let { source ->
            val first = source.biomes.first()
            source.copy(biomes = source.biomes + first.copy(id = "abyss_twin", displayName = "Twin"))
        }

        val overlap = BiomePlanValidator.validate(clashing, library()).filter { it.code == "OVERLAPPING_CLIMATE_CELL" }

        assertTrue(overlap.isNotEmpty())
        assertTrue(overlap.all { it.severity == DiagnosticSeverity.WARNING })
        assertTrue(overlap.first().message.contains("abyss_twin"), overlap.first().message)
    }

    private fun material(role: String, id: String) = MaterialSelector(semanticRole = role, preferredIds = listOf(id))
}
