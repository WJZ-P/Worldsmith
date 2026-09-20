package com.wjz.worldsmith.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.ability.*;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.world.entity.LivingEntity;

/** Persistent actor-wide state and atomic named resource pools. No VM continuation or live handle is saved. */
public final class AbilityActorResources {
    private static AttachmentType<State> type;
    private AbilityActorResources() {}
    public record Pool(double maximum, double value, double regeneration, long updatedAt) {
        static final Codec<Pool> CODEC = AbilityData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("maximum").forGetter(Pool::maximum), Codec.DOUBLE.fieldOf("value").forGetter(Pool::value),
            Codec.DOUBLE.fieldOf("regeneration").forGetter(Pool::regeneration), Codec.LONG.fieldOf("updatedAt").forGetter(Pool::updatedAt)
        ).apply(i, Pool::new)));
        public Pool {
            AbilityData.bounded(maximum,0,1e6); AbilityData.bounded(value,0,maximum); AbilityData.bounded(regeneration,0,1000);
            if (updatedAt < 0) throw new IllegalArgumentException("Invalid pool timestamp");
        }
        Pool at(long now) {
            if (now < 0) throw new IllegalArgumentException("Invalid world time");
            if (now <= updatedAt) return this; // Time rollback never grants duplicate regeneration.
            return new Pool(maximum, Math.min(maximum, value + Math.min(1e9, now - updatedAt) * regeneration), regeneration, now);
        }
        Pool with(double amount, long now) { return new Pool(maximum, amount, regeneration, Math.max(updatedAt,now)); }
    }
    record State(String scope, AbilityData.Values shared, Map<String,Pool> pools) {
        static final Codec<State> CODEC = AbilityData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("scope").forGetter(State::scope), AbilityData.Values.CODEC.fieldOf("shared").forGetter(State::shared),
            Codec.unboundedMap(Codec.STRING,Pool.CODEC).fieldOf("pools").forGetter(State::pools)
        ).apply(i,State::new)));
        State {
            AbilityData.scope(scope); Objects.requireNonNull(shared);
            if (pools.size() > 32) throw new IllegalArgumentException("Actor resource pools exceed 32");
            pools.forEach((key,pool) -> { AbilityData.id(key); Objects.requireNonNull(pool); });
            pools = Collections.unmodifiableMap(new LinkedHashMap<>(pools));
        }
        State pool(String id, Pool value) { var next = new LinkedHashMap<>(pools); next.put(id,value); return new State(scope,shared,next); }
        Pool pool(String id) { var value = pools.get(AbilityData.id(id)); if (value == null) throw new IllegalArgumentException("Unknown resource pool: " + id); return value; }
    }
    static void register() {
        if (type == null) type = AttachmentRegistry.<State>create(Worldsmith.id("ability_actor_resources"), b -> b.persistent(State.CODEC).copyOnDeath());
    }
    private static State state(LivingEntity actor, String scope) {
        if (type == null) throw new IllegalStateException("Actor resource storage is not registered");
        State value = ((AttachmentTarget)actor).getAttached(type);
        if (value == null) return new State(scope,AbilityData.Values.EMPTY,Map.of());
        if (!value.scope.equals(scope)) throw new IllegalStateException("Actor resources belong to another world bundle");
        return value;
    }
    private static void set(LivingEntity actor, State state) { ((AttachmentTarget)actor).setAttached(type,state); }
    static AbilityValue sharedGet(WorldAbilityRuntime.Context c, String key) { return state(c.actor(),c.snapshot().scope()).shared.get(key); }
    static boolean sharedSet(WorldAbilityRuntime.Context c, String key, AbilityValue value) {
        var old = state(c.actor(),c.snapshot().scope()); var next = old.shared.with(key,value);
        if (next != old.shared) set(c.actor(),new State(old.scope,next,old.pools)); return true;
    }
    static boolean define(WorldAbilityRuntime.Context c, String id, double maximum, double initial, double regeneration) {
        AbilityData.id(id); var pool = new Pool(maximum,initial,regeneration,c.level().getGameTime());
        var old = state(c.actor(),c.snapshot().scope()); var existing = old.pools.get(id);
        if (existing != null) {
            if (existing.maximum != maximum || existing.regeneration != regeneration) throw new IllegalArgumentException("Resource definition differs from existing pool");
            return true;
        }
        set(c.actor(),old.pool(id,pool)); return true;
    }
    static double get(WorldAbilityRuntime.Context c, String id) { return balance(c.actor(),c.snapshot().scope(),id,c.level().getGameTime()); }
    static double balance(LivingEntity actor, String scope, String id, long now) { return state(actor,scope).pool(id).at(now).value; }
    static double give(WorldAbilityRuntime.Context c, String id, double amount) {
        AbilityData.bounded(amount,0,1e6); var old = state(c.actor(),c.snapshot().scope()); var pool = old.pool(id).at(c.level().getGameTime());
        double credited = Math.min(amount,pool.maximum-pool.value);
        set(c.actor(),old.pool(id,pool.with(pool.value+credited,c.level().getGameTime()))); return credited;
    }
    static boolean consume(WorldAbilityRuntime.Context c, String id, double amount) { return pay(c.actor(),c.snapshot().scope(),Map.of(id,amount),c.level().getGameTime()); }
    static boolean pay(WorldAbilityRuntime.Context c, Map<String,Double> costs) { return pay(c.actor(),c.snapshot().scope(),costs,c.level().getGameTime()); }
    static boolean pay(LivingEntity actor, String scope, Map<String,Double> costs, long now) {
        State old = state(actor,scope); State next = planPayment(old,costs,now);
        if (next == null) return false; if (next != old) set(actor,next); return true;
    }
    /** Pure transactional preflight, also used by native regression tests. */
    static State planPayment(State old, Map<String,Double> costs, long now) {
        if (costs.isEmpty() || costs.size() > 32) throw new IllegalArgumentException("Payment requires 1..32 pools");
        var next = new LinkedHashMap<>(old.pools);
        boolean sufficient = true;
        for (var cost : costs.entrySet()) {
            AbilityData.bounded(cost.getValue(),0,1e6); Pool pool = old.pool(cost.getKey()).at(now);
            if (pool.value < cost.getValue()) sufficient = false;
            else next.put(cost.getKey(),pool.with(pool.value-cost.getValue(),now));
        }
        return sufficient ? new State(old.scope,old.shared,next) : null;
    }
}
