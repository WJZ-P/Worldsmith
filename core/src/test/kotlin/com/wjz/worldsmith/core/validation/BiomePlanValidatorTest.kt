package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.AmbientParticleSpec
import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomeBehavior
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomeFog
import com.wjz.worldsmith.core.model.BiomeSky
import com.wjz.worldsmith.core.model.BiomeTint
import com.wjz.worldsmith.core.model.BiomeFeatureRef
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.BiomeSpatialSettings
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.ClimatePlacement
import com.wjz.worldsmith.core.model.ClimateSlot
import com.wjz.worldsmith.core.model.FeatureDefinition
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.HumidityBand
import com.wjz.worldsmith.core.model.MaterialRole
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.NumericRange
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.SurfaceAltitude
import com.wjz.worldsmith.core.model.SurfaceAnchorBand
import com.wjz.worldsmith.core.model.SurfaceConditions
import com.wjz.worldsmith.core.model.SurfaceDefinition
import com.wjz.worldsmith.core.model.SurfaceLayer
import com.wjz.worldsmith.core.model.SurfaceNoise
import com.wjz.worldsmith.core.model.SurfaceNoiseBand
import com.wjz.worldsmith.core.model.SurfaceRuleDefinition
import com.wjz.worldsmith.core.model.SurfaceStack
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.TreeSpec
import com.wjz.worldsmith.core.model.TreeDistribution
import com.wjz.worldsmith.core.model.TreeSubstrate
import com.wjz.worldsmith.core.model.TreeHeight
import com.wjz.worldsmith.core.model.TreeBranchSpec
import com.wjz.worldsmith.core.model.TreeTrunkShape
import com.wjz.worldsmith.core.model.TreeTrunkSpec
import com.wjz.worldsmith.core.model.TreeCrownShape
import com.wjz.worldsmith.core.model.TreeCrownSpec
import com.wjz.worldsmith.core.model.WaterFog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiomePlanValidatorTest {
    @Test
    fun `biome spatial controls have explicit generation bounds`() {
        val broken = plan().copy(
            spatial = BiomeSpatialSettings(regionScale = 9.0, boundaryRoughness = -0.1),
        )

        val codes = BiomePlanValidator.validate(broken, library()).map { it.code }.toSet()

        assertTrue("BIOME_REGION_SCALE_OUT_OF_RANGE" in codes)
        assertTrue("BIOME_BOUNDARY_ROUGHNESS_OUT_OF_RANGE" in codes)
    }

    @Test
    fun `archetype and placement report contradictory land semantics`() {
        val contradictorySlot = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    if (biome.id == "abyss") biome.copy(slot = ClimateSlot(ReliefBand.FLATS)) else biome
                },
            )
        }
        val contradictoryRaw = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    if (biome.id == "flats_cold") {
                        biome.copy(
                            slot = null,
                            climate = ClimateBox(
                                continentalness = NumericRange(-1.0f, -0.5f),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        assertTrue(
            BiomePlanValidator.validate(contradictorySlot, library())
                .any { it.code == "ARCHETYPE_LAND_WATER_MISMATCH" },
        )
        assertTrue(
            BiomePlanValidator.validate(contradictoryRaw, library())
                .any { it.code == "ARCHETYPE_LAND_WATER_MISMATCH" },
        )
    }

    @Test
    fun `raw archetype overlap must have positive width rather than touch one band endpoint`() {
        val mismatched = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    when (biome.id) {
                        "abyss" -> biome.copy(
                            slot = null,
                            climate = ClimateBox(continentalness = NumericRange(-0.455f, -0.19f)),
                        )
                        "highland_dry" -> biome.copy(
                            slot = null,
                            climate = ClimateBox(
                                continentalness = NumericRange(-0.11f, 1.0f),
                                erosion = NumericRange(0.05f, 1.0f),
                            ),
                        )
                        else -> biome
                    }
                },
            )
        }

        val mismatches = BiomePlanValidator.validate(mismatched, library())
            .filter { it.code == "ARCHETYPE_RELIEF_MISMATCH" }

        assertTrue(mismatches.size >= 2, mismatches.toString())
    }

    @Test
    fun `climate derived tints may be omitted while authored dry foliage is validated`() {
        val climateTint = plan().let { source ->
            source.copy(
                biomes = source.biomes.mapIndexed { index, biome ->
                    if (index == 0) {
                        biome.copy(
                            environment = biome.environment.copy(
                                tint = biome.environment.tint.copy(grass = null, foliage = null),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }
        val badDryFoliage = climateTint.let { source ->
            source.copy(
                biomes = source.biomes.mapIndexed { index, biome ->
                    if (index == 0) {
                        biome.copy(
                            environment = biome.environment.copy(
                                tint = biome.environment.tint.copy(dryFoliage = "C0FFEE"),
                            ),
                        )
                    } else {
                        biome
                    }
                },
            )
        }

        assertTrue(BiomePlanValidator.validate(climateTint, library()).none { it.severity == DiagnosticSeverity.ERROR })
        assertTrue(
            BiomePlanValidator.validate(badDryFoliage, library())
                .any { it.path.endsWith("dryFoliage") && it.code == "INVALID_COLOR" },
        )
    }

    @Test
    fun `a biome uses the placements list or the shorthand but not both`() {
        val mixed = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    if (biome.id != "flats_cold") {
                        biome
                    } else {
                        biome.copy(
                            placements = listOf(ClimatePlacement(slot = ClimateSlot(ReliefBand.FLATS))),
                        )
                    }
                },
            )
        }

        // Two ways of saying where a biome goes, disagreeing. Silently picking
        // one is how a pack generates a world its own document denies.
        assertTrue(BiomePlanValidator.validate(mixed, library()).any { it.code == "AMBIGUOUS_CLIMATE" })
    }

    @Test
    fun `a placement that names neither a slot nor a box is rejected`() {
        val empty = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    if (biome.id != "flats_cold") {
                        biome
                    } else {
                        biome.copy(slot = null, placements = listOf(ClimatePlacement()))
                    }
                },
            )
        }

        assertTrue(BiomePlanValidator.validate(empty, library()).any { it.code == "MISSING_CLIMATE" })
    }

    @Test
    fun `a relief band held by one biome is reported as a contour line`() {
        val monotone = plan().let { source ->
            source.copy(
                biomes = source.biomes.filterNot { it.id.startsWith("flats_") } +
                    biome("flats", ClimateSlot(ReliefBand.FLATS), BiomeArchetypeRole.LOWLAND)
                        .copy(features = listOf(BiomeFeatureRef("dead_trunk"))),
            )
        }

        val diagnostics = BiomePlanValidator.validate(monotone, library())

        // The partition is still perfect - every square claimed exactly once -
        // which is the whole point: a plan can score full marks on coverage and
        // still hand the player one biome per altitude.
        assertTrue(diagnostics.none { it.code == "UNCLAIMED_CLIMATE_CELL" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.code == "OVERLAPPING_CLIMATE_CELL" }, diagnostics.toString())
        assertTrue(diagnostics.any { it.code == "MONOTONE_RELIEF_BAND" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
    }

    @Test
    fun `open water is exempt because nobody walks across it`() {
        // The fixture deliberately holds one deep-water and one shallow-water
        // biome. Sameness underwater is a decision; sameness on the plains is
        // the failure this check exists for.
        val diagnostics = BiomePlanValidator.validate(plan(), library())

        assertTrue(diagnostics.none { it.code == "MONOTONE_RELIEF_BAND" }, diagnostics.toString())
    }

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

    @Test
    fun `an anchor is a complete surface condition by itself`() {
        val original = plan()
        val anchorRule = SurfaceRuleDefinition(
            id = "summit",
            conditions = SurfaceConditions(anchor = SurfaceAnchorBand("holy_peak", min = 0.7, max = 1.0)),
            stack = original.biomes.first().surface.base,
        )
        val anchored = original.copy(
            biomes = original.biomes.mapIndexed { index, biome ->
                if (index == 0) biome.copy(surface = biome.surface.copy(rules = listOf(anchorRule))) else biome
            },
        )

        val diagnostics = BiomePlanValidator.validate(anchored, library())

        assertTrue(diagnostics.none { it.code == "EMPTY_SURFACE_CONDITIONS" }, diagnostics.toString())
    }

    /**
     * Minecraft survival begins by punching a tree. A world with no wood cannot
     * be played at all, and nothing about it looks broken from the outside, so
     * it has to be said out loud.
     */
    @Test
    fun `a world with no wood is reported as unplayable`() {
        val woodless = library().let { it.copy(features = it.features.filterNot { f -> f.id == "dead_trunk" }) }
        val plan = plan().let {
            it.copy(biomes = it.biomes.map { biome -> biome.copy(features = emptyList()) })
        }

        val codes = BiomePlanValidator.validate(plan, woodless).map { it.code }

        assertTrue("NO_WOOD_IN_WORLD" in codes, codes.toString())
    }

    @Test
    fun `wood that only grows at sea does not count`() {
        val afloat = plan().let { source ->
            source.copy(
                biomes = source.biomes.map { biome ->
                    when {
                        biome.id == "abyss" -> biome.copy(features = listOf(BiomeFeatureRef("dead_trunk")))
                        else -> biome.copy(features = emptyList())
                    }
                },
            )
        }

        val diagnostics = BiomePlanValidator.validate(afloat, library())

        assertTrue(diagnostics.any { it.code == "NO_WOOD_ON_LAND" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
    }

    @Test
    fun `a living tree on land satisfies the structural wood check`() {
        val tree = FeatureDefinition(
            "living_tree",
            FeatureRecipe.TREE,
            materials = mapOf(
                MaterialRole.TRUNK to material("wood", "minecraft:cherry_log"),
                MaterialRole.FOLIAGE to material("leaves", "minecraft:cherry_leaves"),
            ),
            density = 0.3,
            tree = TreeSpec(
                TreeTrunkSpec(TreeTrunkShape.BRANCHING, TreeHeight(8, 11), branches = TreeBranchSpec(3, 4, 0.55)),
                TreeCrownSpec(TreeCrownShape.CLUSTERED, radius = 3, height = 4),
                TreeDistribution.GROVE,
                TreeSubstrate.NATURAL_SOIL,
            ),
        )
        val features = library().copy(features = library().features + tree)
        val living = plan().copy(
            biomes = plan().biomes.mapIndexed { index, biome ->
                if (index == 2) biome.copy(features = listOf(BiomeFeatureRef("living_tree"))) else biome
            },
        )

        val codes = BiomePlanValidator.validate(living, features).map { it.code }

        assertTrue("NO_WOOD_IN_WORLD" !in codes, codes.toString())
        assertTrue("NO_WOOD_ON_LAND" !in codes, codes.toString())
    }

    @Test
    fun `a nominal tree too rare for a reliable start is reported`() {
        val sparse = plan().copy(
            biomes = plan().biomes.map { biome ->
                if (biome.id == "flats_temperate") {
                    biome.copy(features = listOf(BiomeFeatureRef("dead_trunk", 0.05)))
                } else {
                    biome.copy(features = emptyList())
                }
            },
        )

        assertTrue(BiomePlanValidator.validate(sparse, library()).any { it.code == "NO_WOOD_ON_LAND" })
    }

    private fun library() = FeatureLibrary(
        features = listOf(
            FeatureDefinition("ash_scrub", FeatureRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), density = 0.45),
            FeatureDefinition("second_scrub", FeatureRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), density = 0.45),
            FeatureDefinition("third_scrub", FeatureRecipe.GROUND_PATCH, material("dry_scrub", "minecraft:dead_bush"), density = 0.45),
            FeatureDefinition("dead_trunk", FeatureRecipe.DEAD_TREE, material("dead_wood", "minecraft:stripped_spruce_log"), density = 0.2),
        ),
    )

    private fun plan() = BiomePlan(
        biomes = listOf(
            biome("abyss", ClimateSlot(ReliefBand.DEEP_WATER), BiomeArchetypeRole.DEEP_OCEAN),
            biome("shallows", ClimateSlot(ReliefBand.SHALLOW_WATER), BiomeArchetypeRole.OCEAN),
            biome("shore_dry", coast(HumidityBand.ARID), BiomeArchetypeRole.BEACH),
            biome("shore_wet", coast(HumidityBand.HUMID), BiomeArchetypeRole.BEACH),
            biome("peaks_dry", peaks(HumidityBand.ARID), BiomeArchetypeRole.MOUNTAIN),
            biome("peaks_wet", peaks(HumidityBand.HUMID), BiomeArchetypeRole.MOUNTAIN),
            biome("highland_dry", highland(HumidityBand.ARID), BiomeArchetypeRole.HILL),
            biome("highland_wet", highland(HumidityBand.HUMID), BiomeArchetypeRole.HILL),
            biome("flats_cold", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.COLD)), BiomeArchetypeRole.LOWLAND),
            // Carries the wood a player needs to craft, so the fixture is a
            // world someone could actually start in rather than only a valid
            // document.
            biome("flats_temperate", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.TEMPERATE)), BiomeArchetypeRole.LOWLAND)
                .copy(features = listOf(BiomeFeatureRef("dead_trunk"))),
            biome("flats_hot", ClimateSlot(ReliefBand.FLATS, listOf(TemperatureBand.HOT)), BiomeArchetypeRole.LOWLAND),
        ),
    )

    @Test
    fun `a glowing particle is reported at a rate that is fine for ash`() {
        // The rate a model reaches for is inside vanilla's range; what makes it
        // unbearable is which particle is drawn at it.
        val diagnostics = BiomePlanValidator.validate(
            withParticle("minecraft:end_rod", 0.018f),
            library(),
        )

        assertTrue(diagnostics.any { it.code == "OBTRUSIVE_AMBIENT_PARTICLE" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
    }

    @Test
    fun `a small dim particle may use the whole vanilla range`() {
        // basalt deltas runs white ash at 0.118, and the built-in pack goes
        // further still; an ash storm is a decision, not an accident.
        val diagnostics = BiomePlanValidator.validate(
            withParticle("minecraft:white_ash", 0.118f),
            library(),
        )

        assertTrue(diagnostics.none { it.code.endsWith("_AMBIENT_PARTICLE") }, diagnostics.toString())
    }

    @Test
    fun `a wall of particles is reported whichever particle it is made of`() {
        val diagnostics = BiomePlanValidator.validate(withParticle("minecraft:ash", 0.6f), library())

        assertTrue(diagnostics.any { it.code == "DENSE_AMBIENT_PARTICLE" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
    }

    private fun withParticle(particle: String, probability: Float) = plan().let { source ->
        source.copy(
            biomes = source.biomes.map { biome ->
                if (biome.id != "flats_temperate") {
                    biome
                } else {
                    biome.copy(
                        environment = biome.environment.copy(
                            ambientParticles = listOf(AmbientParticleSpec(particle, probability)),
                        ),
                    )
                }
            },
        )
    }

    private fun coast(humidity: HumidityBand) = ClimateSlot(ReliefBand.COAST, humidity = listOf(humidity))

    private fun peaks(humidity: HumidityBand) = ClimateSlot(ReliefBand.PEAKS, humidity = listOf(humidity))

    private fun highland(humidity: HumidityBand) = ClimateSlot(ReliefBand.HIGHLAND, humidity = listOf(humidity))

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
