package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.ability.AbilityLibrary
import com.wjz.worldsmith.core.ability.AbilityProgramDefinition
import com.wjz.worldsmith.core.creatureauthoring.CreatureBuilder
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityEventBindingsTest {
    private fun binding(start: String = "use_start") = AbilityEventBinding("channel", "events", listOf(start))
    private fun item(bindings: List<AbilityEventBinding> = listOf(binding()), ticks: Int = 0) =
        CustomItemDefinition("focus", "Focus", "a".repeat(64), abilityBindings = bindings, maxUseTicks = ticks)

    @Test fun `installed hooks are explicit while routing keeps start listen and cancel distinct`() {
        val valid = binding().copy(listenTo = listOf("use_tick", "use_release"), cancelOn = listOf("use_cancel"))
        assertTrue(AbilityEventBindings.validate(listOf(valid), "item", "bindings").isEmpty())
        assertTrue(AbilityEventBindings.validate(listOf(valid), "creature", "bindings").any { it.code == "ability.binding.event" })
        val invalid = listOf(valid.copy(startOn = emptyList()), valid.copy(listenTo = listOf("use_start")),
            valid.copy(cancelOn = listOf("use_tick")), valid.copy(startOn = listOf("invented_spell")), valid.copy(range = Double.NaN))
        invalid.forEach { assertFalse(AbilityEventBindings.validate(listOf(it), "item", "bindings").isEmpty()) }
        assertTrue(AbilityEventBindings.validate(listOf(valid, valid), "item", "bindings").any { it.code == "ability.binding.duplicate" })
    }

    @Test fun `bindings deeply freeze every event list without gaining mutable aliases`() {
        val starts = mutableListOf("use_start"); val listens = mutableListOf("use_tick"); val cancels = mutableListOf("use_cancel")
        val original = mutableListOf(binding().copy(startOn = starts, listenTo = listens, cancelOn = cancels))
        val frozen = CustomItemValidation.freeze(CustomItemLibrary(4, listOf(item(original, 40))))
        starts.clear(); listens.clear(); cancels.clear(); original.clear()
        val kept = frozen.items.single().abilityBindings.single()
        assertEquals(listOf("use_start"), kept.startOn); assertEquals(listOf("use_tick"), kept.listenTo); assertEquals(listOf("use_cancel"), kept.cancelOn)
        assertThrows(UnsupportedOperationException::class.java) { (kept.startOn as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.items.single().abilityBindings as MutableList).clear() }
    }

    @Test fun `periodic creature observations have an explicit bounded interval and schema`() {
        val periodic = binding("tick").copy(intervalTicks = 40)
        assertTrue(AbilityEventBindings.validate(listOf(periodic), "creature", "bindings").isEmpty())
        for (bad in listOf(periodic.copy(intervalTicks = 0), periodic.copy(intervalTicks = 1201), periodic.copy(startOn = listOf("spawn"))))
            assertTrue(AbilityEventBindings.validate(listOf(bad), "creature", "bindings").any { it.code == "ability.binding.interval" })
        assertTrue(AbilityEventBindings.validate(listOf(binding().copy(intervalTicks = 40)), "item", "bindings").any { it.code == "ability.binding.interval" })
        val builder = CreatureBuilder.create("observer", "Observer", CreatureCategory.PASSIVE).atlas(32, 32).abilityBinding(periodic)
        builder.bone("body", null, 0f, 24f, 0f).cube("body", -2f, -8f, -2f, 4, 8, 4).end()
        val definition = builder.guide().definition
        assertEquals(6, builder.recipe().schemaVersion)
        assertEquals(6, CreatureSounds.requiredSchema(definition))
        assertTrue(CustomCreatureValidator.validate(CreatureLibrary(6, listOf(definition))).isEmpty())
        assertTrue(CustomCreatureValidator.validate(CreatureLibrary(5, listOf(definition))).any { it.path.endsWith(".abilityBindings") })
        assertEquals(40, CustomCreatureValidator.freeze(CreatureLibrary(6, listOf(definition))).creatures.single().abilityBindings.single().intervalTicks)
    }

    @Test fun `item schema and native held use conflicts are validated before activation`() {
        val held = item(listOf(binding().copy(listenTo = listOf("use_tick", "use_release"))), 40)
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(held))).isEmpty())
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(3, listOf(held))).any { it.code == "items.schema" })
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(held.copy(maxUseTicks = 0)))).any { it.code == "items.held_events" })
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(held.copy(consumable = ItemConsumable())))).any { it.code == "items.use_conflict" })
        val heldWithImmediate = held.copy(actions = listOf(ItemAction(effects = listOf(ItemEffect.Heal(1f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(heldWithImmediate))).any { it.code == "items.use_conflict" })
        val doubled = item().copy(actions = listOf(ItemAction(effects = listOf(ItemEffect.RunProgram("events")))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(doubled))).any { it.code == "items.use_conflict" })
        val immediateFood = item().copy(consumable = ItemConsumable())
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(immediateFood))).any { it.code == "items.use_conflict" })
        val immediatePaidAction = item().copy(actions = listOf(ItemAction(consumeCount = 1, effects = listOf(ItemEffect.Heal(1f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(immediatePaidAction))).any { it.code == "items.use_conflict" })
    }

    @Test fun `confirmed melee events require the actual native weapon component profile`() {
        val noWeapon = item(listOf(binding("melee_hit")))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(noWeapon))).any { it.code == "items.melee_equipment" })
        val weapon = noWeapon.copy(maxStackSize = 1, equipment = ItemEquipment(ItemEquipmentType.MELEE))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(weapon))).isEmpty())
        val duplicate = weapon.copy(actions = listOf(ItemAction(trigger = ItemActionTrigger.MELEE_HIT, effects = listOf(ItemEffect.Heal(1f)))))
        assertTrue(CustomItemValidation.validate(CustomItemLibrary(4, listOf(duplicate))).any { it.code == "items.use_conflict" })
    }

    @Test fun `passive creature builder preserves event programs without a hostile combat strategy`() {
        val builder = CreatureBuilder.create("guide", "Guide", CreatureCategory.PASSIVE).atlas(32, 32)
            .abilityBinding(binding("spawn").copy(listenTo = listOf("interact_entity", "hurt")))
        builder.bone("body", null, 0f, 24f, 0f).cube("body", -2f, -8f, -2f, 4, 8, 4).end()
        val recipe = builder.recipe(); val guide = builder.guide().definition
        assertEquals(5, recipe.schemaVersion); assertEquals(5, CreatureSounds.requiredSchema(guide)); assertNull(guide.ability)
        assertEquals(recipe.abilityBindings, guide.abilityBindings)
        assertTrue(CustomCreatureValidator.validate(CreatureLibrary(5, listOf(guide))).isEmpty())
        assertTrue(CustomCreatureValidator.validate(CreatureLibrary(4, listOf(guide))).any { it.path.endsWith(".abilityBindings") })
    }

    @Test fun `new host defaults are omitted and actual bindings participate in pack identity and references`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        assertEquals(base.computedId, WorldContentBundleIO.encode(base).manifest.id)
        assertFalse(WorldsmithJson.encode(item(emptyList())).contains("abilityBindings"))
        assertFalse(WorldsmithJson.encode(item(emptyList())).contains("maxUseTicks"))
        val builder = CreatureBuilder.create("guide", "Guide", CreatureCategory.PASSIVE).atlas(32, 32)
        builder.bone("body", null, 0f, 24f, 0f).cube("body", -2f, -8f, -2f, 4, 8, 4).end()
        val guide = builder.guide()
        assertFalse(WorldsmithJson.encode(guide.definition).contains("abilityBindings"))
        val programs = AbilityLibrary(programs = listOf(AbilityProgramDefinition("events", "Events", "on start { wait 1; }")))
        fun create(bound: Boolean) = WorldContentBundleIO.create("Events", "Shared source bindings", base.terrain, base.biomes, base.features,
            base.structures, base.theme, creatures = CreatureLibrary(5, listOf(guide.definition.copy(abilityBindings = if (bound) listOf(binding("spawn")) else emptyList()))),
            assets = mapOf(guide.asset.id to guide.png), items = CustomItemLibrary(4, listOf(item(if (bound) listOf(binding()) else emptyList()).copy(textureAsset = guide.asset.id))),
            abilities = programs)
        val pack = create(true)
        assertNotEquals(create(false).computedId, pack.computedId)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty(), WorldsmithPackValidator.validate(pack).toString())
        val links = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(pack)).catalog.entries
            .flatMap { it.references }.filter { it.target == ContentKey("ability", "events") }
        assertEquals(2, links.size)
        assertTrue(links.all { it.path.endsWith("abilityBindings[0].program") })
        val broken = pack.copy(abilities = AbilityLibrary())
        assertEquals(2, WorldsmithPackValidator.validate(broken).count { it.code == "CONTENT_REFERENCE_MISSING" })
    }
}
