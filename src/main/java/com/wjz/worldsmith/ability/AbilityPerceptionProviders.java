package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded, read-only native observations; none of these calls claim movement or run another source. */
final class AbilityPerceptionProviders {
    private AbilityPerceptionProviders() {}
    static void install() {
        WorldAbilityRuntime.builtin("entity.identity", (context,args) -> {
            Entity entity=context.resolve(args.getFirst());if(entity==null)return AbilityValues.none();
            String kind=entity instanceof Player?"player":entity instanceof CreatureEntity?"creature":entity instanceof LivingEntity?"living":"other";
            return AbilityValues.map(Map.of("kind",AbilityValues.text(kind),
                "logicalId",AbilityValues.text(entity instanceof CreatureEntity creature?creature.creatureId():""),
                "bundleScope",AbilityValues.text(entity instanceof CreatureEntity creature?creature.bundleHash():""),
                "nativeId",AbilityValues.text(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())));
        });
        WorldAbilityRuntime.builtin("entity.environment", (context,args) -> {
            Entity entity=context.resolve(args.getFirst());if(entity==null)return AbilityValues.none();
            var pos=entity.blockPosition();
            if(!context.level().isInValidBounds(pos)||!WorldAbilityRuntime.loaded(context.level(),new AABB(pos).inflate(2)))return AbilityValues.none();
            long clock=context.level().getOverworldClockTime();
            return AbilityValues.map(Map.of("dayTime",AbilityValues.number(Math.floorMod(clock,24000)),
                "day",AbilityValues.number(Math.min(1e9,Math.max(0,Math.floorDiv(clock,24000)))),
                "light",AbilityValues.number(context.level().getMaxLocalRawBrightness(pos)),
                "skyVisible",AbilityValues.bool(context.level().canSeeSky(pos)),
                "raining",AbilityValues.bool(context.level().isRainingAt(pos)),
                "inWater",AbilityValues.bool(entity.isInWater()),"onFire",AbilityValues.bool(entity.isOnFire())));
        });
        WorldAbilityRuntime.builtin("entity.home", (context,args) -> {
            Entity entity=context.resolve(args.getFirst());
            var home=entity instanceof CreatureEntity creature?creature.abilityHome():null;
            return home==null?AbilityValues.none():WorldAbilityRuntime.vector(Vec3.atBottomCenterOf(home));
        });
        WorldAbilityRuntime.builtin("motion.status", (context,args) -> {
            Entity entity=context.resolve(args.getFirst());
            if(entity!=context.actor()||!(entity instanceof CreatureEntity creature))return AbilityValues.none();
            var navigation=creature.getNavigation();var path=navigation.getPath();
            Vec3 target=path==null?null:Vec3.atBottomCenterOf(path.getTarget());
            return AbilityValues.map(Map.of("navigating",AbilityValues.bool(!navigation.isDone()),"done",AbilityValues.bool(navigation.isDone()),
                "pathReachable",AbilityValues.bool(path!=null&&path.canReach()),"target",target==null?AbilityValues.none():WorldAbilityRuntime.vector(target),
                "distance",AbilityValues.number(target==null?0:creature.position().distanceTo(target)),"controlled",AbilityValues.bool(context.controlsActor())));
        });
        WorldAbilityRuntime.builtin("world.sample", (context,args) -> sample(context,args.getFirst()));
    }
    static AbilityValue sample(WorldAbilityRuntime.Context context,AbilityValue value) {
        final Vec3 point;
        try {point=context.point(value);}catch(IllegalArgumentException unavailable){return AbilityValues.none();}
        var pos=BlockPos.containing(point);
        // Shapes such as fences inspect neighbors; do not certify their collision from a partial loaded view.
        if(!WorldAbilityRuntime.loaded(context.level(),new AABB(pos).inflate(2)))return AbilityValues.none();
        var block=context.level().getBlockState(pos);
        String nativeId=BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        String logical=context.snapshot().blocks().nativeIds().entrySet().stream().filter(entry -> entry.getValue().equals(nativeId))
            .map(Map.Entry::getKey).findFirst().orElse(nativeId);
        return AbilityValues.map(Map.of("blockId",AbilityValues.text(logical),
            "fluidId",AbilityValues.text(BuiltInRegistries.FLUID.getKey(block.getFluidState().getType()).toString()),
            "collision",AbilityValues.bool(!block.getCollisionShape(context.level(),pos).isEmpty()),
            "light",AbilityValues.number(context.level().getMaxLocalRawBrightness(pos)),
            "skyVisible",AbilityValues.bool(context.level().canSeeSky(pos)),"raining",AbilityValues.bool(context.level().isRainingAt(pos))));
    }
}
