package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DrawingSemanticsTest {
    @Test fun `declarations remain mandatory while numeric lighting is advisory and opt in`() {
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
        assertEquals(0,StructureLightingChecker.inspect(StructureDrawCompiler.metadata(b,geometry),geometry.voxels).sampledFeet)
        // Compilation performs no light propagation and accepts a dim-but-valid design.
        val dim=b.copy(lighting=light.copy(minimum=15))
        val compiled=StructureDrawCompiler.compile(dim,c.snapshot())
        val feedback=StructureLightingChecker.analyze(StructureDrawCompiler.metadata(dim,compiled),compiled.voxels)
        assertTrue(feedback.sampledFeet>0)
        assertEquals("ROOM_TOO_DARK",feedback.diagnostics.single().code)
        assertEquals(DiagnosticSeverity.WARNING,feedback.diagnostics.single().severity)
        val partial=b.copy(lighting=light.copy(spaces=listOf(BuildBox(BuildPos(0,1,0),BuildPos(0,2,0)))))
        val partialGeometry=StructureDrawCompiler.compile(partial,c.snapshot())
        val coverage=StructureLightingChecker.analyze(StructureDrawCompiler.metadata(partial,partialGeometry),partialGeometry.voxels)
        assertEquals("UNCOVERED_OCCUPIED_SPACE",coverage.diagnostics.single().code)
        assertEquals(DiagnosticSeverity.WARNING,coverage.diagnostics.single().severity)
        assertEquals("INVALID_STRUCTURE_ID",code(b.copy(id="../outside")))
    }

    @Test fun `deliberately dark interiors may omit lamps but never malformed source declarations`() {
        val c=DrawCanvas.sized(3,4,3)
        c.pen("stone").fill(Box.of(0,0,0,2,0,2));c.pen("air").fill(Box.of(0,1,0,2,3,2))
        val room=BuildBox(BuildPos(0,1,0),BuildPos(2,1,2))
        val policy=StructureLighting(StructureLightingMode.INTENTIONALLY_DARK,listOf(room))
        val b=StructureBlueprint(id="dark_crypt",drawing=StructureDrawingSource(listOf("a".repeat(64))),rooms=listOf(room),lighting=policy)
        val geometry=StructureDrawCompiler.compile(b,c.snapshot())
        assertTrue(StructureLightingChecker.analyze(StructureDrawCompiler.metadata(b,geometry),geometry.voxels).diagnostics.isEmpty())
        val falseSource=b.copy(lighting=policy.copy(sources=listOf(StructureLightSource(BuildPos(1,2,1),15))))
        assertEquals("LIGHT_SOURCE_MISSING_BLOCK",assertThrows(StructureBuildException::class.java){StructureDrawCompiler.compile(falseSource,c.snapshot())}.diagnostic.code)
        val invalidBox=b.copy(lighting=policy.copy(spaces=listOf(BuildBox(BuildPos(0,1,0),BuildPos(3,1,2)))))
        assertEquals("INVALID_LIGHTING_SPACE",assertThrows(StructureBuildException::class.java){StructureDrawCompiler.compile(invalidBox,c.snapshot())}.diagnostic.code)
    }

    @Test fun `preflight defaults to no light estimate and opt in darkness does not invalidate it`() {
        val c=DrawCanvas.sized(5,5,5)
        c.pen("stone").fill(Box.of(0,0,0,4,0,4));c.pen("air").fill(Box.of(0,1,0,4,4,4));c.pen("lantern").set(2,4,2)
        val room=BuildBox(BuildPos(0,1,0),BuildPos(4,1,4));val hash="a".repeat(64)
        val light=StructureLighting(StructureLightingMode.READABLE,listOf(room),listOf(StructureLightSource(BuildPos(2,4,2),15)),15)
        val blueprint=StructureBlueprint(id="hall",drawing=StructureDrawingSource(listOf(hash)),rooms=listOf(room),lighting=light)
        val definition=WorldStructureDefinition("hall",blueprint,StructurePlacement(emptyList()))
        val checks=StructureCheckService()
        val normal=checks.inspect(definition,mapOf(hash to c.snapshot()),assemblyContext=false)
        assertTrue(normal.report.valid)
        assertEquals(0,normal.lighting.getValue("hall:0").sampledFeet)
        val diagnostic=checks.inspect(definition,mapOf(hash to c.snapshot()),assemblyContext=false,estimateLighting=true)
        assertTrue(diagnostic.report.valid)
        assertTrue(diagnostic.lighting.getValue("hall:0").sampledFeet>0)
        assertTrue(diagnostic.report.stages.getValue("semantics").diagnostics.any {it.code=="ROOM_TOO_DARK"&&it.severity==DiagnosticSeverity.WARNING})
    }
}
