package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemFormat7BundleTest {
    @Test fun `schema two validates bounded equipment and combined actions`() {
        val item = CustomItemDefinition("staff", "Staff", "a".repeat(64), maxStackSize = 1,
            equipment = ItemEquipment(ItemEquipmentType.MELEE), actions = listOf(ItemAction(
                cooldownTicks = 60, durabilityCost = 2, effects = listOf(ItemEffect.Projectile(), ItemEffect.Blink(6f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(2, listOf(item))).isEmpty())
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(1, listOf(item))).any { it.code == "items.schema" })
        val invalid = item.copy(actions = listOf(ItemAction(effects = listOf(ItemEffect.Blink(9f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(2, listOf(invalid))).any { it.code == "items.range" })
    }
    @Test fun `new writes use eight including required mechanics and abilities modules`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val pack = WorldContentBundleIO.create("An island", "The mist carries old songs.", base.terrain, base.biomes, base.features,
            base.structures, base.theme, base.blocks, base.creatures, base.assets, base.items, base.quests)
        assertEquals(10, pack.manifest.formatVersion)
        assertEquals(12, pack.manifest.modules.size)
        val files = WorldContentBundleIO.encode(pack)
        assertEquals(pack.manifest.id, WorldsmithHashUtil.computeGenerationId(files.manifest, files.texts, files.binaries))
    }
}
