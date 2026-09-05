package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructureGeometryTest {
    private val stone=BuildMaterial("minecraft:stone_bricks")
    private fun blueprint(build:List<BuildOperation>,modules:Map<String,List<BuildOperation>> = emptyMap()) =
        StructureBlueprint(id="test_house",size=BuildPos(12,12,12),palette=mapOf("stone" to stone),build=build,modules=modules)

    @Test fun `shell has real interior air while unspecified cells stay absent`() {
        val compiled=StructureGeometryCompiler.compile(blueprint(listOf(
            BuildOperation.Shell("room",BuildPos(1,0,1),BuildPos(5,4,5),"stone"),
            BuildOperation.Clear("door",BuildPos(2,1,1),BuildPos(3,2,1)),
        )))
        val cells=compiled.voxels.associate {it.position to it.material.block}
        assertEquals(125,cells.size)
        assertEquals("minecraft:air",cells[BuildPos(2,1,1)])
        assertEquals("minecraft:air",cells[BuildPos(3,2,3)])
        assertEquals("minecraft:stone_bricks",cells[BuildPos(1,0,1)])
        assertNull(cells[BuildPos(0,0,0)])
    }

    @Test fun `instances transform coordinates and carry real block state rotation to adapter`() {
        val b=blueprint(listOf(BuildOperation.Instance("column","beam",BuildPos(6,1,6),BuildRotation.CLOCKWISE_90)),
            mapOf("beam" to listOf(BuildOperation.Line("beam",BuildPos(0,0,0),BuildPos(3,0,0),"stone"))))
        val cells=StructureGeometryCompiler.compile(b).voxels
        assertEquals(setOf(BuildPos(6,1,6),BuildPos(6,1,7),BuildPos(6,1,8),BuildPos(6,1,9)),cells.map {it.position}.toSet())
        assertTrue(cells.all {it.quarterTurns==1})
    }

    @Test fun `repeat output is bounded deterministic and module cycles fail before expansion`() {
        val b=blueprint(listOf(BuildOperation.Repeat("columns",4,BuildPos(2,0,0),listOf(BuildOperation.Fill("pillar",BuildPos(0,0,0),BuildPos(0,4,0),"stone")))))
        val a=StructureGeometryCompiler.compile(b)
        assertEquals(a,StructureGeometryCompiler.compile(b))
        assertEquals(20,a.voxels.size)
        val cyclic=blueprint(listOf(BuildOperation.Instance("root","loop",BuildPos(0,0,0))),mapOf("loop" to listOf(BuildOperation.Instance("child","loop",BuildPos(0,0,0)))))
        assertEquals("STRUCTURE_MODULE_CYCLE",StructureValidator.validateBlueprint(cyclic).single().code)
    }

    @Test fun `bounds unknown material and explosive repeats are diagnosed`() {
        assertEquals("STRUCTURE_BOUNDS_EXCEEDED",StructureValidator.validateBlueprint(blueprint(listOf(BuildOperation.SetBlock("outside",BuildPos(12,0,0),"stone")))).single().code)
        assertEquals("UNKNOWN_STRUCTURE_MATERIAL",StructureValidator.validateBlueprint(blueprint(listOf(BuildOperation.SetBlock("unknown",BuildPos(0,0,0),"absent")))).single().code)
        val huge=BuildOperation.Repeat("a",64,BuildPos(0,0,0),listOf(BuildOperation.Repeat("b",64,BuildPos(0,0,0),listOf(BuildOperation.Fill("box",BuildPos(0,0,0),BuildPos(11,11,11),"stone")))))
        assertTrue(StructureValidator.validateBlueprint(blueprint(listOf(huge))).single().code in setOf("STRUCTURE_WORK_BUDGET","STRUCTURE_OPERATION_BUDGET"))
    }

    @Test fun `roofs have coherent ridge and JSON uses op discriminator`() {
        val b=blueprint(listOf(BuildOperation.Roof("roof",BuildPos(1,4,1),BuildPos(9,8,9),"stone")))
        val text=WorldsmithJson.encode(b)
        assertTrue("\"op\": \"ROOF\"" in text)
        val roundtrip=WorldsmithJson.decode<StructureBlueprint>(text)
        val c=StructureGeometryCompiler.compile(roundtrip)
        assertEquals(81,c.voxels.map {it.position.x to it.position.z}.distinct().size)
        assertTrue(c.voxels.size>81,"sloping roof bands need backing blocks")
        assertEquals(setOf(4,5,6,7,8),c.voxels.map {it.position.y}.toSet())
        val unseen=c.voxels.map {it.position}.toMutableSet();val queue=java.util.ArrayDeque<BuildPos>();queue.add(unseen.first());unseen.remove(queue.first)
        while(queue.isNotEmpty()) {val p=queue.removeFirst();for(d in listOf(BuildPos(1,0,0),BuildPos(-1,0,0),BuildPos(0,1,0),BuildPos(0,-1,0),BuildPos(0,0,1),BuildPos(0,0,-1))) {val next=BuildPos(p.x+d.x,p.y+d.y,p.z+d.z);if(unseen.remove(next))queue.add(next)}}
        assertTrue(unseen.isEmpty(),"roof bands must be six-connected, not touch only at diagonal edges")
    }

    @Test fun `example is executable and preview is well formed XML`() {
        val text=javaClass.classLoader.getResourceAsStream("worldsmith/structures/forest_shrine.json")!!.bufferedReader().readText()
        val b=WorldsmithJson.decode<StructureBlueprint>(text)
        val geometry=StructureGeometryCompiler.compile(b)
        assertTrue(geometry.voxels.size>500)
        val svg=StructurePreview.svg(geometry)
        val xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("svg",xml.documentElement.tagName)
        assertTrue(svg.contains("not in-game render"))
    }

    @Test fun `all referenced blueprints participate in pack identity but preview files do not`() {
        val pack=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val b=blueprint(listOf(BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(3,0,3),"stone")))
        fun files(blueprint:StructureBlueprint):Map<String,String> = mapOf(
            "terrain.json" to WorldsmithJson.encode(pack.terrain),"biomes.json" to WorldsmithJson.encode(pack.biomes),"features.json" to WorldsmithJson.encode(pack.features),
        )+StructurePackIO.files(StructureLibrary(structures=listOf(WorldStructureDefinition("test",blueprint,StructurePlacement(listOf("ashfall_plain"))))))
        val a=files(b)
        val first=WorldsmithHashUtil.computeGenerationId(pack.manifest,a)
        assertEquals(first,WorldsmithHashUtil.computeGenerationId(pack.manifest,a+("preview.svg" to "ignored")))
        val changed=b.copy(palette=mapOf("stone" to BuildMaterial("minecraft:andesite")))
        assertNotEquals(first,WorldsmithHashUtil.computeGenerationId(pack.manifest,files(changed)))
        val index=WorldsmithJson.decode<StructureIndex>(a.getValue("structures.json"))
        assertEquals(b,StructurePackIO.load(index,a).structures.single().blueprint)
    }

    @Test fun `invalid placement is caught before Minecraft sees it`() {
        val pack=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val b=blueprint(listOf(BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(3,0,3),"stone")))
        val library=StructureLibrary(structures=listOf(WorldStructureDefinition("test",b,StructurePlacement(listOf("missing"),spacingChunks=2,separationChunks=2))))
        val codes=StructureValidator.validate(library,pack.biomes).map {it.code}
        assertTrue("UNKNOWN_STRUCTURE_BIOME" in codes)
        assertTrue("INVALID_STRUCTURE_SPACING" in codes)
    }

    @Test fun `refilled openings and overwritten operations produce repairable warnings`() {
        val b=blueprint(listOf(
            BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(4,0,4),"stone"),
            BuildOperation.Clear("opening",BuildPos(2,1,2),BuildPos(2,2,2)),
            BuildOperation.Fill("oops_wall",BuildPos(2,1,2),BuildPos(2,2,2),"stone"),
        ))
        val diagnostics=StructureValidator.validateBlueprint(b)
        assertTrue(diagnostics.any { it.code=="CLEAR_REGION_REFILLED" && "opening" in it.path })
        assertTrue(diagnostics.any { it.code=="OPERATION_FULLY_OVERWRITTEN" })
        assertTrue(diagnostics.all { it.severity==com.wjz.worldsmith.core.validation.DiagnosticSeverity.WARNING })
    }

    @Test fun `unused module declarations also count toward bounded input size`() {
        val operations=(0..StructureGeometryCompiler.MAX_OPERATIONS).map {
            BuildOperation.SetBlock("set_$it",BuildPos(0,0,0),"stone")
        }
        val b=blueprint(listOf(BuildOperation.SetBlock("root",BuildPos(0,0,0),"stone")),mapOf("unused" to operations))
        assertEquals("STRUCTURE_DECLARATION_BUDGET",StructureValidator.validateBlueprint(b).single().code)
    }

    @Test fun `all Minecraft air variants remain air in geometry checks`() {
        val b=blueprint(listOf(BuildOperation.SetBlock("air",BuildPos(0,0,0),"stone")))
            .copy(palette=mapOf("stone" to BuildMaterial("minecraft:cave_air")))
        assertEquals("EMPTY_STRUCTURE",StructureValidator.validateBlueprint(b).single().code)
    }

    @Test fun `isometric preview and chosen cutaway layer show the actual compiled geometry`() {
        val b=blueprint(listOf(
            BuildOperation.Shell("room",BuildPos(0,0,0),BuildPos(6,5,6),"stone"),
            BuildOperation.Clear("entry",BuildPos(2,1,0),BuildPos(3,3,0)),
        ))
        val geometry=StructureGeometryCompiler.compile(b)
        val full=StructurePreview.svg(geometry)
        val cut=StructurePreview.svg(geometry,2,true)
        assertTrue(full.contains("ISOMETRIC"))
        assertTrue(full.contains("<polygon"))
        assertTrue(cut.contains("CUTAWAY: Y &lt;= 2"))
        assertNotEquals(full,cut)
        assertTrue(StructurePreview.floorPlan(geometry,4).startsWith("Local Y=4"))
        assertThrows(IllegalArgumentException::class.java) { StructurePreview.svg(geometry,12,true) }
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(cut.byteInputStream())
    }
}
