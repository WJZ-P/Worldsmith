package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import com.wjz.worldsmith.core.mcp.StructurePreviewService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructureRoofProfileTest {
    private val body = BuildMaterial("minecraft:deepslate_tiles")
    private val steps = BuildMaterial("minecraft:deepslate_tile_stairs", mapOf("waterlogged" to "false", "half" to "top", "shape" to "inner_left", "facing" to "south"))
    private fun roof(width: Int = 13, rise: Int = 2, style: RoofStyle = RoofStyle.GABLE, profile: List<RoofKnot> = emptyList(), axis: RoofAxis = RoofAxis.Z) =
        BuildOperation.Roof("roof", BuildPos(1, 4, 1), BuildPos(width, 4 + rise, width), "body", style, axis, "steps", profile)
    private fun blueprint(roof: BuildOperation.Roof) = StructureBlueprint(id = "roof_study", size = BuildPos(32, 24, 32),
        palette = mapOf("body" to body, "steps" to steps), build = listOf(roof))
    private fun compile(roof: BuildOperation.Roof) = StructureGeometryCompiler.compile(blueprint(roof))
    private fun tops(geometry: CompiledStructure) = geometry.voxels.groupBy { it.position.x to it.position.z }.mapValues { (_, column) -> column.maxBy { it.position.y } }

    @Test fun `cached interpolation preserves every voxel and block state of large profile roofs`() {
        val values = listOf(.25, .20, .15, .10, .10, .15, .23, .30, .37, .45, .53, .62, .72, .82, .92, 1.0)
        val profile = values.mapIndexed { index, height -> RoofKnot(index / 15.0, height) }
        // Frozen before introducing the band cache. The snapshot includes positions, exact block
        // properties, orientation and bounds, rather than accepting only similar counts/profiles.
        val expected = listOf(
            Triple(RoofStyle.GABLE to RoofAxis.Z, 5607, "3959c213a146effe9547f8d8c9d2ae9ec7921d37a9d4733512835d0fcdd3fcaa"),
            Triple(RoofStyle.GABLE to RoofAxis.X, 5607, "50066c3ce20a775ef387ba4a1adc1cbf4a22b3a98066a89b234fef1c60b7ffab"),
            Triple(RoofStyle.HIP to RoofAxis.Z, 5313, "a7842ad04cd965e3b7ca18d6d2f55678c39c22fba37e4436eca8af7cacde6dea"),
            Triple(RoofStyle.FLAT to RoofAxis.Z, 63, "d9684b2d902096079c937f1fd11db13e4262a6681d2d48d5a57a57c9ded121ab"),
        )
        for ((kind, count, hash) in expected) {
            val (style, axis) = kind
            val flat = style == RoofStyle.FLAT
            val operation = BuildOperation.Roof("roof", BuildPos(0, 4, 0), BuildPos(if (flat) 0 else 62, if (flat) 4 else 16, 62),
                "body", style, axis, if (flat) null else "steps", if (flat) emptyList() else profile)
            val b = blueprint(operation).copy(id = "cache_study", size = BuildPos(64, 32, 64),
                palette = mapOf("body" to body, "steps" to BuildMaterial(steps.block, mapOf("waterlogged" to "false"))))
            val geometry = StructureGeometryCompiler.compile(b)
            assertEquals(count, geometry.voxels.size, "$kind")
            assertEquals(count, geometry.expandedWork, "$kind")
            assertEquals(hash, DrawSnapshotCodec.hash(DrawSnapshotCodec.encode(StructurePreviewService.drawing(geometry))), "$kind changed frozen geometry")
        }
    }

    @Test fun `shallow roof uses uninterrupted level terraces not a sawtooth of repeated stairs`() {
        val top = tops(compile(roof()))
        assertEquals(listOf(4, 4, 4, 5, 5, 5, 6, 5, 5, 5, 4, 4, 4), (1..13).map { top.getValue(it to 5).position.y })
        assertEquals(setOf(1, 4, 10, 13), (1..13).filter { top.getValue(it to 5).material.block == steps.block }.toSet())
        for (x in listOf(2, 3, 5, 6, 7, 8, 9, 11, 12)) assertEquals(body, top.getValue(x to 5).material, "Column $x belongs to a level terrace or ridge")
        assertEquals("east", top.getValue(4 to 5).material.properties["facing"])
        assertEquals("west", top.getValue(10 to 5).material.properties["facing"])
    }

    @Test fun `full pitch keeps sloping eaves and ridge while overriding only generated stair properties`() {
        val top = tops(compile(roof(width = 9, rise = 4)))
        assertEquals(body, top.getValue(5 to 5).material)
        assertTrue((1..9).filter { it != 5 }.all { top.getValue(it to 5).material.block == steps.block })
        for (voxel in top.values.filter { it.material.block == steps.block }) {
            assertEquals("bottom", voxel.material.properties["half"])
            assertEquals("straight", voxel.material.properties["shape"])
            assertEquals("false", voxel.material.properties["waterlogged"])
        }
    }

    @Test fun `upturned eaves follow outward and inward slopes without placing steps in the valley`() {
        val profile = listOf(RoofKnot(0.0, 0.5), RoofKnot(0.25, 0.0), RoofKnot(1.0, 1.0))
        val top = tops(compile(roof(width = 17, rise = 4, profile = profile)))
        assertEquals("west", top.getValue(1 to 7).material.properties["facing"])
        assertEquals("west", top.getValue(2 to 7).material.properties["facing"])
        assertEquals(body, top.getValue(3 to 7).material, "The low valley band has no lower neighbour to justify a stair")
        assertEquals("east", top.getValue(4 to 7).material.properties["facing"])
        assertEquals(body, top.getValue(5 to 7).material, "A flat band remains flat after the climb")
        assertEquals(body, top.getValue(9 to 7).material)
    }

    @Test fun `a secondary one band peak has a full cap instead of a one sided arbitrary stair`() {
        val profile = listOf(RoofKnot(0.0, 0.0), RoofKnot(0.25, 0.5), RoofKnot(0.5, 0.0), RoofKnot(1.0, 1.0))
        val top = tops(compile(roof(width = 17, rise = 4, profile = profile)))
        assertEquals("east", top.getValue(2 to 7).material.properties["facing"])
        assertEquals(6, top.getValue(3 to 7).position.y)
        assertEquals(body, top.getValue(3 to 7).material)
        assertEquals("west", top.getValue(4 to 7).material.properties["facing"])
    }

    @Test fun `hip corners use actual perpendicular stair neighbours and keep face bands straight`() {
        val top = tops(compile(roof(width = 9, rise = 4, style = RoofStyle.HIP)))
        for ((xz, facing, shape) in listOf(
            Triple(1 to 1, "east", "outer_right"), Triple(9 to 1, "west", "outer_left"),
            Triple(1 to 9, "east", "outer_left"), Triple(9 to 9, "west", "outer_right"),
            Triple(2 to 2, "east", "outer_right"), Triple(8 to 2, "west", "outer_left"),
        )) {
            assertEquals(facing, top.getValue(xz).material.properties["facing"], "$xz")
            assertEquals(shape, top.getValue(xz).material.properties["shape"], "$xz")
        }
        assertEquals("straight", top.getValue(1 to 3).material.properties["shape"])
        assertEquals("straight", top.getValue(3 to 1).material.properties["shape"])
        assertEquals(body, top.getValue(5 to 5).material)
    }

    @Test fun `upturned hip eaves form concave inner corners instead of hardcoded outer tips`() {
        val profile = listOf(RoofKnot(0.0, 0.5), RoofKnot(0.25, 0.0), RoofKnot(1.0, 1.0))
        val top = tops(compile(roof(width = 17, rise = 4, style = RoofStyle.HIP, profile = profile)))
        assertEquals("west", top.getValue(1 to 1).material.properties["facing"])
        assertEquals("inner_right", top.getValue(1 to 1).material.properties["shape"])
        assertEquals("east", top.getValue(17 to 1).material.properties["facing"])
        assertEquals("inner_left", top.getValue(17 to 1).material.properties["shape"])
        assertEquals(body, top.getValue(3 to 3).material)
    }

    @Test fun `every profile remains bounded deterministic six connected and carries module rotation`() {
        val profiles = listOf(emptyList(), listOf(RoofKnot(0.0, 0.5), RoofKnot(0.25, 0.0), RoofKnot(1.0, 1.0)))
        for (style in listOf(RoofStyle.GABLE, RoofStyle.HIP)) for (profile in profiles) {
            val operation = roof(width = 17, rise = 4, style = style, profile = profile)
            val geometry = compile(operation)
            assertEquals(geometry, compile(operation))
            assertTrue(geometry.voxels.all { it.position.x in 1..17 && it.position.z in 1..17 && it.position.y in 4..8 })
            val unseen = geometry.voxels.map { it.position }.toMutableSet()
            val queue = ArrayDeque<BuildPos>(); queue.addLast(unseen.first()); unseen.remove(queue.first())
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                for (d in listOf(BuildPos(1, 0, 0), BuildPos(-1, 0, 0), BuildPos(0, 1, 0), BuildPos(0, -1, 0), BuildPos(0, 0, 1), BuildPos(0, 0, -1))) {
                    val next = BuildPos(p.x + d.x, p.y + d.y, p.z + d.z)
                    if (unseen.remove(next)) queue.addLast(next)
                }
            }
            assertTrue(unseen.isEmpty(), "$style roof keeps support between each adjacent band")
            val rotated = blueprint(operation).copy(build = listOf(BuildOperation.Instance("turn", "roof", BuildPos(20, 0, 0), BuildRotation.CLOCKWISE_90)), modules = mapOf("roof" to listOf(operation)))
            val actual = StructureGeometryCompiler.compile(rotated).voxels.associateBy { it.position }
            for (voxel in geometry.voxels) {
                val expected = BuildPos(20 - voxel.position.z, voxel.position.y, voxel.position.x)
                assertEquals(voxel.material, actual.getValue(expected).material)
                assertEquals(1, actual.getValue(expected).quarterTurns)
            }
        }
    }
}
