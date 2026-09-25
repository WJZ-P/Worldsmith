package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.AnchorRelief
import com.wjz.worldsmith.core.model.AnchorReliefSampler
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.structure.BuildRotation
import com.wjz.worldsmith.core.structure.StructureSurface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/** Offline envelope proof, not native placement or gameplay verification. */
class ExampleTerrainFitTest {
    @Test
    fun `courtyard and village fit dry mesas even at maximum inward footprint warp`() {
        for (pack in listOf(MechanicDiscoveryExample.create(), ImmersiveVillageExample.create())) {
            val shape = pack.terrain.shape as TerrainShape.Procedural
            val anchor = shape.anchors.single()
            val mesa = assertInstanceOf(AnchorRelief.Mesa::class.java, anchor.relief)
            val structure = pack.structures.structures.single()
            val blueprint = structure.blueprint
            val placement = structure.placement
            val target = requireNotNull(placement.anchor)
            assertEquals(anchor.id, target.id)
            assertEquals(AnchorPlacement.Fixed(0, 0), anchor.placement)
            assertEquals(listOf(BuildRotation.NONE), placement.rotations)
            assertEquals(StructureSurface.LAND_SURFACE, placement.terrainFit.surface)
            assertEquals(0.0, mesa.roughness)
            assertTrue(mesa.surfaceY > pack.terrain.seaLevel)
            assertTrue(mesa.surfaceY + blueprint.size.y < pack.terrain.minY + pack.terrain.height)
            placement.terrainFit.verticalRange?.let { assertTrue(mesa.surfaceY in it.minY..it.maxY) }
            assertTrue(shape.bands.isEmpty())
            assertEquals(0.0, shape.hydrology.riverCoverage)
            assertEquals(0.0, shape.hydrology.lakeDensity)
            assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(shape.caves.tunnelDensity,
                shape.caves.cavernDensity, shape.caves.noodleDensity, shape.caves.entranceDensity))
            assertTrue(pack.biomes.biomes.all { it.features.isEmpty() })

            val padding = placement.clearanceBlocks
            for (x in listOf(-padding, blueprint.size.x - 1 + padding)) {
                for (z in listOf(-padding, blueprint.size.z - 1 + padding)) {
                    val dx = x - blueprint.origin.x + target.offsetX
                    val dz = z - blueprint.origin.z + target.offsetZ
                    // Native silhouette warp only expands distance, by at most 1.5.
                    val worstRadius = hypot(dx.toDouble(), dz.toDouble()) * 1.5 / anchor.radius
                    assertTrue(worstRadius < mesa.topRadius, "${structure.id} corner falls off its mesa: $worstRadius")
                    for (originalGround in listOf(40.0, 90.0, 160.0)) {
                        assertEquals(mesa.surfaceY.toDouble(), AnchorReliefSampler.sample(mesa,
                            worstRadius, originalGround, 0.0, anchor.falloff), 1.0e-9)
                    }
                }
            }
        }
    }
}
