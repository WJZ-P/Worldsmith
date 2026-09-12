package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Decode the real MCP contract example through the DTO rather than validating a second hand-written schema. */
class ItemAuthoringContractTest {
    private val repository=ClasspathPromptTemplateRepository()
    private fun prompt(id:String)=repository.load(PromptTemplateRef(id)).systemPrompt
    private val contract get()=prompt("contract/items")
    private fun example():CustomItemLibrary {
        val json=requireNotNull(Regex("```json\\s*\\n([\\s\\S]*?)\\n```").find(contract)).groupValues[1]
            .replace("<ICON_SHA256>","a".repeat(64)).replace("<ARMOR_SHA256>","b".repeat(64))
        return WorldsmithJson.decode<CustomItemLibrary>(json)
    }

    @Test fun `the published schema two example uses actual DTO fields and all fixed effects`() {
        val library=example()
        assertEquals(2,library.schemaVersion)
        assertEquals(4,library.items.size)
        assertTrue(CustomItemValidation.validate(library).isEmpty(),CustomItemValidation.validate(library).toString())
        val effects=library.items.flatMap {it.actions}.flatMap {it.effects}
        assertTrue(effects.any {it is ItemEffect.Heal})
        assertTrue(effects.any {it is ItemEffect.Feed})
        assertTrue(effects.any {it is ItemEffect.Status})
        assertTrue(effects.any {it is ItemEffect.Projectile})
        assertTrue(effects.any {it is ItemEffect.Blink})
        for(type in ItemEquipmentType.entries) assertTrue(Regex("\\b${type.name}\\b").containsMatchIn(contract),type.name)
        for(tier in ItemMiningTier.entries) assertTrue(Regex("\\b${tier.name}\\b").containsMatchIn(contract),tier.name)
    }

    @Test fun `examples keep food costs and melee equipment requirements consistent with the runtime`() {
        val library=example()
        val food=library.items.single {it.consumable!=null}
        assertTrue(food.actions.all {it.trigger==ItemActionTrigger.USE && it.consumeCount==0 && it.durabilityCost==0})
        assertTrue(library.items.filter {item -> item.actions.any {it.trigger==ItemActionTrigger.MELEE_HIT}}
            .all {it.equipment?.isArmor()==false})
        val armor=library.items.single {it.equipment?.isArmor()==true}
        val wearable=requireNotNull(armor.equipment)
        assertEquals("b".repeat(64),wearable.textureAsset)
        assertNotEquals(armor.textureAsset,wearable.textureAsset)
        assertTrue("Crouch-right-click while holding armor" in contract)
        assertTrue("food actions require consumeCount=0" in contract)
        assertTrue("world scope + logical item ID" in contract)
        assertTrue("64x32, 128x64, 256x128 or 512x256" in contract)
    }

    @Test fun `example lore is player facing while version and compatibility notes stay in the contract`() {
        val library=example()
        val theme=WorldTheme(title="The River Oath",premise="The river beacons have gone dark.",playerRole="A traveller carrying the last ember.",
            mainConflict="Reunite the villages along the river.",worldRules=listOf("Each crossing keeps a watchfire."),
            beats=listOf(WorldNarrativeBeat("arrival","A road of embers","Seek the first ferry beacon.",listOf(ContentKey("item","ember_focus")))))
        assertTrue(PlayerTextPolicy.validate(theme.title,theme.premise,theme,library,QuestLibrary()).isEmpty())
        for(id in listOf("contract/items","contract/theme","contract/quests","contract/world_design","world_entry")) {
            val text=prompt(id)
            assertTrue(Regex("format[ -]${WorldContentBundleIO.FORMAT_VERSION}",RegexOption.IGNORE_CASE).containsMatchIn(text),id)
            assertTrue("PlayerTextPolicy" in text && "PLAYER_TEXT_ENGINEERING_LEAK" in text,id)
            assertTrue("authoring diagnostics/receipts" in text,id)
        }
    }
}
