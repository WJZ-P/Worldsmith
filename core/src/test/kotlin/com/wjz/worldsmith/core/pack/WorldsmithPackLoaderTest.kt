package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.model.Anchor
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.BandEffect
import com.wjz.worldsmith.core.model.BandRegion
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.SurfaceAnchorBand
import com.wjz.worldsmith.core.model.SurfaceConditions
import com.wjz.worldsmith.core.model.SurfaceRuleDefinition
import com.wjz.worldsmith.core.model.TerrainBand
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.model.BiomeSpatialSettings
import com.wjz.worldsmith.core.model.VanillaNoisePreset
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorldsmithPackLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `built in ashlands pack loads and validates`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")

        assertEquals(pack.computedId, pack.manifest.id)
        assertEquals(16, pack.biomes.biomes.size)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }

    @Test
    fun `every referenced feature is declared once in the library`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val declared = pack.features.features.map { it.id }
        val referenced = pack.biomes.biomes.flatMap { biome -> biome.features.map { it.feature } }

        assertEquals(declared.size, declared.toSet().size)
        assertTrue(declared.containsAll(referenced))
        assertTrue(referenced.size > declared.size, "the fixture should reuse at least one feature across biomes")
    }

    @Test
    fun `display metadata does not change generation identity`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val renamed = pack.copy(manifest = pack.manifest.copy(displayName = "Renamed Ashlands", description = "Edited description"))

        val diagnostics = WorldsmithPackValidator.validate(renamed)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `pack validator rejects terrain outside dimension bounds`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(terrain = it.terrain.copy(seaLevel = 900)) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "terrain.seaLevel" && it.code == "SEA_LEVEL_OUT_OF_RANGE" })
    }

    @Test
    fun `vanilla passthrough rejects procedural biome spatial controls`() {
        val original = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val pack = original.copy(
            terrain = original.terrain.copy(shape = TerrainShape.Vanilla(VanillaNoisePreset.OVERWORLD)),
            biomes = original.biomes.copy(spatial = BiomeSpatialSettings(regionScale = 2.0, boundaryRoughness = 0.4)),
        )

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.code == "BIOME_SPATIAL_REQUIRES_PROCEDURAL_TERRAIN" })
    }

    @Test
    fun `surface hydrology rules must be reachable from terrain intent`() {
        val original = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val shape = original.terrain.shape as TerrainShape.Procedural
        val mismatched = original.copy(
            terrain = original.terrain.copy(
                shape = shape.copy(hydrology = shape.hydrology.copy(riverFill = RiverFill.FLUID)),
            ),
        )

        val diagnostics = WorldsmithPackValidator.validate(mismatched)

        assertTrue(diagnostics.any { it.code == "UNREACHABLE_HYDROLOGY_SIGNAL" })
    }

    @Test
    fun `anchor references are validated across terrain bands and biome surfaces`() {
        val original = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val shape = original.terrain.shape as TerrainShape.Procedural
        val anchor = Anchor("holy_peak", AnchorPlacement.Fixed(0, 0), radius = 600, amplitude = 0.0, falloff = 1.0)
        val firstBiome = original.biomes.biomes.first()
        val surfaceRule = SurfaceRuleDefinition(
            id = "summit",
            conditions = SurfaceConditions(anchor = SurfaceAnchorBand("missing", min = 0.7, max = 1.0)),
            stack = firstBiome.surface.base,
        )
        val pack = original.copy(
            terrain = original.terrain.copy(
                shape = shape.copy(
                    anchors = listOf(anchor),
                    bands = listOf(
                        TerrainBand(0.2, 180, 230, BandEffect.ADD, BandRegion.ANYWHERE, "missing", 1.0, 1.0),
                    ),
                ),
            ),
            biomes = original.biomes.copy(
                biomes = original.biomes.biomes.mapIndexed { index, biome ->
                    if (index == 0) biome.copy(surface = biome.surface.copy(rules = listOf(surfaceRule))) else biome
                },
            ),
        )

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertEquals(2, diagnostics.count { it.code == "UNKNOWN_ANCHOR" })
        assertTrue(diagnostics.none { it.code == "ANCHOR_HAS_NO_EFFECT" })
    }

    @Test
    fun `pack validator rejects a stale declared hash`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(manifest = it.manifest.copy(id = "0".repeat(64))) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "manifest.id" && it.code == "PACK_HASH_MISMATCH" })
    }

    @Test
    fun `portable pack loads from a regular directory`() {
        val root = "worldsmith/packs/ashlands"
        listOf("worldsmith.json", "terrain.json", "biomes.json", "features.json").forEach { relative ->
            val target = tempDir.resolve(relative)
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream("$root/$relative").use { source ->
                requireNotNull(source) { "Missing test resource $relative" }
                Files.copy(source, target)
            }
        }

        val pack = WorldsmithPackLoader.loadDirectory(tempDir)

        assertEquals(pack.computedId, pack.manifest.id)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }
}
