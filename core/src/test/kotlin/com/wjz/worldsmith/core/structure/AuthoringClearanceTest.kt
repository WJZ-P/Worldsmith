package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.authoring.AuthoredStructure
import com.wjz.worldsmith.authoring.AuthoringContext
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthoringClearanceTest {
    private val yard = Box.of(2, 1, -3, 3, 3, -1)
    private fun context(): AuthoringContext {
        val a = AuthoringContext(DrawContext(7, emptyMap(), DrawLimits.DEFAULT))
        val c = a.canvas(Box.of(-4, 0, -4, 4, 5, 4)); a.origin(Vec3i.ZERO)
        c.pen("air").fill(c.bounds()); c.pen("stone").fill(Box.of(-4, 0, -4, 4, 0, 4))
        a.room("hall", Box.of(-2, 1, -2, 2, 3, 2), BlockStateRef.of("stone"))
        a.entrance("arrival", Vec3i(0, 1, 2), "SOUTH", BlockStateRef.of("stone"), 3)
        a.lightFixture("lamp", Vec3i(-2, 4, -2), BlockStateRef.of("lantern"), 15)
        return a
    }
    private fun semantics(authored: AuthoredStructure) = Json.parseToJsonElement(authored.sidecar().decodeToString()).jsonObject.getValue("semantics").jsonObject
    private fun boxes(element: JsonElement) = WorldsmithJson.format.decodeFromJsonElement<List<BuildBox>>(element)
    private fun box(value: Box) = BuildBox(BuildPos(value.min().x(), value.min().y(), value.min().z()), BuildPos(value.max().x(), value.max().y(), value.max().z()))
    private fun blueprint(authored: AuthoredStructure): StructureBlueprint {
        val m = semantics(authored)
        return StructureBlueprint(id = "clearance_study", origin = WorldsmithJson.format.decodeFromJsonElement(m.getValue("origin")),
            keepClear = boxes(m.getValue("keepClear")), rooms = boxes(m.getValue("rooms")),
            ports = WorldsmithJson.format.decodeFromJsonElement(m.getValue("ports")),
            variation = StructureVariation(protectedAreas = boxes(m.getValue("protectedAreas"))),
            access = StructureAccess(WorldsmithJson.format.decodeFromJsonElement(m.getValue("entrances")), WorldsmithJson.format.decodeFromJsonElement(m.getValue("destinations")), boxes(m.getValue("keepClear"))),
            lighting = StructureLighting(StructureLightingMode.READABLE, boxes(m.getValue("rooms")), WorldsmithJson.format.decodeFromJsonElement(m.getValue("sources"))))
    }

    @Test fun `clearance records separate intent without changing geometry and reaches the strict compiler`() {
        val a = context().protect(yard)
        val before = a.snapshot()
        a.keepClear(yard)
        val after = a.snapshot()
        assertEquals(before.drawing().voxels(), after.drawing().voxels(), "A declaration must never silently carve or fill blocks")
        val m = semantics(after)
        assertEquals(listOf(box(yard)), boxes(m.getValue("protectedAreas")))
        assertEquals(boxes(semantics(before).getValue("keepClear")) + box(yard), boxes(m.getValue("keepClear")))
        val geometry = StructureDrawCompiler.compile(blueprint(after), after.drawing())
        assertTrue(geometry.diagnostics.isEmpty())
        val normalizedYard = BuildBox(BuildPos(6, 1, 1), BuildPos(7, 3, 3))
        assertTrue(normalizedYard in geometry.keepClear, "Native-export input retains the real declared volume after coordinate normalization")
        assertEquals(listOf(normalizedYard), geometry.protectedAreas, "Variation protection remains a separate field")
    }

    @Test fun `clearance does not remove existing solids and strict preflight rejects blocked access intent`() {
        val a = context()
        a.canvas().pen("stone").set(2, 1, -2)
        val before = a.snapshot().drawing().voxels()
        a.keepClear(yard)
        val authored = a.snapshot()
        assertEquals(before, authored.drawing().voxels())
        assertEquals("minecraft:stone", authored.drawing().voxels().single { it.position() == Vec3i(2, 1, -2) }.block().state().id())
        val error = assertThrows(StructureBuildException::class.java) { StructureDrawCompiler.compile(blueprint(authored), authored.drawing()) }
        assertEquals("BLOCKED_REQUIRED_CLEARANCE", error.diagnostic.code)
    }

    @Test fun `out of canvas clearances fail without mutating an existing declaration`() {
        val a = context().keepClear(yard)
        val before = a.snapshot().sidecar()
        for (outside in listOf(Box.of(-5, 1, 0, -4, 2, 0), Box.of(0, 1, 4, 0, 2, 5), Box.of(0, 5, 0, 0, 6, 0)))
            assertThrows(IllegalArgumentException::class.java) { a.keepClear(outside) }
        assertArrayEquals(before, a.snapshot().sidecar())
    }

    @Test fun `instanced component clearance follows the same rotation and translation as its geometry`() {
        val child = context().keepClear(yard).snapshot()
        val parent = AuthoringContext(DrawContext(8, emptyMap(), DrawLimits.DEFAULT))
        val transform = GridTransform.rotateY(1).andThen(GridTransform.translate(8, 0, 8))
        // This child owns an external entrance port. Preserve its transformed boundary instead
        // of turning that external port into an invalid interior socket on a larger canvas.
        parent.canvas(transform.apply(child.drawing().bounds())); parent.origin(transform.apply(Vec3i.ZERO))
        val assembled = parent.instance("outpost", child, transform).snapshot()
        assertTrue(box(transform.apply(yard)) in boxes(semantics(assembled).getValue("keepClear")))
        assertTrue(StructureDrawCompiler.compile(blueprint(assembled), assembled.drawing()).diagnostics.isEmpty())
    }
}
