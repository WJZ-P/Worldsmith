package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructureAnchorTest {
    private val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
    private val floor = StructureBlueprint(id="floor", size=BuildPos(3,2,3), palette=mapOf("stone" to BuildMaterial("minecraft:stone")),
        build=listOf(BuildOperation.Fill("floor",BuildPos(0,0,0),BuildPos(2,0,2),"stone")))

    private fun pack(target: StructureAnchorTarget? = null, placement: AnchorPlacement = AnchorPlacement.Fixed(0,0)): WorldsmithPack {
        val shape = (base.terrain.shape as TerrainShape.Procedural).copy(anchors=listOf(Anchor("holy_peak",placement,100,30.0)))
        return base.copy(terrain=base.terrain.copy(shape=shape), structures=StructureLibrary(structures=listOf(
            WorldStructureDefinition("temple",floor,StructurePlacement(listOf(base.biomes.biomes.first().id),anchor=target)))))
    }

    @Test fun `omitting the anchor keeps the existing random spread JSON and defaults`() {
        val placement = WorldsmithJson.decode<StructurePlacement>("""{"biomes":["ashfall_plain"]}""")
        assertNull(placement.anchor)
        assertEquals(24,placement.spacingChunks)
        assertEquals(8,placement.separationChunks)
        assertFalse("\"anchor\"" in WorldsmithJson.encode(placement),"an absent optional anchor must not change the normal source document")
        assertTrue(WorldsmithPackValidator.validate(pack()).none { it.severity==DiagnosticSeverity.ERROR })
    }

    @Test fun `fixed scattered and line anchors are opt in cross document links`() {
        for (placement in listOf(AnchorPlacement.Fixed(-31,17),AnchorPlacement.Scattered(1024),AnchorPlacement.Line(0,0,600,900))) {
            val pack = pack(StructureAnchorTarget("holy_peak"),placement)
            val errors = WorldsmithPackValidator.validate(pack).filter { it.severity==DiagnosticSeverity.ERROR }
            assertTrue(errors.isEmpty(),errors.toString())
        }
    }

    @Test fun `unknown anchors and non procedural worlds are rejected before Minecraft export`() {
        assertTrue(WorldsmithPackValidator.validate(pack(StructureAnchorTarget("typo"))).any { it.code=="UNKNOWN_ANCHOR" && "structures" in it.path })
        val p = pack(StructureAnchorTarget("holy_peak")).let { it.copy(terrain=it.terrain.copy(shape=TerrainShape.Vanilla())) }
        assertTrue(WorldsmithPackValidator.validate(p).any { it.code=="ANCHOR_REQUIRES_PROCEDURAL_TERRAIN" && "structures" in it.path })
    }

    @Test fun `anchor fields are bounded and random spacing is not silently consumed`() {
        val p=pack(StructureAnchorTarget("holy_peak",offsetX=4097,along=1.2))
        val codes=WorldsmithPackValidator.validate(p).map { it.code }
        assertTrue("STRUCTURE_ANCHOR_OFFSET_OUT_OF_RANGE" in codes)
        assertTrue("STRUCTURE_ANCHOR_ALONG_OUT_OF_RANGE" in codes)
        assertTrue("UNUSED_ANCHOR_ALONG" in codes)
        val entry=p.structures.structures.single().let { it.copy(placement=it.placement.copy(spacingChunks=30)) }
        assertTrue(StructureValidator.validate(StructureLibrary(structures=listOf(entry)),p.biomes).any { it.code=="UNUSED_STRUCTURE_SPACING" })
        assertTrue(WorldsmithPackValidator.validate(pack(StructureAnchorTarget("holy_peak",offsetX=4096),AnchorPlacement.Fixed(29_998_999,0)))
            .any { it.code=="STRUCTURE_ANCHOR_OUTSIDE_WORLD" })
    }

    @Test fun `line position offsets and anchor identity survive portable storage and affect the hash`() {
        val p=pack(StructureAnchorTarget("holy_peak",offsetX=13,offsetZ=-7,along=0.25),AnchorPlacement.Line(-120,-90,800,170))
        val files=StructurePackIO.files(p.structures)
        val loaded=StructurePackIO.load(WorldsmithJson.decode(files.getValue("structures.json")),files)
        assertEquals(p.structures,loaded)
        fun hash(library:StructureLibrary)=WorldsmithHashUtil.computeGenerationId(p.manifest,mapOf(
            "terrain.json" to WorldsmithJson.encode(p.terrain),"biomes.json" to WorldsmithJson.encode(p.biomes),"features.json" to WorldsmithJson.encode(p.features)
        )+StructurePackIO.files(library))
        val entry=p.structures.structures.single()
        val changed=entry.copy(placement=entry.placement.copy(anchor=entry.placement.anchor!!.copy(offsetX=14)))
        assertNotEquals(hash(p.structures),hash(StructureLibrary(structures=listOf(changed))))
    }
}
