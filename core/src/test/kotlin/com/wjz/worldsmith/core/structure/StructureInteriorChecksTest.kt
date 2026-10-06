package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructureInteriorChecksTest {
    private fun room(walls: Box, furnished: Boolean): Pair<StructureBlueprint, CompiledStructure> {
        val canvas = DrawCanvas(Box.of(-2, 0, -2, walls.max().x() + 2, walls.max().y() + 2, walls.max().z() + 3))
        val pen = canvas.pen("minecraft:stone")
        Walls.floor(pen, walls, "spruce_planks")
        Walls.frame(pen, walls, Walls.Frame.of("stripped_spruce_log", "spruce_log", "calcite", "cobblestone"))
        Walls.door(pen, walls, Walls.Side.SOUTH, (walls.min().x() + walls.max().x()) / 2, "spruce_door")
        val inside = Rooms.inside(walls)
        if (furnished) Rooms.furnish(pen, inside, Rooms.Use.KITCHEN, Rooms.Style.of("spruce", "red"))
        else pen.brush(Brush.solid("minecraft:lantern")).set(inside.min().x(), inside.min().y(), inside.min().z())
        val floor = BuildBox(BuildPos(inside.min().x(), inside.min().y(), inside.min().z()), BuildPos(inside.max().x(), inside.min().y(), inside.max().z()))
        val b = StructureBlueprint(id = "room", origin = BuildPos(0, 0, 0), rooms = listOf(floor))
        val g = StructureDrawCompiler.compile(b, canvas.snapshot(), strict = false)
        return StructureDrawCompiler.metadata(b, g) to g
    }

    @Test fun `an empty room is pointed out as a warning and a furnished one is not`() {
        val walls = Box.of(0, 1, 0, 11, 5, 8)
        val (bare, bareGeometry) = room(walls, furnished = false)
        val warnings = StructureInteriorChecks.bareRooms(bare, bareGeometry.voxels)
        assertEquals(listOf("BARE_ROOM"), warnings.map { it.code })
        assertEquals(DiagnosticSeverity.WARNING, warnings.single().severity, "a review hint, never a publication gate")
        assertEquals(70, warnings.single().metrics["floorCells"])
        assertEquals(1, warnings.single().metrics["furnishedCells"], "a lone lantern does not furnish a room")

        val (kitchen, kitchenGeometry) = room(walls, furnished = true)
        assertEquals(emptyList<String>(), StructureInteriorChecks.bareRooms(kitchen, kitchenGeometry.voxels).map { it.code })
    }

    @Test fun `a closet may stay empty`() {
        val (closet, geometry) = room(Box.of(0, 1, 0, 4, 4, 4), furnished = false)
        assertTrue(StructureInteriorChecks.bareRooms(closet, geometry.voxels).isEmpty())
    }
}
