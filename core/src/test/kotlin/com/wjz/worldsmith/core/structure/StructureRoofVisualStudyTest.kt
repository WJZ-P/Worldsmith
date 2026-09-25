package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.mcp.StructurePreviewService
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Offline surface studies: these are neither game screenshots nor native collision tests. */
class StructureRoofVisualStudyTest {
    private val roofBlock = BuildMaterial("minecraft:deepslate_tiles")
    private val stairBlock = BuildMaterial("minecraft:deepslate_tile_stairs")
    private val upturned = listOf(RoofKnot(0.0, 0.5), RoofKnot(0.25, 0.0), RoofKnot(1.0, 1.0))
    private fun roof(style: RoofStyle, profile: List<RoofKnot> = emptyList()) =
        BuildOperation.Roof("roof", BuildPos(1, 8, 1), BuildPos(17, 12, 17), "roof", style, stairMaterial = "stairs", profile = profile)
    private fun blueprint(operation: BuildOperation.Roof) = StructureBlueprint(id = "roof_study", size = BuildPos(20, 16, 20),
        palette = mapOf("roof" to roofBlock, "stairs" to stairBlock, "wall" to BuildMaterial("minecraft:calcite"),
            "frame" to BuildMaterial("minecraft:stripped_spruce_log"), "base" to BuildMaterial("minecraft:stone_bricks"), "glass" to BuildMaterial("minecraft:blue_stained_glass")),
        build = buildList {
            add(BuildOperation.Shell("hall", BuildPos(2, 0, 2), BuildPos(16, 7, 16), "wall"))
            add(BuildOperation.Fill("base", BuildPos(2, 0, 2), BuildPos(16, 0, 16), "base"))
            add(BuildOperation.Clear("entry", BuildPos(8, 1, 2), BuildPos(10, 3, 2)))
            for (x in listOf(4, 12)) add(BuildOperation.Fill("window_$x", BuildPos(x, 2, 2), BuildPos(x + 2, 4, 2), "glass"))
            for (x in listOf(3, 7, 11, 15)) add(BuildOperation.Fill("pier_$x", BuildPos(x, 1, 1), BuildPos(x, 6, 1), "frame"))
            add(operation)
        })

    private fun top(geometry: CompiledStructure) = geometry.voxels.groupBy { it.position.x to it.position.z }.mapValues { (_, cells) -> cells.maxBy { it.position.y } }
    private fun block(voxel: StructureVoxel, turns: Int = 0) = DrawBlock(BlockStateRef(voxel.material.block, voxel.material.properties), GridTransform.rotateY(turns))

    @Test fun `hip shape masks retain physical corner symmetry through all rotations and reflected coordinates`() {
        for (profile in listOf(emptyList(), upturned)) {
            val operation = roof(RoofStyle.HIP, profile)
            val geometry = StructureGeometryCompiler.compile(blueprint(operation).copy(build = listOf(operation)))
            val surface = top(geometry)
            for ((xz, voxel) in surface) {
                val mask = DrawPreviewShapes.mask(block(voxel))
                var x = xz.first; var z = xz.second
                for (turns in 1..4) {
                    val next = 18 - z; z = x; x = next
                    assertEquals(DrawPreviewShapes.mask(block(surface.getValue(x to z))), DrawPreviewShapes.mask(block(voxel, turns)), "Rotation $turns at $xz")
                }
                // Reflection here compares the generated roof's two physical sides. It does not
                // assume native deferred mirror state semantics for an arbitrary stair block.
                var reflected = 0
                for (hx in 0..1) for (hy in 0..1) for (hz in 0..1) if (mask and (1 shl (hx or (hy shl 1) or (hz shl 2))) != 0)
                    reflected = reflected or (1 shl ((1 - hx) or (hy shl 1) or (hz shl 2)))
                assertEquals(reflected, DrawPreviewShapes.mask(block(surface.getValue((18 - xz.first) to xz.second))), "Reflection at $xz")
            }
            val corner = surface.getValue(1 to 1)
            assertEquals(if (profile.isEmpty()) 0xb3 else 0x7f, DrawPreviewShapes.mask(block(corner)), "NW convex corner has one high quarter; upturned concave corner has three")
        }
    }

