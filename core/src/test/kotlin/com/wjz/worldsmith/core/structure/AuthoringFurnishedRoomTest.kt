package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.authoring.AuthoredStructure
import com.wjz.worldsmith.authoring.AuthoringContext
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** One call declares, furnishes and lights a room, and the result passes the checks its metadata implies. */
class AuthoringFurnishedRoomTest {
    private fun semantics(authored: AuthoredStructure) = Json.parseToJsonElement(authored.sidecar().decodeToString()).jsonObject.getValue("semantics").jsonObject
    private fun boxes(element: JsonElement) = WorldsmithJson.format.decodeFromJsonElement<List<BuildBox>>(element)

    @Test fun `a furnished room is declared, walkable from its entrance, lit and not bare`() {
        val a = AuthoringContext(DrawContext(5, emptyMap(), DrawLimits.DEFAULT))
        val canvas = a.canvas(Box.of(-8, 0, -6, 8, 8, 8)); a.origin(Vec3i(0, 0, 0))
        val pen = canvas.pen("stone")
        val walls = Box.of(-6, 1, -4, 6, 5, 4)
        Walls.floor(pen, walls, "cobblestone")
        Walls.frame(pen, walls, Walls.Frame.of("stripped_oak_log", "oak_log", "calcite", "cobblestone"))
        Walls.door(pen, walls, Walls.Side.SOUTH, 0, "oak_door")
        a.entrance("door", Vec3i(0, 1, 5), "SOUTH", BlockStateRef.of("cobblestone"), 3)
        a.furnishedRoom("tavern", Rooms.inside(walls), BlockStateRef.of("oak_planks"), Rooms.Use.TAVERN, Rooms.Style.of("oak", "green"))
        val authored = a.snapshot()
        val m = semantics(authored)
        val sources = WorldsmithJson.format.decodeFromJsonElement<List<StructureLightSource>>(m.getValue("sources"))
        assertTrue(sources.size >= 2, "every light it stood is declared: $sources")

        val rooms = boxes(m.getValue("rooms"))
        val blueprint = StructureBlueprint(id = "tavern", origin = WorldsmithJson.format.decodeFromJsonElement(m.getValue("origin")), rooms = rooms,
            ports = WorldsmithJson.format.decodeFromJsonElement(m.getValue("ports")),
            access = StructureAccess(WorldsmithJson.format.decodeFromJsonElement(m.getValue("entrances")), WorldsmithJson.format.decodeFromJsonElement(m.getValue("destinations"))),
            lighting = StructureLighting(StructureLightingMode.READABLE, rooms, sources))
        val geometry = StructureDrawCompiler.compile(blueprint, authored.drawing(), strict = false)
        assertTrue(geometry.diagnostics.isEmpty(), geometry.diagnostics.toString())
        val metadata = StructureDrawCompiler.metadata(blueprint, geometry)
        assertEquals(emptyList<String>(), StructureLightingChecker.analyze(metadata, geometry.voxels).diagnostics.map { it.code })
        assertEquals(emptyList<String>(), StructureInteriorChecks.bareRooms(metadata, geometry.voxels).map { it.code })
    }
}
