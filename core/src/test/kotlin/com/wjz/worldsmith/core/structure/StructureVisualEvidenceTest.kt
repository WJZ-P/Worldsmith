package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class StructureVisualEvidenceTest {
    private fun voxel(x: Int, y: Int, z: Int, material: String = "stone") = DrawVoxel(Vec3i(x, y, z), DrawBlock(BlockStateRef.of(material)))
    private fun report(drawing: DrawStructure, view: String = "front") = StructureVisualEvidence.inspect(drawing, listOf(view)).projections.single()
    private fun wall(): DrawStructure {
        val canvas = DrawCanvas(Box.of(0, 0, 0, 11, 7, 4))
        canvas.pen("stone").fill(Box.of(0, 0, 0, 11, 7, 0))
        canvas.pen("spruce_planks").fill(Box.of(0, 0, 4, 11, 7, 4))
        canvas.pen("diamond_block").fill(Box.of(3, 2, 2, 8, 5, 2))
        return canvas.snapshot()
    }

    @Test fun `hidden interiors air and material noise do not masquerade as facade depth`() {
        val drawing = wall()
        val initial = report(drawing)
        assertEquals(96, initial.visibleCells)
        assertEquals(listOf(StructureVisibleMaterial("minecraft:stone", 96)), initial.materials)
        assertEquals(1, initial.depthLayers)
        assertEquals(0, initial.reliefEdges)
        assertEquals(StructurePlanarPanel(BuildBox(BuildPos(0, 0, 0), BuildPos(11, 7, 0)), 12, 8, 96), initial.largestPlanarPanel)
        assertEquals(listOf(StructureProfileRun(0, 11, 0, 7)), initial.profile)
        val recoloured = DrawStructure(drawing.bounds(), drawing.voxels().map { v ->
            if (v.position().z() == 0 && v.position().x() % 2 == 0) voxel(v.position().x(), v.position().y(), 0, "gold_block") else v
        } + voxel(0, 0, 1, "air"), emptyMap())
        val painted = report(recoloured)
        assertEquals(initial.largestPlanarPanel, painted.largestPlanarPanel)
        assertEquals(initial.profile, painted.profile)
        assertEquals(initial.reliefEdges, painted.reliefEdges)
        assertEquals(96, painted.materials.sumOf { it.cells })
        assertEquals(2, painted.materialCount)
        val evidence = StructureVisualEvidence.inspect(recoloured, listOf("front"))
        assertEquals(drawing.nonAirCells().toInt(), evidence.nonAirCells)
        assertEquals("front", evidence.review.single().view)
        assertEquals(initial.largestPlanarPanel!!.region, evidence.review.single().region)
    }

    @Test fun `recessed bay breaks the quiet plane without pretending the back wall is a window`() {
        val drawing = wall()
        val recessed = DrawStructure(drawing.bounds(), drawing.voxels().map { v ->
            if (v.position().z() == 0 && v.position().x() in 4..5 && v.position().y() in 2..4)
                voxel(v.position().x(), v.position().y(), 0, "air") else v
        }, emptyMap())
        val front = report(recessed)
        assertEquals(96, front.visibleCells, "The rear geometry is visible through the bay, not counted as an absent window")
        assertEquals(2, front.depthLayers)
        assertEquals(2L, front.depthSpan, "The nearer interior is visible, not the hidden rear wall")
        assertEquals(10, front.reliefEdges)
        assertEquals(48, front.largestPlanarPanel!!.cells)
        assertEquals(6, front.materials.single { it.block == "minecraft:diamond_block" }.cells)
        assertTrue(front.largestPlanarPanel!!.cells < report(drawing).largestPlanarPanel!!.cells)
    }

    @Test fun `secondary mass changes silhouette and ground plan rather than only palette`() {
        val canvas = DrawCanvas(Box.of(-3, -2, -2, 12, 10, 7))
        canvas.pen("stone").fill(Box.of(0, -2, 0, 11, 5, 5))
        canvas.pen("stone").fill(Box.of(2, 6, 1, 5, 10, 4))
        canvas.pen("stone").fill(Box.of(-3, -2, 1, -1, 2, 4))
        val drawing = canvas.snapshot()
        val evidence = StructureVisualEvidence.inspect(drawing, listOf("isometric", "isometric_back", "top"))
        assertEquals(listOf("front", "back", "left", "right", "top"), evidence.projections.map { it.view })
        assertEquals(84, evidence.footprintColumns)
        val front = evidence.projections.first { it.view == "front" }
        assertEquals(listOf(StructureProfileRun(-3, -1, -2, 2), StructureProfileRun(0, 1, -2, 5),
            StructureProfileRun(2, 5, -2, 10), StructureProfileRun(6, 11, -2, 5)), front.profile)
        assertEquals(3, evidence.projections.first { it.view == "top" }.depthLayers)
        assertEquals(BuildBox(BuildPos(-3, -2, 0), BuildPos(11, 10, 5)), evidence.occupiedBounds)
    }

    @Test fun `rotation and mirror preserve measurements while exposing the correct elevation`() {
        val drawing = wall()
        fun transform(t: GridTransform) = DrawStructure(t.apply(drawing.bounds()), drawing.voxels().map { DrawVoxel(t.apply(it.position()), it.block()) }, emptyMap())
        val rotated = transform(GridTransform.rotateY(1))
        val mirrored = transform(GridTransform.reflectX())
        for ((original, moved) in listOf(report(drawing, "left") to report(rotated, "front"),
            report(drawing, "front") to report(rotated, "right"), report(drawing, "right") to report(mirrored, "left"))) {
            assertEquals(original.visibleCells, moved.visibleCells)
            assertEquals(original.depthSpan, moved.depthSpan)
            assertEquals(original.reliefEdges, moved.reliefEdges)
            assertEquals(original.materials, moved.materials)
            assertEquals(original.largestPlanarPanel!!.cells, moved.largestPlanarPanel!!.cells)
        }
        assertEquals("-x", report(drawing, "back").screenRight)
        assertEquals("-z", report(drawing, "left").screenRight)
        assertEquals("+z", report(drawing, "right").screenRight)
    }

    @Test fun `slice is measured separately from top while its camera frame stays fixed`() {
        val drawing = wall()
        val frame = Box.of(-20, -10, -20, 20, 20, 20)
        val evidence = StructureVisualEvidence.inspect(drawing, listOf("slice", "top"), 3, frame)
        assertEquals(BuildBox(BuildPos(-20, -10, -20), BuildPos(20, 20, 20)), evidence.frame)
        assertEquals(30, evidence.projections.first().visibleCells)
        assertEquals(1, evidence.projections.first().depthLayers)
        assertEquals(30, evidence.projections.last().visibleCells)
        assertEquals(2, evidence.projections.last().depthLayers)
        assertThrows(IllegalArgumentException::class.java) { StructureVisualEvidence.inspect(drawing, listOf("slice")) }
        assertThrows(IllegalArgumentException::class.java) { StructureVisualEvidence.inspect(drawing, listOf("slice"), 99) }
    }

    @Test fun `empty drawing has no invented surfaces or review failure`() {
        val canvas = DrawCanvas.sized(4, 4, 4)
        canvas.pen("air").fill(Box.sized(4, 4, 4))
        val evidence = StructureVisualEvidence.inspect(canvas.snapshot())
        assertEquals(0, evidence.nonAirCells)
        assertEquals(0, evidence.footprintColumns)
        assertNull(evidence.occupiedBounds)
        assertTrue(evidence.review.isEmpty())
        assertTrue(evidence.projections.all { it.visibleCells == 0 && it.depthLayers == 0 && it.largestPlanarPanel == null && it.materials.isEmpty() && it.profile.isEmpty() })
    }

    @Test fun `sparse extreme coordinates do not allocate the bounding volume or connect across gaps`() {
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            val drawing = DrawStructure(Box.of(-1_000_000_000, -1_000_000_000, -1_000_000_000, 1_000_000_000, 1_000_000_000, 1_000_000_000),
                listOf(voxel(-1_000_000_000, -1_000_000_000, -1_000_000_000), voxel(1_000_000_000, 1_000_000_000, 1_000_000_000)), emptyMap())
            val evidence = StructureVisualEvidence.inspect(drawing)
            assertEquals(2, evidence.nonAirCells)
            assertTrue(evidence.projections.all { it.visibleCells == 2 && it.profileRunCount == 2 && it.largestPlanarPanel!!.cells == 1 && it.reliefEdges == 0 })
            assertEquals(2_000_000_000L, evidence.projections.first().depthSpan)
        }
    }

    @Test fun `profile and palette summaries retain actual counts when truncated`() {
        val voxels = (0..79).map { voxel(it, it % 3, 0, "worldsmith:material_$it") }
        val drawing = DrawStructure(Box.of(0, 0, 0, 79, 3, 0), voxels, emptyMap())
        val evidence = report(drawing)
        assertEquals(80, evidence.profileRunCount)
        assertEquals(StructureVisualEvidence.MAX_PROFILE_RUNS, evidence.profile.size)
        assertEquals(80, evidence.materialCount)
        assertEquals(StructureVisualEvidence.MAX_MATERIALS, evidence.materials.size)
        assertEquals(evidence, report(drawing), "Summary order and tied largest-panel selection must be deterministic")
    }

    @Test fun `sparse largest rectangle agrees with exhaustive small facade search`() {
        val random = Random(276)
        repeat(40) {
            val depths = (0..5).flatMap { y -> (0..7).mapNotNull { x -> if (random.nextInt(4) == 0) null else (x to y) to random.nextInt(3) } }.toMap()
            val drawing = DrawStructure(Box.of(0, 0, 0, 7, 5, 2), depths.map { (p, depth) -> voxel(p.first, p.second, depth) }, emptyMap())
            var expected = 0
            for (left in 0..7) for (right in left..7) for (low in 0..5) for (high in low..5) {
                val depth = depths[left to low] ?: continue
                if ((left..right).all { x -> (low..high).all { y -> depths[x to y] == depth } }) expected = maxOf(expected, (right - left + 1) * (high - low + 1))
            }
            val actual = report(drawing).largestPlanarPanel!!
            assertEquals(expected, actual.cells)
            assertTrue((actual.region.from.x..actual.region.to.x).all { x -> (actual.region.from.y..actual.region.to.y).all { y -> depths[x to y] == actual.region.from.z } })
        }
    }
}
