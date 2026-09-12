package com.wjz.worldsmith.content.item;

import com.wjz.worldsmith.core.content.CustomItemDefinition;
import com.wjz.worldsmith.core.content.ItemEffect;
import com.wjz.worldsmith.core.content.ItemEquipment;
import com.wjz.worldsmith.core.content.ItemEquipmentType;
import com.wjz.worldsmith.core.content.ItemStatusEffect;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

/** Native components on a world-bound stack. Never allocates a registered item per logical definition. */
public final class CustomItemComponents {
    private CustomItemComponents() {}

    /** Called only for a freshly constructed canonical stack; mutable wear is preserved by its owner. */
    public static void apply(ItemStack stack, String bundleHash, CustomItemDefinition definition) {
        var equipment = definition.getEquipment();
        if (equipment != null) {
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
            stack.set(DataComponents.MAX_DAMAGE, equipment.getDurability());
            stack.set(DataComponents.DAMAGE, 0);
            var attributes = ItemAttributeModifiers.builder();
            if (equipment.isArmor()) {
                EquipmentSlot slot = slot(equipment.getType());
                EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
                Identifier modifier = Identifier.fromNamespaceAndPath("worldsmith", "armor/" + slot.getName());
                attributes.add(Attributes.ARMOR, new AttributeModifier(modifier, equipment.getArmor(), AttributeModifier.Operation.ADD_VALUE), group);
                attributes.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(modifier, equipment.getToughness(), AttributeModifier.Operation.ADD_VALUE), group);
                attributes.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(modifier, equipment.getKnockbackResistance(), AttributeModifier.Operation.ADD_VALUE), group);
                stack.set(DataComponents.EQUIPPABLE, Equippable.builder(slot)
                    .setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, GeneratedItemResources.equipmentId(bundleHash, definition.getId()))).build());
            } else {
                attributes.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, equipment.getAttackDamage(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
                attributes.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, equipment.getAttackSpeed(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
                stack.set(DataComponents.TOOL, tool(equipment));
                stack.set(DataComponents.WEAPON, equipment.getType() == ItemEquipmentType.AXE
                    ? new Weapon(2, Weapon.AXE_DISABLES_BLOCKING_FOR_SECONDS)
                    : new Weapon(equipment.getType() == ItemEquipmentType.MELEE ? 1 : 2));
            }
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, attributes.build());
        }
        var food = definition.getConsumable();
        if (food != null) {
            // saturation is the actual native saturation amount, not the builder's nutrition multiplier.
            stack.set(DataComponents.FOOD, new FoodProperties(food.getNutrition(), food.getSaturation(), food.getAlwaysEdible()));
            var consumable = Consumable.builder().consumeSeconds(food.getConsumeSeconds());
            if (!food.getEffects().isEmpty()) consumable.onConsume(new ApplyStatusEffectsConsumeEffect(food.getEffects().stream().map(CustomItemComponents::status).toList()));
            stack.set(DataComponents.CONSUMABLE, consumable.build());
        }
    }

    /** Runs during native preparation, before the definitions can be handed to players. */
    public static void validateNative(CustomItemDefinition definition) {
        var e = definition.getEquipment();
        if (e != null) {
            if (e.isArmor()) {
                checkAttribute(definition, "armor", Attributes.ARMOR, e.getArmor());
                checkAttribute(definition, "toughness", Attributes.ARMOR_TOUGHNESS, e.getToughness());
                checkAttribute(definition, "knockbackResistance", Attributes.KNOCKBACK_RESISTANCE, e.getKnockbackResistance());
            } else {
                // Authored weapon values are additive modifiers; validate their normal player totals.
                checkAttribute(definition, "attackDamage + player base", Attributes.ATTACK_DAMAGE, 1.0 + e.getAttackDamage());
                checkAttribute(definition, "attackSpeed + player base", Attributes.ATTACK_SPEED, 4.0 + e.getAttackSpeed());
                tool(e); // Resolves the target version's real block tags and tier rules.
            }
        }
        if (definition.getConsumable() != null) definition.getConsumable().getEffects().forEach(CustomItemComponents::status);
        for (var action : definition.getActions()) for (var effect : action.getEffects()) {
            if (effect instanceof ItemEffect.Status status) effect(status.getEffect());
            else if (effect instanceof ItemEffect.Projectile projectile) projectile.getHitEffects().forEach(CustomItemComponents::status);
        }
    }

    private static void checkAttribute(CustomItemDefinition item, String field, Holder<Attribute> attribute, double requested) {
        if (!Double.isFinite(requested) || Double.compare(attribute.value().sanitizeValue(requested), requested) != 0)
            throw new IllegalArgumentException("Item '" + item.getId() + "' " + field + " exceeds the native attribute range: " + requested);
    }

    private static MobEffectInstance status(ItemStatusEffect effect) {
        return new MobEffectInstance(effect(effect.getEffect()), effect.getDurationTicks(), effect.getAmplifier());
    }
    private static Holder<MobEffect> effect(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) throw new IllegalArgumentException("Invalid status effect id: " + id);
        return BuiltInRegistries.MOB_EFFECT.get(key).orElseThrow(() -> new IllegalArgumentException("Unknown native status effect: " + id));
    }

    private static Tool tool(ItemEquipment e) {
        if (e.getType() == ItemEquipmentType.MELEE) return Objects.requireNonNull(Items.IRON_SWORD.getDefaultInstance().get(DataComponents.TOOL));
        String profile = switch (e.getType()) {
            case AXE -> "axe";
            case PICKAXE -> "pickaxe";
            case SHOVEL -> "shovel";
            case HOE -> "hoe";
            default -> throw new IllegalArgumentException("Armor has no mining tool profile");
        };
        String tier = switch (e.getMiningTier()) {
            case WOOD -> "wooden";
            default -> e.getMiningTier().name().toLowerCase(Locale.ROOT);
        };
        Item nativeItem = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(tier + "_" + profile))
            .orElseThrow(() -> new IllegalArgumentException("Missing native mining tier/profile: " + tier + "/" + profile));
        Tool nativeTool = Objects.requireNonNull(nativeItem.getDefaultInstance().get(DataComponents.TOOL), "Native mining profile has no TOOL component");
        // Preserve the real registered tag holders. Looking tags up again before world tag binding is unnecessary.
        var rules = nativeTool.rules().stream().map(rule -> new Tool.Rule(rule.blocks(),
            rule.speed().map(ignored -> e.getMiningSpeed()), rule.correctForDrops())).toList();
        return new Tool(rules, nativeTool.defaultMiningSpeed(), nativeTool.damagePerBlock(), nativeTool.canDestroyBlocksInCreative());
    }

    /** Reuses native transformations, sounds, drops and durability against the context's actual custom stack. */
    public static InteractionResult useToolOn(UseOnContext context, ItemEquipment equipment) {
        if (equipment == null || context.getPlayer() == null) return InteractionResult.PASS;
        var level = context.getLevel(); var player = context.getPlayer(); var pos = context.getClickedPos();
        if (!level.hasChunkAt(pos) || !level.mayInteract(player, pos)
            || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())) return InteractionResult.FAIL;
        return switch (equipment.getType()) {
            case AXE -> Items.IRON_AXE.useOn(context);
            case SHOVEL -> Items.IRON_SHOVEL.useOn(context);
            case HOE -> Items.IRON_HOE.useOn(context);
            default -> InteractionResult.PASS;
        };
    }

    public static EquipmentSlot slot(ItemEquipmentType type) {
        return switch (type) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHESTPLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            default -> EquipmentSlot.MAINHAND;
        };
    }
}
