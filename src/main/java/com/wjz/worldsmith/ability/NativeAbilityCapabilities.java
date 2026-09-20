package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.item.ItemActions;
import com.wjz.worldsmith.core.ability.*;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Engine operations are registered separately. No ability id, named boss move or program branch appears here. */
final class NativeAbilityCapabilities {
    private NativeAbilityCapabilities() {}
    static void install() {
        WorldAbilityRuntime.builtin("entity.position", (context, args) -> {
            Entity entity = context.resolve(args.getFirst()); return entity == null ? AbilityValues.none() : WorldAbilityRuntime.vector(entity.position());
        });
        WorldAbilityRuntime.builtin("entity.alive", (context, args) -> AbilityValues.bool(context.living(args.getFirst()) != null));
        WorldAbilityRuntime.builtin("entity.health_ratio", (context, args) -> {
            var actor = context.living(args.getFirst()); return AbilityValues.number(actor == null ? 0 : Math.max(0, Math.min(1, actor.getHealth() / actor.getMaxHealth())));
        });
        WorldAbilityRuntime.builtin("entity.target", (context, args) -> {
            Entity entity = context.resolve(args.getFirst()); return WorldAbilityRuntime.handle(entity instanceof Mob mob ? mob.getTarget() : null);
        });
        WorldAbilityRuntime.builtin("entity.kind", (context, args) -> {
            Entity entity = context.resolve(args.getFirst()); return AbilityValues.text(entity instanceof Player ? "player" : entity instanceof CreatureEntity ? "creature" : entity instanceof LivingEntity ? "living" : "other");
        });
        WorldAbilityRuntime.builtin("world.entities", (context, args) -> {
            var region = args.getFirst(); AABB bounds = context.region(region);
            var entities = context.level().getEntitiesOfClass(LivingEntity.class, bounds.inflate(.5), entity -> entity != context.actor() && entity.isAlive()
                && !(entity instanceof Player player && (player.isCreative() || player.isSpectator()))
                && AbilityRegions.contains(region, entity.getX(), entity.getY(), entity.getZ()));
            entities.sort(Comparator.comparingDouble((LivingEntity entity) -> entity.position().distanceToSqr(context.origin())).thenComparing(Entity::getUUID));
            return AbilityValues.list(entities.stream().limit(32).map(WorldAbilityRuntime::handle).toList());
        });
        WorldAbilityRuntime.builtin("world.visible", (context, args) -> {
            var from = context.living(args.get(0)); var to = context.living(args.get(1));
            if (from == null || to == null) return AbilityValues.bool(false);
            Vec3 start = from.getEyePosition(), end = to.getEyePosition();
            if (!WorldAbilityRuntime.loaded(context.level(), new AABB(start, end).inflate(1))) return AbilityValues.bool(false);
            return AbilityValues.bool(context.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from)).getType() == HitResult.Type.MISS);
        });
        WorldAbilityRuntime.builtin("world.block", (context, args) -> {
            var state = context.level().getBlockState(BlockPos.containing(context.point(args.getFirst())));
            String nativeId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return AbilityValues.text(context.snapshot().blocks().nativeIds().entrySet().stream().filter(entry -> entry.getValue().equals(nativeId))
                .map(java.util.Map.Entry::getKey).findFirst().orElse(nativeId));
        });
        WorldAbilityRuntime.builtin("world.aim", (context, args) -> {
            double range = number(args, 0, .1, 16);
            Vec3 eye = context.actor().getEyePosition(), end = eye.add(context.actor().getLookAngle().scale(range));
            if (!WorldAbilityRuntime.loaded(context.level(), new AABB(eye, end).inflate(1))) throw new IllegalArgumentException("Aim traverses an unloaded area");
            HitResult hit = context.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context.actor()));
            return WorldAbilityRuntime.vector(hit.getLocation());
        });
        WorldAbilityRuntime.builtin("combat.damage", (context, args) -> {
            var target = context.living(args.getFirst()); float amount = (float)number(args, 1, 0, 100);
            if (!mayAffect(context, target)) return AbilityValues.bool(false);
            var source = context.actor() instanceof Player player ? context.level().damageSources().playerAttack(player) : context.level().damageSources().mobAttack(context.actor());
            return AbilityValues.bool(target.hurtServer(context.level(), source, amount));
        });
        WorldAbilityRuntime.builtin("combat.heal", (context, args) -> {
            var target = context.living(args.getFirst()); float amount = (float)number(args, 1, 0, 100);
            if (target == null) return AbilityValues.bool(false);
            float before = target.getHealth(); target.heal(amount); return AbilityValues.bool(target.getHealth() > before);
        });
        WorldAbilityRuntime.builtin("status.apply", (context, args) -> {
            var target = context.living(args.getFirst()); String effect = text(args, 1, 128);
            int duration = integer(args, 2, 1, 1200), amplifier = integer(args, 3, 0, 4);
            var holder = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effect)).orElseThrow(() -> new IllegalArgumentException("Unknown status effect: " + effect));
            if (!mayAffect(context, target)) return AbilityValues.bool(false);
            return AbilityValues.bool(target.addEffect(new MobEffectInstance(holder, duration, amplifier), context.actor()));
        });
        WorldAbilityRuntime.builtin("motion.stop", (context, args) -> {
            var entity = context.living(args.getFirst());
            if (entity != context.actor() || !(entity instanceof Player || entity instanceof CreatureEntity && context.controlsActor())) return AbilityValues.bool(false);
            if (entity instanceof Mob mob) mob.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0); return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("motion.face", (context, args) -> {
            var entity = context.living(args.getFirst()); Vec3 point = context.point(args.get(1));
            if (entity != context.actor() || !(entity instanceof Player || entity instanceof CreatureEntity && context.controlsActor())) return AbilityValues.bool(false);
            double dx = point.x - entity.getX(), dz = point.z - entity.getZ();
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            entity.setYRot(yaw); entity.setYHeadRot(yaw); entity.setYBodyRot(yaw);
            if (entity instanceof Mob mob) mob.getLookControl().setLookAt(point.x, point.y, point.z, 360, 360);
            return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("motion.navigate", (context, args) -> {
            var entity = context.living(args.getFirst()); Vec3 point = context.point(args.get(1)); double speed = number(args, 2, .1, 2);
            if (entity != context.actor() || !(entity instanceof CreatureEntity mob) || !context.controlsActor()) return AbilityValues.bool(false);
            if (!WorldAbilityRuntime.loaded(context.level(), new AABB(entity.position(), point).inflate(8))) throw new IllegalArgumentException("Navigation area is not loaded");
            // Native moveTo(null) clears an existing path: reject an absent/finished candidate first.
            var path = mob.getNavigation().createPath(point.x, point.y, point.z, 1);
            if (path == null || path.isDone()) return AbilityValues.bool(false);
            boolean accepted = mob.getNavigation().moveTo(path, speed);
            if (accepted && !context.acceptControlPath(path)) {
                if (mob.getNavigation().getPath()==path) mob.getNavigation().stop();
                return AbilityValues.bool(false);
            }
            return AbilityValues.bool(accepted);
        });
        WorldAbilityRuntime.builtin("control.claim", (context,args) -> AbilityValues.number(context.claimControl(integer(args,0,0,100),integer(args,1,1,1200))));
        WorldAbilityRuntime.builtin("control.held", (context,args) -> AbilityValues.bool(context.holdsControl(integer(args,0,0,Integer.MAX_VALUE))));
        WorldAbilityRuntime.builtin("control.release", (context,args) -> AbilityValues.bool(context.releaseLease(integer(args,0,0,Integer.MAX_VALUE),"control")));
        WorldAbilityRuntime.builtin("motion.blink", (context, args) -> {
            float distance = (float)number(args, 0, .1, 8);
            if (!(context.actor() instanceof Player player) || player.isPassenger() || player.isSleeping()) return AbilityValues.bool(false);
            Vec3 destination = ItemActions.blinkDestination(context.level(), player, distance);
            if (destination == null) return AbilityValues.bool(false);
            player.teleportTo(destination.x, destination.y, destination.z); player.resetFallDistance(); return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("motion.push", (context, args) -> {
            var target = context.living(args.getFirst()); Vec3 force = rawVector(args.get(1));
            if (force.lengthSqr() > 4) throw new IllegalArgumentException("Push vector exceeds speed limit");
            if (!mayAffect(context, target)) return AbilityValues.bool(false);
            target.push(force); target.hurtMarked = true; return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("fx.telegraph", (context, args) -> AbilityValues.number(context.telegraph(args.get(0), integer(args, 1, 1, 200), text(args, 2, 16))));
        WorldAbilityRuntime.builtin("fx.clear", (context, args) -> AbilityValues.bool(context.clearCue(integer(args, 0, 1, Integer.MAX_VALUE))));
        WorldAbilityRuntime.builtin("fx.sound", (context, args) -> {
            String id = text(args, 0, 128); Vec3 position = context.point(args.get(1));
            float volume = (float)number(args, 2, 0, 2), pitch = (float)number(args, 3, .5, 2);
            var sound = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(id)).orElseThrow(() -> new IllegalArgumentException("Unknown sound: " + id));
            context.level().playSound(null, position.x, position.y, position.z, sound, SoundSource.HOSTILE, volume, pitch);
            return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("fx.message", (context, args) -> {
            Component message = Component.literal(text(args, 0, 256));
            if (context.actor() instanceof ServerPlayer player) player.sendOverlayMessage(message);
            else for (ServerPlayer player : context.level().players()) if (player.distanceToSqr(context.actor()) <= 16 * 16) player.sendOverlayMessage(message);
            return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("fx.pose", (context, args) -> {
            context.pose(text(args, 0, 32), integer(args, 1, 1, 200)); return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("fx.caption", (context, args) -> {
            context.caption(text(args, 0, 128), integer(args, 1, 1, 1200)); return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("projectile.emit", (context, args) -> {
            Vec3 position = context.point(args.get(0)), velocity = rawVector(args.get(1));
            if (velocity.lengthSqr() > 4 || velocity.lengthSqr() < .0001) throw new IllegalArgumentException("Projectile velocity must be 0.01..2 blocks/tick");
            return context.projectile(position, velocity, (float)number(args, 2, 0, .2), integer(args, 3, 1, 200), text(args, 4, 64));
        });
        WorldAbilityRuntime.builtin("signal.emit", (context, args) -> AbilityValues.bool(context.signal(text(args, 0, 64), args.get(1))));
        AbilityGameplayProviders.install();
        AbilityPerceptionProviders.install();
    }

    static boolean mayAffect(WorldAbilityRuntime.Context context, LivingEntity target) {
        if (target == null || target instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        if (target != context.actor() && context.actor().isAlliedTo(target)) return false;
        return !(target != context.actor() && context.actor() instanceof Player player && target instanceof Player other && !player.canHarmPlayer(other));
    }
    static double number(List<AbilityValue> args, int index, double min, double max) {
        if (!(args.get(index) instanceof AbilityValue.NumberValue value) || !Double.isFinite(value.getValue()) || value.getValue() < min || value.getValue() > max)
            throw new IllegalArgumentException("Capability number must be " + min + ".." + max);
        return value.getValue();
    }
    static int integer(List<AbilityValue> args, int index, int min, int max) {
        double value = number(args, index, min, max); if (Math.rint(value) != value) throw new IllegalArgumentException("Capability requires an integer"); return (int)value;
    }
    static String text(List<AbilityValue> args, int index, int maximum) {
        if (!(args.get(index) instanceof AbilityValue.TextValue value) || value.getValue().length() > maximum || value.getValue().chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Capability text exceeds its bounds");
        return value.getValue();
    }
    static Vec3 rawVector(AbilityValue value) {
        if (!(value instanceof AbilityValue.VectorValue vector)) throw new IllegalArgumentException("Expected vector");
        return new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }
}
