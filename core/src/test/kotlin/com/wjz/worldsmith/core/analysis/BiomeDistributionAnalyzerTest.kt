package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomeBehavior
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomeFog
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.BiomeSpatialSettings
import com.wjz.worldsmith.core.model.BiomeSky
import com.wjz.worldsmith.core.model.BiomeTint
import com.wjz.worldsmith.core.model.ClimatePlacement
import com.wjz.worldsmith.core.model.ClimateSlot
import com.wjz.worldsmith.core.model.HumidityBand
import com.wjz.worldsmith.core.model.HydrologyIntent
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.ReliefDistribution
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.SurfaceDefinition
import com.wjz.worldsmith.core.model.SurfaceLayer
import com.wjz.worldsmith.core.model.SurfaceStack
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiomeDistributionAnalyzerTest {
    private val terrain = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").terrain

    @Test
    fun `bands that look like mirrors of each other are not the same size`() {
        // The whole reason this tool exists. HOT spans 0.55 to 1.0 and COLD
        // spans -1.0 to -0.15; they read as a symmetric pair in the contract,
        // and the temperature field's measured sigma of 0.383 makes one of them
        // several times the other. Nothing in the document says so.
        val report = BiomeDistributionAnalyzer.analyze(
            plan(
                biome("cold_flats", ReliefBand.FLATS, TemperatureBand.COLD),
                biome("temperate_flats", ReliefBand.FLATS, TemperatureBand.TEMPERATE),
                biome("hot_flats", ReliefBand.FLATS, TemperatureBand.HOT),
            ),
            terrain,
        )

        val shares = report.biomes.associate { it.id to it.share }
        assertTrue(
            shares.getValue("cold_flats") > shares.getValue("hot_flats") * 2,
            "cold should dwarf hot but was $shares",
        )
        assertTrue(shares.getValue("temperate_flats") > shares.getValue("cold_flats"), shares.toString())
    }

    @Test
    fun `a biome no sample ever lands in is named`() {
        // hidden_flats claims exactly what plain_flats claims. Minecraft breaks
        // the tie the same way every time, so one of them generates nowhere -
        // a failure with no error anywhere and nothing to see in the world.
        val report = BiomeDistributionAnalyzer.analyze(
            plan(
                biome("plain_flats", ReliefBand.FLATS),
                biome("hidden_flats", ReliefBand.FLATS),
                biome("ocean", ReliefBand.DEEP_WATER),
            ),
            terrain,
        )

        assertEquals(1, report.absent.size, report.biomes.toString())
        assertTrue(report.absent.single() in listOf("plain_flats", "hidden_flats"))
        assertTrue(report.notes.any { "never chosen" in it })
    }

    @Test
    fun `the land share follows the terrain document rather than the biome list`() {
        val oceanic = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), terrainWithLandRatio(0.2))
        val continental = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), terrainWithLandRatio(0.85))

        // The coastline is a property of the terrain compiler's calibration, so
        // the report has to move with it or it is describing a different world
        // than the one the pack will generate.
        assertTrue(oceanic.landShare in 0.15..0.27, "oceanic land share was ${oceanic.landShare}")
        assertTrue(continental.landShare in 0.78..0.92, "continental land share was ${continental.landShare}")
    }

    @Test
    fun `fluid rivers and lakes contribute to the reported aquatic share`() {
        val shape = terrain.shape as com.wjz.worldsmith.core.model.TerrainShape.Procedural
        val dry = terrain.copy(
            shape = shape.copy(
                hydrology = shape.hydrology.copy(
                    riverCoverage = 0.0,
                    lakeDensity = 0.0,
                ),
            ),
        )
        val wet = terrain.copy(
            shape = shape.copy(
                hydrology = HydrologyIntent(
                    riverCoverage = 0.35,
                    riverWidth = 1.0,
                    riverDepth = 0.8,
                    riverMeander = 0.6,
                    riverFill = RiverFill.FLUID,
                    lakeDensity = 0.35,
                    lakeScale = 1.0,
                    lakeDepth = 0.8,
                    oceanDepth = 1.0,
                ),
            ),
        )

        val dryReport = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), dry)
        val wetReport = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), wet)

        assertTrue(wetReport.landShare < dryReport.landShare - 0.15, "$dryReport vs $wetReport")
        assertTrue(wetReport.notes.any { "aquatic share" in it })
    }

    @Test
    fun `land biome shares follow the authored relief mixture`() {
        val shape = terrain.shape as com.wjz.worldsmith.core.model.TerrainShape.Procedural
        val authored = terrain.copy(
            shape = shape.copy(relief = ReliefDistribution(flats = 0.20, highlands = 0.30, peaks = 0.50)),
        )

        val report = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), authored)
        val shares = report.biomes.associate { it.id to it.share }
        val inland = shares.getValue("flats") + shares.getValue("highland") + shares.getValue("peaks")

        assertTrue(kotlin.math.abs(shares.getValue("flats") / inland - 0.20) < 0.04, shares.toString())
        assertTrue(kotlin.math.abs(shares.getValue("highland") / inland - 0.30) < 0.04, shares.toString())
        assertTrue(kotlin.math.abs(shares.getValue("peaks") / inland - 0.50) < 0.04, shares.toString())
    }

    @Test
    fun `one biome may hold two regions that are not neighbours`() {
        val split = BiomeDistributionAnalyzer.analyze(
            plan(
                BiomeDefinition(
                    id = "twin_shore",
                    displayName = "twin shore",
                    archetype = BiomeArchetypeRole.BEACH,
                    placements = listOf(
                        ClimatePlacement(slot = ClimateSlot(ReliefBand.COAST, listOf(TemperatureBand.COLD))),
                        ClimatePlacement(slot = ClimateSlot(ReliefBand.COAST, listOf(TemperatureBand.HOT))),
                    ),
                    behavior = BiomeBehavior(0.5f, 0.4f, true),
                    surface = surface(),
                    environment = environment(),
                ),
                biome("inland", ReliefBand.FLATS),
                biome("ocean", ReliefBand.DEEP_WATER),
            ),
            terrain,
        )

        val cold = BiomeDistributionAnalyzer.analyze(
            plan(
                biome("twin_shore", ReliefBand.COAST, TemperatureBand.COLD),
                biome("inland", ReliefBand.FLATS),
                biome("ocean", ReliefBand.DEEP_WATER),
            ),
            terrain,
        )

        val splitShare = split.biomes.single { it.id == "twin_shore" }.share
        val coldShare = cold.biomes.single { it.id == "twin_shore" }.share
        assertTrue(splitShare > coldShare, "two regions should cover more than one: $splitShare vs $coldShare")
    }

    @Test
    fun `the same pack always reports the same numbers`() {
        val first = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), terrain)
        val second = BiomeDistributionAnalyzer.analyze(plan(*everyRelief()), terrain)

        // A report an author compares against a previous run has to be stable,
        // or every edit looks like it changed something.
        assertEquals(first.biomes, second.biomes)
        assertEquals(first.borders, second.borders)
    }

    @Test
    fun `spatial controls are not misreported as changing marginal shares`() {
        val plan = plan(*everyRelief()).copy(
            spatial = BiomeSpatialSettings(regionScale = 3.0, boundaryRoughness = 0.6),
        )

        val report = BiomeDistributionAnalyzer.analyze(plan, terrain)

        assertTrue(report.notes.any { "patch diameter" in it && "not marginal climate shares" in it })
    }

    private fun everyRelief(): Array<BiomeDefinition> = ReliefBand.entries
        .map { biome(it.name.lowercase(), it) }
        .toTypedArray()

    private fun terrainWithLandRatio(landRatio: Double) = terrain.copy(
        shape = (terrain.shape as com.wjz.worldsmith.core.model.TerrainShape.Procedural).copy(landRatio = landRatio),
    )

    private fun plan(vararg biomes: BiomeDefinition) = BiomePlan(biomes = biomes.toList())

    private fun biome(
        id: String,
        relief: ReliefBand,
        temperature: TemperatureBand? = null,
        humidity: HumidityBand? = null,
    ) = BiomeDefinition(
        id = id,
        displayName = id,
        archetype = if (relief.isLand) BiomeArchetypeRole.LOWLAND else BiomeArchetypeRole.OCEAN,
        slot = ClimateSlot(
            relief,
            temperature?.let(::listOf).orEmpty(),
            humidity?.let(::listOf).orEmpty(),
        ),
        behavior = BiomeBehavior(0.5f, 0.4f, true),
        surface = surface(),
        environment = environment(),
    )

    private fun surface() = SurfaceDefinition(
        base = SurfaceStack(
            layers = listOf(SurfaceLayer(MaterialSelector("top", listOf("minecraft:dirt")), 1)),
            foundation = MaterialSelector("deep", listOf("minecraft:tuff")),
        ),
        rules = emptyList(),
    )

    private fun environment() = BiomeEnvironment(
        tint = BiomeTint(grass = "#7A6C55", foliage = "#6B5F49", water = "#4A5340"),
        fog = BiomeFog(color = "#9C8A73"),
        sky = BiomeSky(color = "#8C7A63"),
    )
}