    @Test fun `same frame roof before and after studies expose terraces and hip corner repairs`() {
        val output = Path.of(System.getProperty("worldsmith.projectRoot"), "build/structure-quality-verification/roof-profile")
        Files.createDirectories(output)
        val frame = Box.of(0, 0, 0, 18, 14, 18)
        for ((name, operation) in listOf("shallow-gable" to roof(RoofStyle.GABLE), "upturned-hip" to roof(RoofStyle.HIP, upturned))) {
            val after = StructureGeometryCompiler.compile(blueprint(operation))
            val before = legacySurface(after, operation)
            val afterDrawing = StructurePreviewService.drawing(after)
            val beforeDrawing = StructurePreviewService.drawing(before)
            assertEquals(before.voxels.map { it.position }, after.voxels.map { it.position }, "Repair preserves the declared envelope and existing support")
            val changed = after.voxels.zip(before.voxels).count { (a, b) -> a.material != b.material }
            assertTrue(changed > 0)
            for (mode in listOf("clay", "material")) for (view in listOf("isometric", "front")) {
                val first = DrawPreview.png(beforeDrawing, view, null, frame, emptyList(), mode)
                val second = DrawPreview.png(afterDrawing, view, null, frame, emptyList(), mode)
                assertFalse(first.contentEquals(second), "$name $view $mode must reveal real stair/profile changes, not only a caption")
                Files.write(output.resolve("$name-before-$mode-$view.png"), first)
                Files.write(output.resolve("$name-after-$mode-$view.png"), second)
            }
            Files.writeString(output.resolve("$name.json"), buildJsonObject {
                put("scope", "Frozen voxel-model study, same building and camera frame. Before reconstructs the previous all-bands-stairs/straight-corners rule; after uses actual profile transitions and native-style corner topology. Not an in-game render.")
                put("changedBlockStates", changed)
                put("beforeStairs", before.voxels.count { it.material.block == stairBlock.block })
                put("afterStairs", after.voxels.count { it.material.block == stairBlock.block })
                put("afterCornerStairs", after.voxels.count { it.material.properties["shape"] in listOf("inner_left", "inner_right", "outer_left", "outer_right") })
            }.toString())
        }
    }

    /** Reconstruct only the old roof-surface material rule; backing and building stay identical. */
    private fun legacySurface(geometry: CompiledStructure, operation: BuildOperation.Roof): CompiledStructure {
        val surface = top(geometry).filterKeys { (x, z) -> x in operation.from.x..operation.to.x && z in operation.from.z..operation.to.z }
        val half = (operation.to.x - operation.from.x) / 2
        fun height(distance: Int) = surface.getValue((operation.from.x + distance) to (operation.from.z + half)).position.y
        return geometry.copy(voxels = geometry.voxels.map { voxel ->
            val p = voxel.position
            if (surface[p.x to p.z]?.position != p || p.y >= operation.to.y) return@map voxel
            val dx = minOf(p.x - operation.from.x, operation.to.x - p.x)
            val dz = minOf(p.z - operation.from.z, operation.to.z - p.z)
            val distance = if (operation.style == RoofStyle.HIP) minOf(dx, dz) else dx
            val inward = if (operation.style == RoofStyle.GABLE || dx <= dz) {
                if (p.x - operation.from.x <= operation.to.x - p.x) BuildFacing.EAST else BuildFacing.WEST
            } else if (p.z - operation.from.z <= operation.to.z - p.z) BuildFacing.SOUTH else BuildFacing.NORTH
            val facing = if (distance < half && height(distance + 1) < p.y) inward.rotate(2) else inward
            voxel.copy(material = stairBlock.copy(properties = mapOf("facing" to facing.name.lowercase(), "half" to "bottom", "shape" to "straight")))
        })
    }
}
