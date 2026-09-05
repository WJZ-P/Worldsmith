package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructureExpansionTest {
    private val stone=BuildMaterial("minecraft:stone_bricks")
    private fun blueprint(ops:List<BuildOperation>,size:BuildPos=BuildPos(16,16,16))=StructureBlueprint(id="test",size=size,
        palette=mapOf("stone" to stone,"moss" to BuildMaterial("minecraft:mossy_stone_bricks"),"door" to BuildMaterial("minecraft:oak_door"),"stairs" to BuildMaterial("minecraft:stone_brick_stairs")),build=ops)

    @Test fun `ellipsoid shell has explicit interior air and preserves outside corners`() {
        val geometry=StructureGeometryCompiler.compile(blueprint(listOf(BuildOperation.Ellipsoid("dome",BuildPos(1,1,1),BuildPos(11,11,11),"stone",1))))
        val cells=geometry.voxels.associateBy {it.position}
        assertTrue(cells.getValue(BuildPos(6,6,6)).material.isAir())
        assertEquals(stone,cells.getValue(BuildPos(6,11,6)).material)
        assertNull(cells[BuildPos(1,1,1)])
        assertEquals(cells.keys,cells.keys.map {it.copy(x=12-it.x)}.toSet())
    }
    @Test fun `tapered columns produce a continuous cone rather than an isolated tip`() {
        val geometry=StructureGeometryCompiler.compile(blueprint(listOf(BuildOperation.Cylinder("cone",BuildPos(2,0,2),BuildPos(12,12,12),"stone",0.0))))
        val sizes=geometry.voxels.groupingBy {it.position.y}.eachCount()
        assertEquals((0..12).toSet(),sizes.keys)
        assertTrue(sizes.getValue(0)>sizes.getValue(12))
    }
    @Test fun `concave polygon extrusion is exact and self intersections are rejected`() {
        val points=listOf(BuildPoint2(0,0),BuildPoint2(5,0),BuildPoint2(5,2),BuildPoint2(2,2),BuildPoint2(2,5),BuildPoint2(0,5))
        val b=blueprint(listOf(BuildOperation.Polygon("shape",points,0,2,"stone")))
        val g=StructureGeometryCompiler.compile(b)
        assertEquals(48,g.voxels.size)
        assertFalse(g.voxels.any {it.position.x>=2&&it.position.z>=2})
        assertThrows(StructureBuildException::class.java) {StructureGeometryCompiler.compile(blueprint(listOf(BuildOperation.Polygon("bad",listOf(BuildPoint2(0,0),BuildPoint2(5,4),BuildPoint2(0,5),BuildPoint2(4,0)),0,2,"stone"))))}
    }
    @Test fun `arch carves a doorway and curve emits a six connected beam`() {
        val arch=StructureGeometryCompiler.compile(blueprint(listOf(BuildOperation.Arch("arch",BuildPos(0,0,0),BuildPos(10,10,2),4,"stone"))))
        val a=arch.voxels.associateBy {it.position}
        assertTrue(a.getValue(BuildPos(5,2,1)).material.isAir())
        assertEquals(stone,a.getValue(BuildPos(0,0,1)).material)
        assertEquals(stone,a.getValue(BuildPos(5,10,1)).material)
        val curve=StructureGeometryCompiler.compile(blueprint(listOf(BuildOperation.Curve("beam",listOf(BuildPos(1,1,1),BuildPos(4,13,4),BuildPos(13,2,12)),"stone"))))
        val unseen=curve.voxels.map {it.position}.toMutableSet();val queue=java.util.ArrayDeque<BuildPos>();queue.add(unseen.first());unseen.remove(queue.first)
        while(queue.isNotEmpty()) {val p=queue.removeFirst();for(d in listOf(BuildPos(1,0,0),BuildPos(-1,0,0),BuildPos(0,1,0),BuildPos(0,-1,0),BuildPos(0,0,1),BuildPos(0,0,-1))) {val next=BuildPos(p.x+d.x,p.y+d.y,p.z+d.z);if(unseen.remove(next))queue.add(next)}}
        assertTrue(unseen.isEmpty())
    }
    @Test fun `roof profile is bounded and disallows disconnected steps`() {
        val roof=BuildOperation.Roof("profile",BuildPos(1,4,1),BuildPos(13,7,13),"stone",profile=listOf(RoofKnot(0.0,0.3),RoofKnot(0.3,0.1),RoofKnot(1.0,1.0)))
        val b=blueprint(listOf(roof))
        assertTrue(StructureGeometryCompiler.compile(b).voxels.all {it.position.y in 4..7})
        assertThrows(StructureBuildException::class.java) {StructureGeometryCompiler.compile(b.copy(build=listOf(roof.copy(profile=listOf(RoofKnot(0.0,0.0),RoofKnot(0.99,0.0),RoofKnot(1.0,1.0))))))}
    }
    @Test fun `door halves and module transforms share the same state intent`() {
        val b=blueprint(listOf(BuildOperation.Instance("rotated","entry",BuildPos(8,0,8),BuildRotation.CLOCKWISE_90))).copy(
            modules=mapOf("entry" to listOf(BuildOperation.Door("door",BuildPos(0,1,0),"door",BuildFacing.NORTH))))
        val cells=StructureGeometryCompiler.compile(b).voxels
        assertEquals(setOf("lower","upper"),cells.map {it.material.properties["half"]}.toSet())
        assertTrue(cells.all {it.passable&&it.quarterTurns==1})
    }
    private fun stairHouse()=blueprint(listOf(
        BuildOperation.Clear("room",BuildPos(0,1,0),BuildPos(9,9,7)),
        BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(9,0,7),"stone"),
        BuildOperation.Staircase("stairs",BuildPos(2,0,2),BuildFacing.EAST,5,"stairs",2,3,"stone")
    ),BuildPos(10,10,8)).copy(access=StructureAccess(listOf(BuildPos(1,1,2)),listOf(BuildPos(6,5,2))))
    @Test fun `stairs clear headroom and connect the upper floor`() {
        val b=stairHouse();val g=StructureGeometryCompiler.compile(b)
        val nav=StructureNavigation.inspect(b,g.voxels)
        assertTrue(nav.diagnostics.isEmpty(),nav.diagnostics.toString())
        assertTrue(BuildPos(6,5,2) in nav.reachableFeet)
    }
    @Test fun `navigation detects sealed wings and does not assume KEEP is air`() {
        val b=blueprint(listOf(BuildOperation.Shell("room",BuildPos(0,0,0),BuildPos(7,4,7),"stone"),BuildOperation.Fill("divider",BuildPos(3,1,0),BuildPos(3,3,7),"stone")),BuildPos(8,5,8))
            .copy(access=StructureAccess(listOf(BuildPos(1,1,1)),listOf(BuildPos(5,1,1))))
        assertEquals("DISCONNECTED_STRUCTURE_ROUTE",StructureValidator.validateBlueprint(b).first().code)
        val keep=blueprint(listOf(BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(7,0,7),"stone")),BuildPos(8,5,8)).copy(access=b.access)
        assertEquals("UNWALKABLE_STRUCTURE_POINT",StructureValidator.validateBlueprint(keep).first().code)
    }
    @Test fun `weathering preserves floors and declared walking routes`() {
        val b=stairHouse().copy(variation=StructureVariation(count=4,decay=listOf(StructureDecay(listOf("stone","stairs"),1.0,exposedOnly=false))))
        for(g in StructureGeometryCompiler.compileVariants(b)) {
            assertTrue(StructureNavigation.inspect(b,g.voxels).diagnostics.isEmpty())
            assertTrue(g.voxels.filter {it.position.y==0}.all {!it.material.isAir()})
        }
    }
    @Test fun `weathering changes unprotected cells while an explicit beam reservation survives`() {
        val b=blueprint(listOf(BuildOperation.Fill("mass",BuildPos(0,0,0),BuildPos(5,5,5),"stone"))).copy(variation=StructureVariation(
            decay=listOf(StructureDecay(listOf("stone"),1.0,exposedOnly=false)),protectedAreas=listOf(BuildBox(BuildPos(2,1,2),BuildPos(2,5,2)))))
        val result=StructureGeometryCompiler.compile(b).voxels.associateBy {it.position}
        assertTrue(result.getValue(BuildPos(1,3,1)).material.isAir())
        assertEquals(stone,result.getValue(BuildPos(2,3,2)).material)
        assertEquals(stone,result.getValue(BuildPos(1,0,1)).material)
    }
    @Test fun `palette and module choices are repeatable and yield different bounded variants`() {
        val b=blueprint(listOf(BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(5,0,5),"stone"),BuildOperation.Choose("roof",listOf(WeightedModule("a"),WeightedModule("b")))))
            .copy(variation=StructureVariation(8,12,mapOf("stone" to listOf(WeightedMaterial("stone"),WeightedMaterial("moss")))),modules=mapOf(
                "a" to listOf(BuildOperation.Fill("low",BuildPos(1,1,1),BuildPos(3,2,3),"stone")),
                "b" to listOf(BuildOperation.Fill("tall",BuildPos(1,1,1),BuildPos(3,6,3),"stone"))))
        val variants=StructureGeometryCompiler.compileVariants(b)
        assertEquals(variants,StructureGeometryCompiler.compileVariants(WorldsmithJson.decode(WorldsmithJson.encode(b))))
        assertTrue(variants.map {it.voxels}.distinct().size>1)
    }
    @Test fun `typed content catches invalid item slots and excessive sign text`() {
        val b=blueprint(listOf(BuildOperation.SetBlock("block",BuildPos(1,1,1),"stone"))).copy(interactions=listOf(StructureInteraction.Container(BuildPos(1,1,1),items=listOf(StructureItem(99,"minecraft:apple")))))
        assertEquals("INVALID_CONTAINER_ITEM",StructureValidator.validateBlueprint(b).first().code)
        val sign=b.copy(interactions=listOf(StructureInteraction.Sign(BuildPos(1,1,1),listOf("x".repeat(161)))))
        assertEquals("INVALID_SIGN_TEXT",StructureValidator.validateBlueprint(sign).first().code)
    }

    private fun room(id:String,forward:Boolean):StructureBlueprint {
        val ports=mutableListOf(StructurePort("west",BuildPos(0,1,2),PortFacing.WEST,"hall"))
        if(forward)ports+=StructurePort("east",BuildPos(4,1,2),PortFacing.EAST,"hall","hall",true)
        return blueprint(listOf(BuildOperation.Shell("room",BuildPos(0,0,0),BuildPos(4,3,4),"stone"))+
            ports.map {BuildOperation.Clear(it.id,it.at,it.at.copy(y=2))},BuildPos(5,4,5)).copy(id=id,origin=BuildPos(2,0,2),ports=ports)
    }
    private fun assembly():WorldStructureDefinition {
        val root=room("root",true)
        val parts=mapOf("hall" to room("hall",true),"cap" to room("cap",false))
        return WorldStructureDefinition("settlement",root,StructurePlacement(listOf("ashfall_plain")),StructureAssembly(parts,mapOf("hall" to listOf(AssemblyChoice("hall",3),AssemblyChoice("cap",1))),4,4,3,32))
    }
    @Test fun `assembly compiles connected collision free pieces and uses terminal modules at its budget`() {
        val d=assembly();val catalog=StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(d)))
        assertEquals(catalog,StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(d))))
        for(plan in catalog.plans.getValue(d.id)) {
            assertTrue(plan.parts.size in 2..4);assertEquals(plan.parts.size-1,plan.connections.size)
            val positions=plan.parts.flatMap {p->p.geometry.voxels.map {StructureCatalogCompiler.transform(it.position,p)}}
            assertEquals(positions.size,positions.distinct().size,"pieces overlap")
            assertTrue(plan.bounds.to.x-plan.bounds.from.x+1>5)
        }
    }
    @Test fun `assembly child files roundtrip and participate in the content hash`() {
        val d=assembly();val library=StructureLibrary(structures=listOf(d));val files=StructurePackIO.files(library)
        assertEquals(setOf("structures/root.json","structures/hall.json","structures/cap.json","structures.json"),files.keys)
        assertEquals(library,StructurePackIO.load(WorldsmithJson.decode(files.getValue("structures.json")),files))
        val base=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val documents=mapOf("terrain.json" to WorldsmithJson.encode(base.terrain),"biomes.json" to WorldsmithJson.encode(base.biomes),"features.json" to WorldsmithJson.encode(base.features))+files
        val changed=documents+("structures/cap.json" to WorldsmithJson.encode(d.assembly!!.pieces.getValue("cap").copy(palette=mapOf("stone" to BuildMaterial("minecraft:andesite")))))
        assertNotEquals(WorldsmithHashUtil.computeGenerationId(base.manifest,documents),WorldsmithHashUtil.computeGenerationId(base.manifest,changed))
    }
    @Test fun `assembly rejects unknown pools and required dead ends before world creation`() {
        val d=assembly()
        assertEquals("UNKNOWN_ASSEMBLY_POOL",StructureValidator.validateDefinition(d.copy(assembly=d.assembly!!.copy(pools=mapOf("typo" to listOf(AssemblyChoice("cap")))))).first().code)
        assertEquals("REQUIRED_PORT_UNCONNECTED",StructureValidator.validateDefinition(d.copy(assembly=d.assembly!!.copy(maxPieces=1))).first().code)
    }
    @Test fun `solid vertical sockets support stacked landmarks without pretending they are doorways`() {
        val root=blueprint(listOf(BuildOperation.Fill("trunk",BuildPos(1,0,1),BuildPos(3,15,3),"stone")),BuildPos(5,16,5))
            .copy(id="lower",ports=listOf(StructurePort("top",BuildPos(2,15,2),PortFacing.UP,"trunk","crowns",true,false)))
        val crown=blueprint(listOf(BuildOperation.Fill("crown",BuildPos(0,0,0),BuildPos(8,9,8),"stone")),BuildPos(9,10,9))
            .copy(id="upper",ports=listOf(StructurePort("base",BuildPos(4,0,4),PortFacing.DOWN,"trunk",passage=false)))
        val d=WorldStructureDefinition("tree",root,StructurePlacement(listOf("ashfall_plain")),StructureAssembly(mapOf("upper" to crown),mapOf("crowns" to listOf(AssemblyChoice("upper"))),1,2,1,32))
        val plan=StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(d))).plans.getValue("tree").single()
        assertEquals(2,plan.parts.size);assertEquals(26,plan.bounds.to.y+1)
        assertEquals(16,plan.parts[1].offset.y)
    }
    @Test fun `walkable ports must connect internally even without explicit access metadata`() {
        val b=room("sealed",true).copy(build=room("sealed",true).build+BuildOperation.Fill("wall",BuildPos(2,1,0),BuildPos(2,2,4),"stone"))
        assertEquals("DISCONNECTED_STRUCTURE_ROUTE",StructureValidator.validateBlueprint(b).first().code)
    }
}
