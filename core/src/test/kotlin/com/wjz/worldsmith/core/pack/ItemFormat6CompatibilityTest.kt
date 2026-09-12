package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ItemFormat6CompatibilityTest {
    @Test fun `legacy item normalization keeps exactly the original schema one fields`() {
        val hash = "a".repeat(64)
        val legacy = """{"schemaVersion":1,"items":[{"id":"token","displayName":"Token","textureAsset":"$hash"}]}"""
        val normalized = LegacyItemsV1.normalize(legacy).jsonObject
        val item = normalized.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "displayName", "textureAsset", "kind", "maxStackSize", "rarity", "description", "themeRole"), item.keys)
        assertEquals("RESOURCE", item.getValue("kind").jsonPrimitive.content)
        assertEquals(64, item.getValue("maxStackSize").jsonPrimitive.int)
        assertFalse(item.containsKey("equipment")); assertFalse(item.containsKey("actions")); assertFalse(item.containsKey("consumable"))
        assertEquals(normalized, Json.parseToJsonElement(LegacyItemsV1.encode(WorldsmithJson.decode(legacy))))
    }
    @Test fun `abilities never leak into legacy encoding`() {
        val item = CustomItemDefinition("blade", "Blade", "a".repeat(64), maxStackSize = 1,
            equipment = ItemEquipment(ItemEquipmentType.MELEE))
        assertThrows(IllegalArgumentException::class.java) { LegacyItemsV1.encode(CustomItemLibrary(2, listOf(item))) }
    }
    @Test fun `schema two validates bounded equipment and combined actions`() {
        val item = CustomItemDefinition("staff", "Staff", "a".repeat(64), maxStackSize = 1,
            equipment = ItemEquipment(ItemEquipmentType.MELEE), actions = listOf(ItemAction(
                cooldownTicks = 60, durabilityCost = 2, effects = listOf(ItemEffect.Projectile(), ItemEffect.Blink(6f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(2, listOf(item))).isEmpty())
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(1, listOf(item))).any { it.code == "items.schema" })
        val invalid = item.copy(actions = listOf(ItemAction(effects = listOf(ItemEffect.Blink(9f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(2, listOf(invalid))).any { it.code == "items.range" })
    }
    @Test fun `new writes use six without changing the nine module set`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val pack = WorldContentBundleIO.create("An island", "The mist carries old songs.", base.terrain, base.biomes, base.features,
            base.structures, base.theme, base.blocks, base.creatures, base.assets, base.items, base.quests)
        assertEquals(6, pack.manifest.formatVersion)
        assertEquals(9, pack.manifest.modules.size)
        val files = WorldContentBundleIO.encode(pack)
        assertEquals(pack.manifest.id, WorldsmithHashUtil.computeGenerationId(files.manifest, files.texts, files.binaries))
    }
    @Test fun `retained format five worlds keep their published identities when reembedded`() {
        val root = Path.of(System.getProperty("worldsmith.projectRoot", ".."))
        val packs = root.resolve("run/config/worldsmith/packs")
        val ids = listOf("65dc1880e79e1197c4d95f16e776a3d20abfe9bac01e78a1a42c0918f1aa062b", "f033169f6d40d2e391bd62d6bb3a949f3ce26090b1265a8c8d1b10606ab20ec0")
        assumeTrue(ids.all { Files.isRegularFile(packs.resolve(it).resolve("worldsmith.json")) }, "Optional local retained-world fixtures")
        for (id in ids) {
            val pack = WorldsmithPackLoader.loadDirectory(packs.resolve(id))
            assertEquals(5, pack.manifest.formatVersion); assertEquals(id, pack.computedId)
            val encoded = WorldContentBundleIO.encode(pack)
            assertEquals(id, encoded.manifest.id)
            assertFalse(encoded.texts.getValue("items.json").contains("\"equipment\""))
        }
    }
}
