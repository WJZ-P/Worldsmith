package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable enum class CustomItemKind { RESOURCE, RELIC }
@Serializable enum class CustomItemRarity { COMMON, UNCOMMON, RARE, EPIC }
@Serializable enum class ItemEquipmentType { MELEE, AXE, PICKAXE, SHOVEL, HOE, HELMET, CHESTPLATE, LEGGINGS, BOOTS }
@Serializable enum class ItemMiningTier { WOOD, STONE, IRON, DIAMOND, NETHERITE }
@Serializable enum class ItemActionTrigger { USE, MELEE_HIT }
@Serializable enum class ItemEffectTarget { SELF, TARGET }

@Serializable data class ItemEquipment @JvmOverloads constructor(
    val type: ItemEquipmentType,
    val durability: Int = 256,
    val attackDamage: Double = 3.0,
    val attackSpeed: Double = -2.4,
    val armor: Double = 0.0,
    val toughness: Double = 0.0,
    val knockbackResistance: Double = 0.0,
    val miningSpeed: Float = 4.0f,
    val miningTier: ItemMiningTier = ItemMiningTier.IRON,
    val textureAsset: String? = null,
) {
    fun isArmor() = type in setOf(ItemEquipmentType.HELMET, ItemEquipmentType.CHESTPLATE, ItemEquipmentType.LEGGINGS, ItemEquipmentType.BOOTS)
}
@Serializable data class ItemStatusEffect @JvmOverloads constructor(
    val effect: String, val durationTicks: Int = 200, val amplifier: Int = 0,
)
@Serializable data class ItemConsumable @JvmOverloads constructor(
    val nutrition: Int = 0, val saturation: Float = 0.0f, val consumeSeconds: Float = 1.6f,
    val alwaysEdible: Boolean = false, val effects: List<ItemStatusEffect> = emptyList(),
)
@Serializable sealed class ItemEffect {
    @Serializable @SerialName("heal") data class Heal(val amount: Float, val target: ItemEffectTarget = ItemEffectTarget.SELF) : ItemEffect()
    @Serializable @SerialName("feed") data class Feed(val nutrition: Int, val saturation: Float = 0.0f) : ItemEffect()
    @Serializable @SerialName("status") data class Status(val effect: String, val durationTicks: Int = 200, val amplifier: Int = 0, val target: ItemEffectTarget = ItemEffectTarget.SELF) : ItemEffect()
    @Serializable @SerialName("projectile") data class Projectile(
        val damage: Float = 4.0f, val speed: Float = 1.5f, val gravity: Float = 0.03f,
        val lifetimeTicks: Int = 80, val hitEffects: List<ItemStatusEffect> = emptyList(),
    ) : ItemEffect()
    @Serializable @SerialName("blink") data class Blink(val distance: Float = 6.0f) : ItemEffect()
}
@Serializable data class ItemAction @JvmOverloads constructor(
    val trigger: ItemActionTrigger = ItemActionTrigger.USE,
    val cooldownTicks: Int = 20,
    val consumeCount: Int = 0,
    val durabilityCost: Int = 0,
    val effects: List<ItemEffect>,
)
@Serializable data class CustomItemDefinition @JvmOverloads constructor(
    val id: String,
    val displayName: String,
    val textureAsset: String,
    val kind: CustomItemKind = CustomItemKind.RESOURCE,
    val maxStackSize: Int = 64,
    val rarity: CustomItemRarity = CustomItemRarity.COMMON,
    val description: String = "",
    val themeRole: String = "",
    val equipment: ItemEquipment? = null,
    val consumable: ItemConsumable? = null,
    val actions: List<ItemAction> = emptyList(),
)
@Serializable data class CustomItemLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val items: List<CustomItemDefinition> = emptyList(),
)

