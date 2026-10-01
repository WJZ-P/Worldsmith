package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** A furnished room must stay walkable from its door and lit, judged by the same check a published structure gets. */
class RoomsAccessTest {
    private fun house(walls: Box, door: Int, use: Rooms.Use, seed: Long): Pair<DrawStructure, List<Vec3i>> {
        val canvas = DrawCanvas(Box.of(-3, 0, -3, walls.max().x() + 3, walls.max().y() + 3, walls.max().z() + 4))
        val pen = canvas.pen("minecraft:stone")
        val frame = Walls.Frame.of("stripped_spruce_log", "spruce_log", "white_concrete", "cobblestone")
        Walls.floor(pen, walls, "spruce_planks")
        Walls.frame(pen, walls, frame)
        Walls.door(pen, walls, Walls.Side.SOUTH, door, "spruce_door")
        pen.brush(Brush.solid("minecraft:stone_bricks")).fill(Box.of(door - 1, 0, walls.max().z() + 1, door + 1, 0, walls.max().z() + 2))
        val inside = Vec3i(door, walls.min().y(), walls.max().z() - 1)
        val lights = Rooms.furnish(pen, Rooms.inside(walls), use, Rooms.Style.of("spruce", "red").withSeed(seed), Box(inside, inside))
        return canvas.snapshot() to lights
    }

    @Test fun `every use leaves the walkway ring reachable from the door and stands a light`() {
        val walls = Box.of(0, 1, 0, 11, 5, 8)
        for (use in Rooms.Use.entries) for (seed in 0L..3L) {
            val (drawing, lights) = house(walls, 5, use, seed)
            val blueprint = StructureBlueprint(id = "room", origin = BuildPos(5, 0, 4),
                access = StructureAccess(listOf(BuildPos(5, 1, 9)), listOf(BuildPos(2, 1, 2), BuildPos(9, 1, 2), BuildPos(2, 1, 6), BuildPos(9, 1, 6))))
            val geometry = StructureDrawCompiler.compile(blueprint, drawing, strict = false)
            assertTrue(geometry.diagnostics.isEmpty(), "$use/$seed: ${geometry.diagnostics}")
            assertFalse(lights.isEmpty(), "$use/$seed has a light")
            val cells = drawing.voxels().associate { it.position() to it.block().state().id() }
            lights.forEach { assertTrue(cells[it] in setOf("minecraft:lantern", "minecraft:candle"), "$use/$seed: ${cells[it]} at $it") }
            assertTrue(drawing.voxels().count { it.position().y() == 1 && !it.block().state().isAir && it.position().x() in 1..10 && it.position().z() in 1..7 } > 3,
                "$use/$seed is furnished")
        }
    }

    @Test fun `a cramped room still reaches its middle and gets a light`() {
        val walls = Box.of(0, 1, 0, 4, 4, 4)
        for (use in Rooms.Use.entries) {
            val (drawing, lights) = house(walls, 2, use, 0)
            val blueprint = StructureBlueprint(id = "room", origin = BuildPos(2, 0, 2),
                access = StructureAccess(listOf(BuildPos(2, 1, 5)), listOf(BuildPos(2, 1, 2))))
            val geometry = StructureDrawCompiler.compile(blueprint, drawing, strict = false)
            assertTrue(geometry.diagnostics.isEmpty(), "$use: ${geometry.diagnostics}")
            assertFalse(lights.isEmpty(), "$use")
        }
    }
}
