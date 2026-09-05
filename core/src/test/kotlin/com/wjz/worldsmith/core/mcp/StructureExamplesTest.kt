package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StructureExamplesTest {
    @TempDir lateinit var root:Path
    private fun call(name:String,args:JsonObject)=WorldsmithMcpTools(root.resolve("packs")).all().single {it.name==name}.handler(args)
    @Test fun `all public examples compile and expose their actual capabilities`() {
        for(id in listOf("forest_shrine","wayfarer_lodge","arcane_observatory","connected_courtyard")) {
            val sample=call("worldsmith_get_structure_example",buildJsonObject {put("id",id)})
            assertFalse(sample.isError,sample.text)
            val blueprint=WorldsmithJson.format.decodeFromJsonElement<StructureBlueprint>(sample.structuredContent.getValue("blueprint"))
            assertTrue(StructureValidator.validateBlueprint(blueprint).none {it.severity==com.wjz.worldsmith.core.validation.DiagnosticSeverity.ERROR},"$id: ${StructureValidator.validateBlueprint(blueprint)}")
            if(id=="connected_courtyard") {
                val definition=sample.structuredContent.getValue("structure")
                val result=call("worldsmith_preview_assembly",buildJsonObject {put("structure",definition);put("variant",3)})
                assertFalse(result.isError,result.text)
                assertEquals(5,result.structuredContent.getValue("pieceCount").jsonPrimitive.int)
                assertEquals(4,result.structuredContent.getValue("connectionCount").jsonPrimitive.int)
                assertTrue(Files.readString(Path.of(result.structuredContent.getValue("previewPath").jsonPrimitive.content)).contains("ISOMETRIC"))
            } else {
                val result=call("worldsmith_preview_structure",buildJsonObject {
                    put("blueprint",sample.structuredContent.getValue("blueprint"));put("variant",blueprint.variation.count-1);put("sliceY",minOf(7,blueprint.size.y-1));put("cutaway",true)
                })
                assertFalse(result.isError,"$id: ${result.text}")
                assertFalse(result.structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
            }
        }
    }
    @Test fun `special surfaces require bounded height windows and explicit policies`() {
        val sample=call("worldsmith_get_structure_example",buildJsonObject {put("id","forest_shrine")})
        val b=WorldsmithJson.format.decodeFromJsonElement<StructureBlueprint>(sample.structuredContent.getValue("blueprint"))
        val base=com.wjz.worldsmith.core.pack.WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        fun errors(fit:StructureTerrainFit)=StructureValidator.validate(StructureLibrary(structures=listOf(WorldStructureDefinition("shrine",b,StructurePlacement(listOf(base.biomes.biomes.first().id),terrainFit=fit)))),base.biomes).map {it.code}
        assertTrue("MISSING_STRUCTURE_HEIGHT_RANGE" in errors(StructureTerrainFit(surface=StructureSurface.SKY_SURFACE)))
        assertTrue("INVALID_STRUCTURE_EARTHWORK" in errors(StructureTerrainFit(earthwork=StructureEarthwork())))
        assertTrue("CEILING_FOUNDATION_UNUSED" in errors(StructureTerrainFit(surface=StructureSurface.CAVE_CEILING,verticalRange=StructureHeightRange(20,50),foundation=StructureFoundation(FoundationMode.FILL,"foundation",6))))
    }
}
