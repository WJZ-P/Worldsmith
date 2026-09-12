package com.wjz.worldsmith.core.content

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class CustomItemActionValidationTest {
    private val texture = "a".repeat(64)
    private fun item(equipment: ItemEquipment?) = CustomItemDefinition(
        "oathblade", "Oathblade", texture, maxStackSize = 1, equipment = equipment,
        actions = listOf(ItemAction(trigger = ItemActionTrigger.MELEE_HIT, effects = listOf(ItemEffect.Heal(1f)))),
    )
    private fun diagnostics(equipment: ItemEquipment?) = CustomItemValidation.validate(CustomItemLibrary(2, listOf(item(equipment))))

    @Test fun meleeHitRequiresAnActualNativeWeaponProfile() {
        assertTrue(diagnostics(null).any { it.code == "items.melee_equipment" })
        assertTrue(diagnostics(ItemEquipment(ItemEquipmentType.HELMET, textureAsset = texture)).any { it.code == "items.melee_equipment" })
    }

    @Test fun meleeAndMiningEquipmentHaveNativeSuccessfulHitCallbacks() {
        for (type in listOf(ItemEquipmentType.MELEE, ItemEquipmentType.AXE, ItemEquipmentType.PICKAXE, ItemEquipmentType.SHOVEL, ItemEquipmentType.HOE))
            assertFalse(diagnostics(ItemEquipment(type)).any { it.code == "items.melee_equipment" }, type.name)
    }
}
