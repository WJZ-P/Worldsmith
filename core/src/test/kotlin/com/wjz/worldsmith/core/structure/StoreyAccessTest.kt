package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** A two-storey house drawn with the SDK helpers must pass the same access check a published structure does. */
class StoreyAccessTest {
    private fun house(withFlight: Boolean): DrawStructure {
        val canvas = DrawCanvas(Box.of(-3, 0, -3, 14, 16, 12))
        val pen = canvas.pen("minecraft:stone")
        val frame = Walls.Frame.of("stripped_spruce_log", "spruce_log", "white_concrete", "cobblestone")
        val lower = Box.of(0, 1, 0, 11, 5, 8)
        Walls.floor(pen, lower, "cobblestone")
        Walls.frame(pen, lower, frame)
        Walls.door(pen, lower, Walls.Side.SOUTH, 5, "spruce_door")
        pen.brush(Brush.solid("minecraft:stone_bricks")).fill(Box.of(4, 0, 9, 6, 0, 10))
        val upper = Box.of(-1, 6, -1, 12, 10, 9)
        Walls.floor(pen, upper, "spruce_planks")
        Walls.frame(pen, upper, frame)
        if (withFlight) {
            val landing = Stairs.flight(pen, Vec3i(2, 1, 6), Walls.Side.NORTH, 5, Stairs.Flight.of("spruce_stairs"))
            assertEquals(Vec3i(2, 6, 1), landing)
        }
        return canvas.snapshot()
    }

    private fun blueprint() = StructureBlueprint(id = "storeys", origin = BuildPos(5, 0, 4),
        access = StructureAccess(listOf(BuildPos(5, 1, 9)), listOf(BuildPos(2, 6, 1), BuildPos(9, 6, 7))))

    @Test fun `a flight between framed storeys is walkable from the door to the upper floor`() {
        val geometry = StructureDrawCompiler.compile(blueprint(), house(true), strict = false)
        assertTrue(geometry.diagnostics.isEmpty(), geometry.diagnostics.toString())
        // Compiled coordinates are normalised from the canvas minimum (-3, 0, -3).
        assertTrue(BuildPos(12, 6, 10) in geometry.reachableFeet, "the far corner of the upper floor is reached")
    }

    @Test fun `without the flight the upper floor is cut off`() {
        val geometry = StructureDrawCompiler.compile(blueprint(), house(false), strict = false)
        assertEquals(listOf("DISCONNECTED_STRUCTURE_ROUTE"), geometry.diagnostics.map { it.code }.distinct(), geometry.diagnostics.toString())
    }
}
