package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.AnchorPlacement
import com.wjz.worldsmith.core.model.TerrainShape
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MechanicDiscoveryExampleTest {
    @Test fun `the reproducible archive round trips all playable content`(@TempDir output: Path) {
        val pack = MechanicDiscoveryExample.create()
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { "${it.path}: ${it.message}" })
        assertEquals(pack.computedId, MechanicDiscoveryExample.create().computedId)
        val file = output.resolve("lantern-vault.wspack")
        val written = WorldsmithResourceArchive.write(pack, file)
        val read = WorldsmithResourceArchive.read(file)
        assertEquals(written, read.info)
        assertEquals(pack.mechanics, read.pack.mechanics)
        assertEquals(pack.quests, read.pack.quests)
        assertEquals(pack.structures.structures, read.pack.structures.structures)
        assertEquals(7, read.pack.manifest.formatVersion)
    }

    @Test fun `missing material comes from a real container and guides reproduce the executable rules`() {
        val pack = MechanicDiscoveryExample.create()
        val structure = pack.structures.structures.single()
        val geometry = StructureGeometryCompiler.compile(structure.blueprint)
        val voxels = geometry.voxels.associateBy { it.position }
        val altar = pack.mechanics.mechanics.single { it.id == MechanicDiscoveryExample.ALTAR }
        val guide = MechanicGuides.describe(altar).rules.single()
        val anchor = MechanicDiscoveryExample.ALTAR_POSITION
        assertEquals(altar.rules.single(), guide.rule)
        assertEquals(setOf(0, 1, 2), guide.layers.map { it.y }.toSet())
        assertEquals(".", MechanicGuides.symbolAt(guide, 0, 1, 0))
        assertNull(MechanicGuides.symbolAt(guide, 1, 1, 0))
        val missing = guide.rule.pattern.filter { cell ->
            val offset = cell.offset
            val actual = voxels[BuildPos(anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z)]?.material
            actual?.block != cell.block.block || !cell.block.properties.all { actual.properties[it.key] == it.value }
        }
        assertEquals(listOf("minecraft:iron_block"), missing.map { it.block.block })
        val supplies = structure.blueprint.interactions.filterIsInstance<StructureInteraction.Container>().single()
        assertEquals(MechanicDiscoveryExample.MATERIALS_POSITION, supplies.at)
        assertTrue(supplies.items.any { it.item == missing.single().block.block && it.count == 1 })
        assertTrue(supplies.items.any { it.item == "minecraft:iron_sword" })
        val clues = structure.blueprint.interactions.filterIsInstance<StructureInteraction.Sign>()
        assertEquals(4, clues.size)
        assertTrue(clues.any { it.front.any { line -> "日志" in line } })
        assertTrue(clues.all { voxels[it.at]?.material?.block == "minecraft:oak_sign" })
        assertTrue(clues.all { voxels[it.at]?.material?.properties?.get("rotation") == "8" },
            "Clues face the north-to-south arrival route, rather than exposing blank backs")
        assertEquals(AnchorPlacement.Fixed(0, 0), (pack.terrain.shape as TerrainShape.Procedural).anchors.single().placement)
        assertEquals("lantern_court", structure.placement.anchor?.id)
    }

    @Test fun `only actual guardian death supplies the gate key and both activations drive the journal`() {
        val pack = MechanicDiscoveryExample.create()
        val guardian = pack.creatures.creatures.single()
        assertTrue(guardian.spawn.biomes.isEmpty())
        val drop = guardian.drops.single()
        assertTrue(drop.requirePlayerKill)
        assertEquals(1.0, drop.chance)
        assertEquals(1, drop.minCount)
        assertEquals(1, drop.maxCount)
        val gate = pack.mechanics.mechanics.single { it.id == MechanicDiscoveryExample.GATE }.rules.single()
        assertEquals(drop.item, gate.heldItem?.item)
        assertEquals(1, gate.heldItem?.count)
        assertEquals(2, gate.actions.filterIsInstance<MechanicAction.SetBlock>().size)
        assertEquals(setOf(MechanicDiscoveryExample.ALTAR, MechanicDiscoveryExample.GATE),
            pack.quests.quests.single().objectives.filterIsInstance<QuestObjective.ActivateMechanic>().map { it.mechanic }.toSet())
        assertFalse(pack.structures.structures.single().blueprint.interactions.filterIsInstance<StructureInteraction.Container>()
            .flatMap { it.items }.any { it.item == drop.item })
    }
}
