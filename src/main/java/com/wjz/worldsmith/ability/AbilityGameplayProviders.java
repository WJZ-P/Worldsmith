package com.wjz.worldsmith.ability;

import static com.wjz.worldsmith.ability.NativeAbilityCapabilities.*;
import com.wjz.worldsmith.core.ability.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Registration only; resource, world and combat policy lives in separately testable services. */
final class AbilityGameplayProviders {
    private AbilityGameplayProviders() {}
    static void install() {
        AbilityWorldGameplay.install();
        AbilityCombatEffects.install();
        WorldAbilityRuntime.builtin("story.get",(c,a) -> com.wjz.worldsmith.content.story.WorldStoryRuntime.value(c.actor(),storyRef(a)));
        WorldAbilityRuntime.builtin("story.set",(c,a) -> {
            var update=com.wjz.worldsmith.content.story.WorldStoryRuntime.prepareChanges(c.actor(),List.of(new com.wjz.worldsmith.core.story.StoryFactChange(storyRef(a),a.get(2))));update.commit();return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("story.add",(c,a) -> {
            var update=com.wjz.worldsmith.content.story.WorldStoryRuntime.prepareChanges(c.actor(),List.of(new com.wjz.worldsmith.core.story.StoryFactChange(storyRef(a),a.get(2),com.wjz.worldsmith.core.story.StoryChangeMode.ADD)));update.commit();return AbilityValues.bool(true);
        });
        WorldAbilityRuntime.builtin("program.start",(c,a) -> c.startChild(text(a,0,64),a.get(1)));
        WorldAbilityRuntime.builtin("program.cancel",(c,a) -> AbilityValues.bool(c.cancelChild(text(a,0,36))));
        WorldAbilityRuntime.builtin("program.self",(c,a) -> AbilityValues.text(c.invocation().toString()));
        WorldAbilityRuntime.builtin("signal.send",(c,a) -> AbilityValues.bool(c.send(text(a,0,36),text(a,1,64),a.get(2))));
        WorldAbilityRuntime.builtin("scene.join",(c,a) -> AbilityValues.bool(c.joinScene(text(a,0,64),c.point(a.get(1)))));
        WorldAbilityRuntime.builtin("scene.anchor",(c,a) -> WorldAbilityRuntime.vector(c.sceneAnchor()));
        WorldAbilityRuntime.builtin("scene.emit",(c,a) -> AbilityValues.number(c.emitScene(text(a,0,64),a.get(1))));
        WorldAbilityRuntime.builtin("shared.actor_get",(c,a) -> AbilityActorResources.sharedGet(c,text(a,0,64)));
        WorldAbilityRuntime.builtin("shared.actor_set",(c,a) -> AbilityValues.bool(AbilityActorResources.sharedSet(c,text(a,0,64),a.get(1))));
        WorldAbilityRuntime.builtin("shared.scene_get",(c,a) -> c.sharedSceneGet(text(a,0,64)));
        WorldAbilityRuntime.builtin("shared.scene_set",(c,a) -> AbilityValues.bool(c.sharedSceneSet(text(a,0,64),a.get(1))));
        WorldAbilityRuntime.builtin("resource.define",(c,a) -> AbilityValues.bool(AbilityActorResources.define(c,text(a,0,64),number(a,1,0,1e6),number(a,2,0,1e6),number(a,3,0,1000))));
        WorldAbilityRuntime.builtin("resource.get",(c,a) -> AbilityValues.number(AbilityActorResources.get(c,text(a,0,64))));
        WorldAbilityRuntime.builtin("resource.give",(c,a) -> AbilityValues.number(AbilityActorResources.give(c,text(a,0,64),number(a,1,0,1e6))));
        WorldAbilityRuntime.builtin("resource.consume",(c,a) -> AbilityValues.bool(AbilityActorResources.consume(c,text(a,0,64),number(a,1,0,1e6))));
        WorldAbilityRuntime.builtin("resource.pay",(c,a) -> {
            var costs = new LinkedHashMap<String,Double>();
            map(a.getFirst()).forEach((key,value) -> {
                if (!(value instanceof AbilityValue.NumberValue number)) throw new IllegalArgumentException("Resource costs must be numbers");
                costs.put(key,number.getValue());
            });
            return AbilityValues.bool(AbilityActorResources.pay(c,costs));
        });
        WorldAbilityRuntime.builtin("entity.velocity",(c,a) -> { Entity e = c.resolve(a.getFirst()); return e == null ? AbilityValues.none() : WorldAbilityRuntime.vector(e.getDeltaMovement()); });
        WorldAbilityRuntime.builtin("entity.forward",(c,a) -> { Entity e = c.resolve(a.getFirst()); return e == null ? AbilityValues.none() : WorldAbilityRuntime.vector(e.getLookAngle()); });
        WorldAbilityRuntime.builtin("world.raycast",AbilityGameplayProviders::raycast);
        WorldAbilityRuntime.builtin("fx.particles",AbilityVisualRuntime::particles);
        WorldAbilityRuntime.builtin("fx.path",AbilityVisualRuntime::path);
        WorldAbilityRuntime.builtin("fx.item",AbilityVisualRuntime::item);
        WorldAbilityRuntime.builtin("fx.transform",AbilityVisualRuntime::transform);
        WorldAbilityRuntime.builtin("fx.remove",AbilityVisualRuntime::remove);
        WorldAbilityRuntime.builtin("animation.play",AbilityVisualRuntime::animationPlay);
        WorldAbilityRuntime.builtin("animation.stop",AbilityVisualRuntime::animationStop);
        WorldAbilityRuntime.builtin("projectile.velocity",AbilityProjectileControl::velocity);
        WorldAbilityRuntime.builtin("projectile.steer",AbilityProjectileControl::steer);
        WorldAbilityRuntime.builtin("projectile.retire",AbilityProjectileControl::retire);
    }
    static Map<String,AbilityValue> map(AbilityValue value) {
        if (!(value instanceof AbilityValue.MapValue map)) throw new IllegalArgumentException("Expected gameplay map value");
        return map.getValues();
    }
    private static com.wjz.worldsmith.core.story.StoryFactRef storyRef(List<AbilityValue> args) {
        String subject=text(args,1,64);return new com.wjz.worldsmith.core.story.StoryFactRef(text(args,0,64),subject.isEmpty()?null:subject);
    }
    private static AbilityValue raycast(WorldAbilityRuntime.Context c,List<AbilityValue> a) {
        Vec3 start = c.point(a.get(0)), end = c.point(a.get(1));
        if (start.distanceToSqr(end)>32*32 || !WorldAbilityRuntime.loaded(c.level(),new AABB(start,end).inflate(1))) throw new IllegalArgumentException("Raycast exceeds loaded segment bounds");
        var block = c.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,c.actor()));
        Vec3 position = block.getLocation(); double distance = start.distanceToSqr(position);
        Entity hit = null;
        for (Entity candidate : c.level().getEntities(c.actor(),new AABB(start,position).inflate(1),entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator())) {
            var intersection = candidate.getBoundingBox().clip(start,end);
            if (intersection.isPresent() && start.distanceToSqr(intersection.get()) < distance) { hit=candidate; position=intersection.get(); distance=start.distanceToSqr(position); }
        }
        String kind = hit != null ? "entity" : block.getType() == HitResult.Type.MISS ? "miss" : "block";
        Vec3 normal = hit != null ? position.subtract(hit.getBoundingBox().getCenter()).normalize()
            : block.getType() == HitResult.Type.MISS ? Vec3.ZERO : new Vec3(block.getDirection().getStepX(),block.getDirection().getStepY(),block.getDirection().getStepZ());
        var result = new LinkedHashMap<String,AbilityValue>();
        result.put("kind",AbilityValues.text(kind)); result.put("position",WorldAbilityRuntime.vector(position)); result.put("normal",WorldAbilityRuntime.vector(normal));
        result.put("normalExact",AbilityValues.bool(kind.equals("block"))); result.put("entity",WorldAbilityRuntime.handle(hit));
        result.put("blockId",kind.equals("block") ? AbilityValues.text(BuiltInRegistries.BLOCK.getKey(c.level().getBlockState(block.getBlockPos()).getBlock()).toString()) : AbilityValues.none());
        return AbilityValues.map(result);
    }
}
