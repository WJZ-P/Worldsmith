package com.wjz.worldsmith.content.item;

import com.wjz.worldsmith.core.content.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A fixed server-owned effect vocabulary. No script source, commands or client-supplied effect data. */
public final class ItemActions {
    private ItemActions() {}
    public static Identifier cooldownGroup(String scope, String id) { return Identifier.fromNamespaceAndPath("worldsmith", "ability/" + scope + "/" + id); }
    public static ItemAction action(CustomItemDefinition definition, ItemActionTrigger trigger) {
        return definition.getActions().stream().filter(a -> a.getTrigger() == trigger).findFirst().orElse(null);
    }
    /**
     * The component supplies the HUD/group; only a successful server action starts its timer.
     * Native ItemStack.use/finishUsingItem passes its nonempty pre-use copy to UseCooldown.apply,
     * including when the real held stack was consumed or broken by the action.
     */
    public static boolean ownsCooldown(ItemStack stack, LivingEntity user) {
        var definition = CustomItemRuntime.definition(user.level(), stack);
        return definition != null && !definition.getActions().isEmpty();
    }
    public static Holder<MobEffect> effect(String id) {
        return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id))
            .orElseThrow(() -> new IllegalArgumentException("Unknown item status effect: " + id));
    }
    public static MobEffectInstance status(ItemStatusEffect status) { return new MobEffectInstance(effect(status.getEffect()), status.getDurationTicks(), status.getAmplifier()); }

    public static InteractionResult use(Level level, Player player, InteractionHand hand, CustomItemDefinition definition) {
        ItemStack stack = player.getItemInHand(hand);
        ItemAction action = action(definition, ItemActionTrigger.USE);
        if (action == null) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer)) return InteractionResult.FAIL;
        return execute(server, player, stack, action, null, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    public static void melee(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (!(attacker instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
        var definition = CustomItemRuntime.definition(level, stack);
        if (definition == null) return;
        ItemAction action = action(definition, ItemActionTrigger.MELEE_HIT);
        if (action != null) execute(level, player, stack, action, target, EquipmentSlot.MAINHAND);
    }

    /** Called when native consumption completes, before it consumes its one stack unit. */
    public static boolean consumed(Level level, LivingEntity user, ItemStack stack, CustomItemDefinition definition) {
        ItemAction action = action(definition, ItemActionTrigger.USE);
        if (action == null || level.isClientSide()) return true;
        if (!(user instanceof ServerPlayer player) || !(level instanceof ServerLevel server)) return false;
        boolean succeeded=execute(server, player, stack, action, null,
            player.getUsedItemHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        if(!succeeded) {
            // ServerPlayer already sent native completion event 9. Its client-side consumption
            // prediction must be corrected even though the authoritative stack/food did not change.
            player.inventoryMenu.sendAllDataToRemote();
            if(player.containerMenu!=player.inventoryMenu)player.containerMenu.sendAllDataToRemote();
            player.connection.send(new ClientboundSetHealthPacket(player.getHealth(),
                player.getFoodData().getFoodLevel(),player.getFoodData().getSaturationLevel()));
        }
        return succeeded;
    }

    static boolean execute(ServerLevel level, Player player, ItemStack stack, ItemAction action, LivingEntity hitTarget, EquipmentSlot hand) {
        var identity = stack.get(CustomItemRuntime.identityComponent());
        var definition = CustomItemRuntime.definition(level, stack);
        if (identity == null || definition == null || !player.isAlive() || player.getCooldowns().isOnCooldown(stack)) return false;
        if (action != action(definition, action.getTrigger())) return false;
        if (!player.isCreative() && (stack.getCount() < action.getConsumeCount()
            || action.getDurabilityCost() > 0 && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() < action.getDurabilityCost()))) return false;
        // Resolve all capabilities and destinations before any effects/costs are applied.
        Vec3 destination = null;
        List<Holder<MobEffect>> statuses = new ArrayList<>();
        ItemAbilityProjectile projectile = null;
        int effectIndex = 0;
        for (ItemEffect effect : action.getEffects()) {
            if (effect instanceof ItemEffect.Heal heal && heal.getTarget() == ItemEffectTarget.TARGET && hitTarget == null) return false;
            if (effect instanceof ItemEffect.Status status) {
                if (status.getTarget() == ItemEffectTarget.TARGET && hitTarget == null) return false;
                statuses.add(effect(status.getEffect()));
            }
            if (effect instanceof ItemEffect.Blink blink) {
                if (player.isPassenger() || player.isSleeping()) return false;
                destination = blinkDestination(level, player, blink.getDistance());
                if (destination == null) return false;
            }
            if (effect instanceof ItemEffect.Projectile p) {
                var snapshot = CustomItemRuntime.snapshot(level);
                projectile = new ItemAbilityProjectile(ItemAbilityProjectile.type(), level);
                projectile.initialize(player, snapshot.stack(identity.logicalId(), 1), action.getTrigger(), effectIndex, p);
                if (!loaded(level, projectile.getBoundingBox().inflate(1.0)) || !level.getWorldBorder().isWithinBounds(projectile.getBoundingBox())) return false;
            }
            effectIndex++;
        }
        // Entity insertion is the fallible commit boundary; nothing else has changed if it fails.
        if (projectile != null && !level.addFreshEntity(projectile)) return false;
        int statusIndex = 0;
        for (ItemEffect effect : action.getEffects()) {
            if (effect instanceof ItemEffect.Heal heal) {
                LivingEntity recipient = heal.getTarget() == ItemEffectTarget.SELF ? player : hitTarget;
                if (recipient != null) recipient.heal(heal.getAmount());
            } else if (effect instanceof ItemEffect.Feed feed) {
                player.getFoodData().eat(new FoodProperties(feed.getNutrition(), feed.getSaturation(), true));
            } else if (effect instanceof ItemEffect.Status status) {
                LivingEntity recipient = status.getTarget() == ItemEffectTarget.SELF ? player : hitTarget;
                var holder = statuses.get(statusIndex++);
                if (recipient != null) recipient.addEffect(new MobEffectInstance(holder, status.getDurationTicks(), status.getAmplifier()), player);
            } else if (effect instanceof ItemEffect.Blink && destination != null) {
                player.teleportTo(destination.x, destination.y, destination.z);
                player.resetFallDistance();
            }
        }
        player.getCooldowns().addCooldown(cooldownGroup(identity.bundleHash(), identity.itemId()), action.getCooldownTicks());
        if (!player.isCreative()) {
            if (action.getDurabilityCost() > 0) stack.hurtAndBreak(action.getDurabilityCost(), player, hand);
            if (action.getConsumeCount() > 0) stack.shrink(action.getConsumeCount());
        }
        return true;
    }

    /** No chunk loads or through-wall fallback. Probe nearest valid standing point behind the clipped endpoint. */
    static Vec3 blinkDestination(ServerLevel level, Player player, float requested) {
        double distance = Math.min(8.0, Math.max(0.0, requested));
        Vec3 eye = player.getEyePosition(), direction = player.getLookAngle();
        if (!loaded(level, player.getBoundingBox().expandTowards(direction.scale(distance)).inflate(1.0))) return null;
        HitResult hit = level.clip(new ClipContext(eye, eye.add(direction.scale(distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        double limit = hit.getType() == HitResult.Type.MISS ? distance : Math.max(0, eye.distanceTo(hit.getLocation()) - 0.6);
        for (double step = limit; step >= 0.75; step -= 0.25) {
            Vec3 aim = player.position().add(direction.scale(step));
            for (int dy : new int[]{0, -1, 1}) {
                Vec3 target = new Vec3(aim.x, Math.floor(aim.y) + dy, aim.z);
                if (target.distanceTo(player.position()) > distance + 0.001) continue;
                AABB box = player.getBoundingBox().move(target.subtract(player.position()));
                if (!loaded(level, box.inflate(1.0)) || !level.getWorldBorder().isWithinBounds(box) || box.minY < level.getMinY() || box.maxY > level.getMaxY()) continue;
                BlockPos below = BlockPos.containing(target.x, target.y - 0.01, target.z);
                if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
                if (!level.noCollision(player, box) || level.containsAnyLiquid(box)) continue;
                Vec3 targetEye = target.add(0, player.getEyeHeight(), 0);
                if (level.clip(new ClipContext(eye, targetEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player)).getType() != HitResult.Type.MISS) continue;
                if (!sweptBodyIsClear(player.getBoundingBox(), target.subtract(player.position()),
                    probe -> loaded(level, probe.inflate(1.0)) && level.noCollision(player, probe) && !level.containsAnyLiquid(probe))) continue;
                return target;
            }
        }
        return null;
    }
    /** At most one-quarter block between full-body probes, including the exact destination. */
    static boolean sweptBodyIsClear(AABB body, Vec3 displacement, Predicate<AABB> clear) {
        double length=displacement.length();
        if (!Double.isFinite(length) || length > 8.001) return false;
        int steps=Math.max(1,(int)Math.ceil(length / 0.25));
        for(int step=1;step<=steps;step++)
            if(!clear.test(body.move(displacement.scale((double)step / steps))))return false;
        return true;
    }
    static boolean loaded(ServerLevel level, AABB box) {
        for (int x = ((int)Math.floor(box.minX)) >> 4; x <= ((int)Math.floor(box.maxX)) >> 4; x++)
            for (int z = ((int)Math.floor(box.minZ)) >> 4; z <= ((int)Math.floor(box.maxZ)) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
}
