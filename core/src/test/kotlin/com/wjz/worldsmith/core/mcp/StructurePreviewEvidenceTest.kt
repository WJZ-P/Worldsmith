package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructurePreviewEvidenceTest {
    @Test fun `preview evidence uses the exact filtered source original coordinates and fixed frame`() {
        val canvas = DrawCanvas(Box.of(-8, -3, -8, 8, 12, 8))
        canvas.pen("stone").shell(Box.of(-5, -2, -5, 5, 8, 5), 1)
        canvas.pen("air").fill(Box.of(-4, -1, -4, 4, 7, 4))
        canvas.pen("gold_block").fill(Box.of(-6, 9, -6, 6, 10, 6))
        val drawing = canvas.snapshot()
        val region = BuildBox(BuildPos(-5, -2, -5), BuildPos(4, 8, 4))
        val hidden = BuildBox(BuildPos(-5, -2, -5), BuildPos(-3, 8, 4))
        val frame = Box.of(-12, -5, -12, 12, 15, 12)
        val args = buildJsonObject {
            put("views", McpJson.encode(listOf("front", "slice")))
            put("region", McpJson.encode(region)); put("cutaway", true); put("sliceY", 3)
            put("hideComponents", McpJson.encode(listOf("wing")))
            put("frame", McpJson.encode(StructurePreviewService.buildBox(frame)))
        }
        val result = StructurePreviewService().render("filtered", drawing, args, mapOf("wing" to hidden))
        val selected = DrawStructure(drawing.bounds(), drawing.voxels().filter { v ->
            v.position().y() <= 3 && StructurePreviewService.box(region).contains(v.position()) && !StructurePreviewService.box(hidden).contains(v.position())
        }, drawing.anchors())
        val evidence = McpJson.decode<StructureVisualEvidenceReport>(result.structuredContent.getValue("visualEvidence"))
        assertEquals(StructureVisualEvidence.inspect(selected, listOf("front", "slice"), 3, frame), evidence)
        assertTrue(evidence.nonAirCells < result.structuredContent.getValue("visibleAuthoredCells").jsonPrimitive.int, "Explicit room air is not material evidence")
        assertEquals(-2, evidence.occupiedBounds!!.from.y)
        assertEquals(-2, evidence.occupiedBounds!!.from.x)
        assertEquals(3, evidence.occupiedBounds!!.to.y)
        assertTrue(evidence.projections.all { projection -> projection.materials.none { it.block == "minecraft:gold_block" } }, "Cut roof must not leak into material counts")
        assertEquals(listOf("front", "slice"), evidence.projections.map { it.view })
    }

    @Test fun `material and clay share geometric evidence and isometric companion measurements are explicit`() {
        val canvas = DrawCanvas.sized(12, 8, 5)
        canvas.pen("stone").shell(Box.sized(12, 8, 5), 1)
        val drawing = canvas.snapshot()
        val service = StructurePreviewService()
        val material = service.render("pair", drawing, JsonObject(emptyMap()))
        val clay = service.render("pair", drawing, buildJsonObject { put("renderMode", "clay") })
        assertEquals(material.structuredContent.getValue("visualEvidence"), clay.structuredContent.getValue("visualEvidence"))
        val evidence = McpJson.decode<StructureVisualEvidenceReport>(clay.structuredContent.getValue("visualEvidence"))
        assertEquals(listOf("front", "back", "left", "right"), evidence.projections.map { it.view })
        assertTrue(evidence.scope.contains("orthographic companion"))
        assertTrue(evidence.interpretation.contains("not an aesthetic score"))
        assertTrue(evidence.review.isNotEmpty())
    }
}