object CustomItemValidation {
    const val MAX_ITEMS = 256
    const val MAX_TEXTURE_BYTES = 1024 * 1024
    const val LOGICAL_PREFIX = "worldsmith:item/"
    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val NATIVE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    @JvmStatic fun validate(library: CustomItemLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        fun number(path: String, value: Double, min: Double, max: Double) {
            if (!value.isFinite() || value < min || value > max) error(path, "items.range", "Expected finite value in $min..$max")
        }
        fun status(path: String, effect: String, ticks: Int, amplifier: Int) {
            if (!NATIVE_ID.matches(effect)) error("$path.effect", "items.effect", "Status effects name a registered effect resource id")
            if (ticks !in 1..72000) error("$path.durationTicks", "items.duration", "Effect duration supports 1..72000 ticks")
            if (amplifier !in 0..9) error("$path.amplifier", "items.amplifier", "Effect amplifier supports 0..9")
        }
        if (library.schemaVersion !in 1..2) error("schemaVersion", "items.schema", "Item library schema must be 1 or 2")
        if (library.items.size > MAX_ITEMS) {
            error("items", "items.capacity", "At most $MAX_ITEMS item definitions per world")
            return@buildList
        }
        val seen = mutableSetOf<String>()
        library.items.forEachIndexed { index, item ->
            val path = "items[$index]"
            if (!validId(item.id)) error("$path.id", "items.id", "Use a local lowercase item id of 1..64 characters; '..' and a trailing dot are reserved")
            if (!seen.add(item.id)) error("$path.id", "items.duplicate", "Duplicate item '${item.id}'")
            if (item.displayName.isBlank() || item.displayName.length > 128 || item.displayName.any(Char::isISOControl)) error("$path.displayName", "items.name", "Display names need 1..128 printable characters")
            if (!WorldContentRegistry.SHA256.matches(item.textureAsset)) error("$path.textureAsset", "items.texture", "Icons reference their actual lowercase PNG SHA-256")
            if (item.maxStackSize !in 1..64) error("$path.maxStackSize", "items.stack_size", "Item stacks support 1..64 items")
            if (item.description.length > 2048 || item.description.any { it.isISOControl() && it != '\n' }) error("$path.description", "items.description", "Description supports up to 2048 plain-text characters")
            if (item.description.lineSequence().count() > 256) error("$path.description", "items.description_lines", "Item lore supports at most 256 lines")
            if (item.themeRole.length > 2048) error("$path.themeRole", "items.theme_role", "Theme role is limited to 2048 characters")
            if (library.schemaVersion == 1 && (item.equipment != null || item.consumable != null || item.actions.isNotEmpty())) error(path, "items.schema", "Equipment, consumption and actions require items schema 2 / bundle format 6")
            item.equipment?.let { e ->
                if (item.maxStackSize != 1) error("$path.maxStackSize", "items.equipment_stack", "Equipment is non-stackable")
                if (e.durability !in 1..100000) error("$path.equipment.durability", "items.durability", "Durability supports 1..100000")
                number("$path.equipment.attackDamage", e.attackDamage, 0.0, 1024.0)
                number("$path.equipment.attackSpeed", e.attackSpeed, -3.9, 20.0)
                number("$path.equipment.armor", e.armor, 0.0, 30.0)
                number("$path.equipment.toughness", e.toughness, 0.0, 20.0)
                number("$path.equipment.knockbackResistance", e.knockbackResistance, 0.0, 1.0)
                number("$path.equipment.miningSpeed", e.miningSpeed.toDouble(), 0.1, 128.0)
                if (e.isArmor() && e.textureAsset == null) error("$path.equipment.textureAsset", "items.armor_texture", "Armor needs its separate humanoid UV texture")
                if (e.textureAsset != null && !WorldContentRegistry.SHA256.matches(e.textureAsset)) error("$path.equipment.textureAsset", "items.armor_texture", "Armor texture references an actual PNG SHA-256")
                if (!e.isArmor() && e.textureAsset != null) error("$path.equipment.textureAsset", "items.armor_texture", "Wearable textures apply only to armor")
            }
            item.consumable?.let { c ->
                if (item.equipment != null) error(path, "items.consumable_equipment", "Equipment and food are distinct native use profiles")
                if (c.nutrition !in 0..20) error("$path.consumable.nutrition", "items.nutrition", "Nutrition supports 0..20")
                number("$path.consumable.saturation", c.saturation.toDouble(), 0.0, 20.0)
                number("$path.consumable.consumeSeconds", c.consumeSeconds.toDouble(), 0.1, 10.0)
                if (c.effects.size > 8) error("$path.consumable.effects", "items.effect_capacity", "At most 8 consumption effects")
                c.effects.forEachIndexed { j, effect -> status("$path.consumable.effects[$j]", effect.effect, effect.durationTicks, effect.amplifier) }
            }
            if (item.actions.size > 2 || item.actions.map { it.trigger }.distinct().size != item.actions.size) error("$path.actions", "items.actions", "At most one action per USE / MELEE_HIT trigger")
            item.actions.forEachIndexed { j, action ->
                val ap = "$path.actions[$j]"
                if (action.trigger == ItemActionTrigger.MELEE_HIT && (item.equipment == null || item.equipment.isArmor()))
                    error("$ap.trigger", "items.melee_equipment", "MELEE_HIT requires non-armor equipment with the native weapon attack component")
                if (action.cooldownTicks !in 1..72000) error("$ap.cooldownTicks", "items.cooldown", "Cooldown supports 1..72000 ticks")
                if (action.consumeCount !in 0..item.maxStackSize) error("$ap.consumeCount", "items.cost", "Quantity cost must fit the definition's stack")
                if (action.durabilityCost !in 0..100000 || action.durabilityCost > (item.equipment?.durability ?: 0)) error("$ap.durabilityCost", "items.cost", "Durability cost needs equipment and must fit its durability")
                if (item.consumable != null && (action.trigger != ItemActionTrigger.USE || action.consumeCount != 0 || action.durabilityCost != 0)) error(ap, "items.consumption_cost", "Food USE actions already consume exactly one item via native consumption")
                if (action.effects.isEmpty() || action.effects.size > 8) error("$ap.effects", "items.effect_capacity", "Actions need 1..8 effects")
                if (action.effects.count { it is ItemEffect.Projectile } > 1 || action.effects.count { it is ItemEffect.Blink } > 1) error("$ap.effects", "items.effect_capacity", "At most one projectile and one blink per action")
                action.effects.forEachIndexed { k, effect ->
                    val ep = "$ap.effects[$k]"
                    when (effect) {
                        is ItemEffect.Heal -> { number("$ep.amount", effect.amount.toDouble(), 0.1, 100.0); if (effect.target == ItemEffectTarget.TARGET && action.trigger != ItemActionTrigger.MELEE_HIT) error(ep, "items.target", "TARGET requires a melee hit") }
                        is ItemEffect.Feed -> { if (effect.nutrition !in 0..20) error(ep, "items.nutrition", "Nutrition supports 0..20"); number("$ep.saturation", effect.saturation.toDouble(), 0.0, 20.0) }
                        is ItemEffect.Status -> { status(ep, effect.effect, effect.durationTicks, effect.amplifier); if (effect.target == ItemEffectTarget.TARGET && action.trigger != ItemActionTrigger.MELEE_HIT) error(ep, "items.target", "TARGET requires a melee hit") }
                        is ItemEffect.Projectile -> { number("$ep.damage", effect.damage.toDouble(), 0.0, 100.0); number("$ep.speed", effect.speed.toDouble(), 0.1, 4.0); number("$ep.gravity", effect.gravity.toDouble(), 0.0, 0.2); if (effect.lifetimeTicks !in 1..200) error(ep, "items.projectile_lifetime", "Projectile lifetime supports 1..200 ticks"); if (effect.hitEffects.size > 4) error(ep, "items.effect_capacity", "At most 4 projectile hit effects"); effect.hitEffects.forEachIndexed { n, e -> status("$ep.hitEffects[$n]", e.effect, e.durationTicks, e.amplifier) } }
                        is ItemEffect.Blink -> { number("$ep.distance", effect.distance.toDouble(), 0.1, 8.0); if (action.trigger != ItemActionTrigger.USE) error(ep, "items.blink_trigger", "Blink is an active USE action") }
                    }
                }
            }
        }
    }
    @JvmStatic fun freeze(library: CustomItemLibrary) = library.copy(items = java.util.List.copyOf(library.items.map { item ->
        item.copy(consumable = item.consumable?.copy(effects = java.util.List.copyOf(item.consumable.effects)), actions = java.util.List.copyOf(item.actions.map { a ->
            a.copy(effects = java.util.List.copyOf(a.effects.map { e -> if (e is ItemEffect.Projectile) e.copy(hitEffects = java.util.List.copyOf(e.hitEffects)) else e }))
        }))
    }))
    @JvmStatic fun validId(value: String): Boolean = ID.matches(value) && ".." !in value && !value.endsWith('.')
}
