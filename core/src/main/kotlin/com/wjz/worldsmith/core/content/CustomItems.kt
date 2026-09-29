package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

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
    @Serializable @SerialName("run_program") data class RunProgram(val program: String) : ItemEffect()
}
@Serializable data class ItemAction @JvmOverloads constructor(
    val trigger: ItemActionTrigger = ItemActionTrigger.USE,
    val cooldownTicks: Int = 20,
    val consumeCount: Int = 0,
    val durabilityCost: Int = 0,
    val effects: List<ItemEffect>,
)
@Serializable @OptIn(ExperimentalSerializationApi::class) data class CustomItemDefinition @JvmOverloads constructor(
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
    /** Additional native event entry points; these have no implicit quantity or durability charge. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val abilityBindings: List<AbilityEventBinding> = emptyList(),
    /** Zero is instant use; positive values enable a native held-use session. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxUseTicks: Int = 0,
)
@Serializable enum class CookingStation(val defaultTicks: Int) { FURNACE(200), BLAST_FURNACE(100), SMOKER(100), CAMPFIRE(600) }

/**
 * A native crafting-table or cooking recipe that makes or spends this world's items.
 * References are `worldsmith:item/<id>`, `worldsmith:content/<blockId>` or native item ids;
 * ingredients may also name a native item tag as `#namespace:path`.
 */
@Serializable sealed class ItemRecipe {
    abstract val id: String
    abstract val result: String
    abstract val count: Int
    abstract fun inputs(): List<String>

    @Serializable @SerialName("shaped") data class Shaped @JvmOverloads constructor(
        override val id: String, val pattern: List<String>, val key: Map<String, String>,
        override val result: String, override val count: Int = 1,
    ) : ItemRecipe() {
        override fun inputs() = pattern.flatMap { row -> row.filter { it != ' ' }.map { key[it.toString()].orEmpty() } }
    }
    @Serializable @SerialName("shapeless") data class Shapeless @JvmOverloads constructor(
        override val id: String, val ingredients: List<String>, override val result: String, override val count: Int = 1,
    ) : ItemRecipe() {
        override fun inputs() = ingredients
    }
    @Serializable @SerialName("cooking") data class Cooking @JvmOverloads constructor(
        override val id: String, val ingredient: String, override val result: String, override val count: Int = 1,
        val station: CookingStation = CookingStation.FURNACE, val experience: Float = 0.1f, val cookingTicks: Int? = null,
    ) : ItemRecipe() {
        override fun inputs() = listOf(ingredient)
    }
}

@Serializable @OptIn(ExperimentalSerializationApi::class) data class CustomItemLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val items: List<CustomItemDefinition> = emptyList(),
    /** Schema 5. Omitted when empty so earlier libraries keep their exact encoding and bundle hash. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val recipes: List<ItemRecipe> = emptyList(),
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
        if (library.schemaVersion !in 1..5) error("schemaVersion", "items.schema", "Item library schema must be 1 through 5")
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
            if (library.schemaVersion == 1 && (item.equipment != null || item.consumable != null || item.actions.isNotEmpty())) error(path, "items.schema", "Equipment, consumption and actions require items schema 2")
            if ((item.abilityBindings.isNotEmpty() || item.maxUseTicks != 0) && library.schemaVersion < 4)
                error(path, "items.schema", "Event bindings and held-use sessions require items schema 4")
            addAll(AbilityEventBindings.validate(item.abilityBindings, "item", "$path.abilityBindings"))
            if (item.maxUseTicks !in 0..12000) error("$path.maxUseTicks", "items.use_duration", "Use duration is 0 for instant use or 1..12000 native held-use ticks")
            val events = item.abilityBindings.flatMap { AbilityEventBindings.events(it) }.toSet()
            if ("melee_hit" in events && (item.equipment == null || item.equipment.isArmor()))
                error("$path.abilityBindings", "items.melee_equipment", "The native post-hit hook requires non-armor weapon/tool equipment")
            if (item.maxUseTicks == 0 && events.any { it in setOf("use_tick", "use_release", "use_cancel") })
                error("$path.maxUseTicks", "items.held_events", "Held tick/release/cancel events need a positive maxUseTicks")
            if (item.maxUseTicks > 0 && events.none { it.startsWith("use_") })
                error("$path.maxUseTicks", "items.held_events", "A held-use session needs an actual use event binding")
            if (events.any { it.startsWith("use_") } && item.consumable != null)
                error(path, "items.use_conflict", "Consumable use and event-program use are separate activation models")
            if (item.maxUseTicks > 0 && item.actions.any { it.trigger == ItemActionTrigger.USE })
                error(path, "items.use_conflict", "Held-use event programs are separate from consumables and immediate USE actions")
            if ("use_start" in events && item.actions.any { it.trigger == ItemActionTrigger.USE })
                error(path, "items.use_conflict", "Choose one USE activation model: an ItemAction or event bindings, not both")
            if ("melee_hit" in events && item.actions.any { it.trigger == ItemActionTrigger.MELEE_HIT })
                error(path, "items.use_conflict", "Choose one MELEE_HIT activation model: an ItemAction or event bindings, not both")
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
                        is ItemEffect.RunProgram -> {
                            if (library.schemaVersion < 3) error(ep, "items.schema", "Program invocation requires items schema 3")
                            if (!com.wjz.worldsmith.core.ability.AbilityPrograms.validId(effect.program)) error("$ep.program", "items.program", "Use a normalized local ability program id")
                            if (action.trigger != ItemActionTrigger.USE || action.effects.size != 1 || item.consumable != null)
                                error(ep, "items.program_action", "A program is the sole effect of a non-consumable USE action")
                        }
                        is ItemEffect.Heal -> { number("$ep.amount", effect.amount.toDouble(), 0.1, 100.0); if (effect.target == ItemEffectTarget.TARGET && action.trigger != ItemActionTrigger.MELEE_HIT) error(ep, "items.target", "TARGET requires a melee hit") }
                        is ItemEffect.Feed -> { if (effect.nutrition !in 0..20) error(ep, "items.nutrition", "Nutrition supports 0..20"); number("$ep.saturation", effect.saturation.toDouble(), 0.0, 20.0) }
                        is ItemEffect.Status -> { status(ep, effect.effect, effect.durationTicks, effect.amplifier); if (effect.target == ItemEffectTarget.TARGET && action.trigger != ItemActionTrigger.MELEE_HIT) error(ep, "items.target", "TARGET requires a melee hit") }
                        is ItemEffect.Projectile -> { number("$ep.damage", effect.damage.toDouble(), 0.0, 100.0); number("$ep.speed", effect.speed.toDouble(), 0.1, 4.0); number("$ep.gravity", effect.gravity.toDouble(), 0.0, 0.2); if (effect.lifetimeTicks !in 1..200) error(ep, "items.projectile_lifetime", "Projectile lifetime supports 1..200 ticks"); if (effect.hitEffects.size > 4) error(ep, "items.effect_capacity", "At most 4 projectile hit effects"); effect.hitEffects.forEachIndexed { n, e -> status("$ep.hitEffects[$n]", e.effect, e.durationTicks, e.amplifier) } }
                        is ItemEffect.Blink -> { number("$ep.distance", effect.distance.toDouble(), 0.1, 8.0); if (action.trigger != ItemActionTrigger.USE) error(ep, "items.blink_trigger", "Blink is an active USE action") }
                    }
                }
            }
        }
        addAll(validateRecipes(library))
    }

    const val MAX_RECIPES = 128
    private const val LOCAL_BLOCK_PREFIX = "worldsmith:content/"

    private fun validateRecipes(library: CustomItemLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (library.recipes.isEmpty()) return@buildList
        if (library.schemaVersion < 5) error("recipes", "items.schema", "Recipes require items schema 5")
        if (library.recipes.size > MAX_RECIPES) {
            error("recipes", "items.recipe_capacity", "At most $MAX_RECIPES recipes per world")
            return@buildList
        }
        val items = library.items.associateBy { it.id }
        val seen = mutableSetOf<String>()
        fun reference(path: String, value: String, ingredient: Boolean) {
            when {
                value.startsWith(LOGICAL_PREFIX) -> if (value.removePrefix(LOGICAL_PREFIX) !in items)
                    error(path, "items.recipe_item", "No item '${value.removePrefix(LOGICAL_PREFIX)}' in this library")
                value.startsWith("worldsmith:content/item/") || value.startsWith("worldsmith:content/block/") ->
                    error(path, "items.recipe_reference", "Name the logical item or block, never a reserved native host")
                value.startsWith(LOCAL_BLOCK_PREFIX) -> if (!ID.matches(value.removePrefix(LOCAL_BLOCK_PREFIX)))
                    error(path, "items.recipe_reference", "Custom block items are worldsmith:content/<blockId> without block state properties")
                value.startsWith("#") -> if (!ingredient || !NATIVE_ID.matches(value.substring(1)))
                    error(path, "items.recipe_reference", if (ingredient) "Item tags are #namespace:path" else "A result is one item, not a tag")
                !NATIVE_ID.matches(value) || value == "minecraft:air" -> error(path, "items.recipe_reference", "Expected worldsmith:item/<id>, worldsmith:content/<blockId>, a native item id or, for ingredients, #tag")
            }
        }
        library.recipes.forEachIndexed { index, recipe ->
            val path = "recipes[$index]"
            if (!validId(recipe.id)) error("$path.id", "items.recipe_id", "Use a local lowercase recipe id of 1..64 characters")
            if (!seen.add(recipe.id)) error("$path.id", "items.recipe_duplicate", "Duplicate recipe '${recipe.id}'")
            reference("$path.result", recipe.result, ingredient = false)
            val stack = items[recipe.result.removePrefix(LOGICAL_PREFIX)]?.takeIf { recipe.result.startsWith(LOGICAL_PREFIX) }?.maxStackSize ?: 64
            if (recipe.count !in 1..stack) error("$path.count", "items.recipe_count", "A result count is 1..$stack, within the result's stack size")
            // Recipes live here to give this world's items sources and uses; that also anchors their references.
            if ((recipe.inputs() + recipe.result).none { it.startsWith(LOGICAL_PREFIX) })
                error(path, "items.recipe_scope", "A recipe makes or spends at least one worldsmith:item/<id> from this library")
            when (recipe) {
                is ItemRecipe.Shaped -> {
                    val width = recipe.pattern.firstOrNull()?.length ?: 0
                    if (recipe.pattern.size !in 1..3 || width !in 1..3 || recipe.pattern.any { it.length != width })
                        error("$path.pattern", "items.recipe_pattern", "A pattern is 1..3 rows of equal length 1..3")
                    val symbols = recipe.pattern.flatMap { row -> row.toList() }.filter { it != ' ' }.map { it.toString() }.toSet()
                    if (symbols.isEmpty()) error("$path.pattern", "items.recipe_pattern", "A pattern needs at least one ingredient symbol")
                    symbols.filter { it !in recipe.key }.forEach { error("$path.pattern", "items.recipe_key", "Symbol '$it' has no key entry") }
                    recipe.key.forEach { (symbol, value) ->
                        if (symbol.length != 1 || symbol == " ") error("$path.key", "items.recipe_key", "Keys are single non-space characters")
                        else if (symbol !in symbols) error("$path.key.$symbol", "items.recipe_key", "Key '$symbol' is not used by the pattern")
                        reference("$path.key.$symbol", value, ingredient = true)
                    }
                }
                is ItemRecipe.Shapeless -> {
                    if (recipe.ingredients.size !in 1..9) error("$path.ingredients", "items.recipe_ingredients", "A shapeless recipe has 1..9 ingredients")
                    recipe.ingredients.forEachIndexed { i, value -> reference("$path.ingredients[$i]", value, ingredient = true) }
                }
                is ItemRecipe.Cooking -> {
                    reference("$path.ingredient", recipe.ingredient, ingredient = true)
                    if (!recipe.experience.isFinite() || recipe.experience !in 0f..100f) error("$path.experience", "items.recipe_experience", "Experience is 0..100")
                    if (recipe.cookingTicks != null && recipe.cookingTicks !in 1..32767) error("$path.cookingTicks", "items.recipe_time", "Cooking time is 1..32767 ticks")
                }
            }
        }
    }

    @JvmStatic fun freeze(library: CustomItemLibrary) = library.copy(recipes = java.util.List.copyOf(library.recipes.map { recipe -> when (recipe) {
        is ItemRecipe.Shaped -> recipe.copy(pattern = java.util.List.copyOf(recipe.pattern), key = java.util.Collections.unmodifiableMap(LinkedHashMap(recipe.key)))
        is ItemRecipe.Shapeless -> recipe.copy(ingredients = java.util.List.copyOf(recipe.ingredients))
        is ItemRecipe.Cooking -> recipe
    } }), items = java.util.List.copyOf(library.items.map { item ->
        item.copy(abilityBindings = AbilityEventBindings.freeze(item.abilityBindings), consumable = item.consumable?.copy(effects = java.util.List.copyOf(item.consumable.effects)), actions = java.util.List.copyOf(item.actions.map { a ->
            a.copy(effects = java.util.List.copyOf(a.effects.map { e -> if (e is ItemEffect.Projectile) e.copy(hitEffects = java.util.List.copyOf(e.hitEffects)) else e }))
        }))
    }))
    @JvmStatic fun validId(value: String): Boolean = ID.matches(value) && ".." !in value && !value.endsWith('.')
}
