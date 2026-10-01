package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** A furnished room must stay walkable from its door and readably lit, judged by the checks a published structure gets. */
class RoomsAccessTest {
    private fun house(walls: Box, door: Int, use: Rooms.Use, seed: Long): Pair<DrawStructure, List<Rooms.Light>> {
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

    private fun pos(v: Vec3i) = BuildPos(v.x(), v.y(), v.z())

    /** What AuthoringContext.room declares: the floor plane as the occupied space, its centre as a destination. */
    private fun blueprint(walls: Box, door: Int, lights: List<Rooms.Light>, extra: List<BuildPos>): StructureBlueprint {
        val room = Rooms.inside(walls)
        val floor = BuildBox(pos(room.min()), BuildPos(room.max().x(), room.min().y(), room.max().z()))
        val centre = BuildPos((room.min().x() + room.max().x()) / 2, room.min().y(), (room.min().z() + room.max().z()) / 2)
        return StructureBlueprint(id = "room", origin = BuildPos(door, 0, 2), rooms = listOf(floor),
            access = StructureAccess(listOf(BuildPos(door, 1, walls.max().z() + 1)), listOf(centre) + extra),
            lighting = StructureLighting(StructureLightingMode.READABLE, listOf(floor), lights.map { StructureLightSource(pos(it.at()), it.level()) }))
    }

    @Test fun `every use leaves the walkway and centre reachable from the door and the floor readably lit`() {
        val walls = Box.of(0, 1, 0, 11, 5, 8)
        for (use in Rooms.Use.entries) for (seed in 0L..3L) {
            val (drawing, lights) = house(walls, 5, use, seed)
            val b = blueprint(walls, 5, lights, listOf(BuildPos(2, 1, 2), BuildPos(9, 1, 2), BuildPos(2, 1, 6), BuildPos(9, 1, 6)))
            val geometry = StructureDrawCompiler.compile(b, drawing, strict = false)
            assertTrue(geometry.diagnostics.isEmpty(), "$use/$seed: ${geometry.diagnostics}")
            val cells = drawing.voxels().associate { it.position() to it.block().state() }
            lights.forEach { assertEquals(it.state(), cells[it.at()], "$use/$seed light at ${it.at()}") }
            val lighting = StructureLightingChecker.analyze(StructureDrawCompiler.metadata(b, geometry), geometry.voxels)
            assertEquals(emptyList<String>(), lighting.diagnostics.map { "${it.code}: ${it.message}" }, "$use/$seed")
            assertTrue(drawing.voxels().count { it.position().y() == 1 && !it.block().state().isAir && it.position().x() in 1..10 && it.position().z() in 1..7 } > 3,
                "$use/$seed is furnished")
        }
    }

    @Test fun `a cramped room still reaches its middle and gets a light`() {
        val walls = Box.of(0, 1, 0, 4, 4, 4)
        for (use in Rooms.Use.entries) {
            val (drawing, lights) = house(walls, 2, use, 0)
            val geometry = StructureDrawCompiler.compile(blueprint(walls, 2, lights, emptyList()), drawing, strict = false)
            assertTrue(geometry.diagnostics.isEmpty(), "$use: ${geometry.diagnostics}")
            assertFalse(lights.isEmpty(), "$use")
        }
    }
}
