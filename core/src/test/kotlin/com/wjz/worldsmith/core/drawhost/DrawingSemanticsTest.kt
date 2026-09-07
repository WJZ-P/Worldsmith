package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.structure.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DrawingSemanticsTest {
    @Test fun `declared rooms and indoor passages require complete readable coverage`() {
        val c=DrawCanvas(Box.of(-1,0,-1,1,3,1))
        c.pen("stone").fill(Box.of(-1,0,-1,1,0,1));c.pen("air").fill(Box.of(-1,1,-1,1,3,1));c.pen("lantern").set(0,3,0)
        val room=BuildBox(BuildPos(-1,1,-1),BuildPos(1,2,1))
        val light=StructureLighting(StructureLightingMode.READABLE,listOf(room),listOf(StructureLightSource(BuildPos(0,3,0),15)))
        val b=StructureBlueprint(id="lit",drawing=StructureDrawingSource(listOf("a".repeat(64))),rooms=listOf(room),lighting=light)
        val geometry=StructureDrawCompiler.compile(b,c.snapshot())
        assertEquals(BuildPos(1,3,1),geometry.lighting!!.sources.single().at)
        fun code(changed:StructureBlueprint)=assertThrows(StructureBuildException::class.java) {StructureDrawCompiler.compile(changed,c.snapshot())}.diagnostic.code
        assertEquals("ROOMS_REQUIRE_LIGHTING",code(b.copy(lighting=null)))
        assertEquals("ROOMS_REQUIRE_LIGHTING",code(b.copy(rooms=emptyList(),indoorPassages=listOf(room),lighting=null)))
        assertEquals("INTERIOR_LIGHTING_REQUIRED",code(b.copy(lighting=light.copy(mode=StructureLightingMode.EXTERIOR_ONLY,spaces=emptyList()))))
        assertEquals("UNCOVERED_OCCUPIED_SPACE",code(b.copy(lighting=light.copy(spaces=listOf(BuildBox(BuildPos(0,1,0),BuildPos(0,2,0)))))))
        assertEquals("ROOM_TOO_DARK",code(b.copy(lighting=light.copy(minimum=15))))
        assertEquals("INVALID_STRUCTURE_ID",code(b.copy(id="../outside")))
    }
}
