package com.wjz.worldsmith.ability;

import static com.wjz.worldsmith.ability.NativeAbilityCapabilities.*;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.ability.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.phys.Vec3;

/** Generic leased effects and pre-health damage guards. No attack name or synchronous script callback is embedded. */
public final class AbilityCombatEffects {
    private AbilityCombatEffects() {}
    private static final Map<ServerLevel,List<Effect>> EFFECTS=new IdentityHashMap<>();
    private static final Map<ServerLevel,List<Guard>> GUARDS=new IdentityHashMap<>();
    private static final Map<String,Holder<Attribute>> ATTRIBUTES=Map.of("movement_speed",Attributes.MOVEMENT_SPEED,"attack_damage",Attributes.ATTACK_DAMAGE,
        "armor",Attributes.ARMOR,"knockback_resistance",Attributes.KNOCKBACK_RESISTANCE);
    private record Modifier(Holder<Attribute> attribute,Identifier id,double amount) {}
    private record Effect(WorldAbilityRuntime.Context context,LivingEntity target,String tag,int stacks,long until,double incoming,double outgoing,List<Modifier> modifiers) {
        boolean live() { return target.isAlive() && !target.isRemoved() && context.active() && context.level().getGameTime()<until; }
    }
    private static final class Guard {
        final WorldAbilityRuntime.Context context; final GuardRule rule; final long until; int hits;
        Guard(WorldAbilityRuntime.Context context,GuardRule rule,int ticks) { this.context=context; this.rule=rule; until=context.level().getGameTime()+ticks; }
        boolean live() { return context.active() && context.level().getGameTime()<until && hits<rule.maxHits; }
    }
    record GuardRule(double multiplier,double absorb,String pool,TagKey<DamageType> sourceTag,Double frontDot,int maxHits,String tag) {
        GuardRule {
            AbilityData.bounded(multiplier,0,1); AbilityData.bounded(absorb,0,100); if(pool!=null) AbilityData.id(pool);
            if(frontDot!=null) AbilityData.bounded(frontDot,-1,1);
            if(maxHits<1 || maxHits>128 || tag==null || tag.length()>64 || tag.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid guard limits/tag");
        }
        double prevented(double damage,double available) { return Math.min(available,Math.min(damage,Math.max(0,damage*(1-multiplier)+absorb))); }
    }
    static void install() {
        WorldAbilityRuntime.builtin("effect.apply",AbilityCombatEffects::apply);
        WorldAbilityRuntime.builtin("effect.stacks",(c,a) -> AbilityValues.number(stacks(c.level(),c.living(a.get(0)),text(a,1,64))));
        WorldAbilityRuntime.builtin("effect.remove",(c,a) -> AbilityValues.bool(c.releaseLease(integer(a,0,1,Integer.MAX_VALUE),"effect")));
        WorldAbilityRuntime.builtin("combat.guard",AbilityCombatEffects::guard);
    }
    private static AbilityValue apply(WorldAbilityRuntime.Context c,List<AbilityValue> a) {
        LivingEntity target=c.living(a.get(0)); String tag=AbilityData.id(text(a,1,64)); int stacks=integer(a,2,1,16),ticks=integer(a,3,1,1200);
        var values=AbilityGameplayProviders.map(a.get(4));
        if(!mayAffect(c,target)) return AbilityValues.number(0);
        if(stacks(c.level(),target,tag)+stacks>64) throw new IllegalArgumentException("Effect tag stack budget exceeds 64");
        double incoming=1,outgoing=1; List<Modifier> modifiers=new ArrayList<>();
        for(var entry:values.entrySet()) {
            if(!(entry.getValue() instanceof AbilityValue.NumberValue numeric)) throw new IllegalArgumentException("Effect modifiers require numeric values");
            double amount=numeric.getValue();
            switch(entry.getKey()) {
                case "incoming" -> incoming=Math.pow(AbilityData.bounded(amount,0,4),stacks);
                case "outgoing" -> outgoing=Math.pow(AbilityData.bounded(amount,0,4),stacks);
                default -> {
                    Holder<Attribute> attribute=ATTRIBUTES.get(entry.getKey()); if(attribute==null) throw new IllegalArgumentException("Unknown effect modifier: "+entry.getKey());
                    double maximum=entry.getKey().equals("movement_speed")?.2:entry.getKey().equals("knockback_resistance")?.5:20;
                    AbilityData.bounded(amount,-maximum,maximum); amount*=stacks;
                    AttributeInstance instance=target.getAttribute(attribute); if(instance==null) throw new IllegalArgumentException("Target has no attribute: "+entry.getKey());
                    double next=withAdditiveModifier(instance,amount);
                    if(!Double.isFinite(next) || attribute.value().sanitizeValue(next)!=next) throw new IllegalArgumentException("Effect exceeds native attribute range");
                    modifiers.add(new Modifier(attribute,Worldsmith.id("ability_effect/"+UUID.randomUUID()),amount));
                }
            }
        }
        if(!Double.isFinite(incoming) || !Double.isFinite(outgoing) || incoming>8 || outgoing>8) throw new IllegalArgumentException("Stacked damage multiplier exceeds 8");
        Effect effect=new Effect(c,target,tag,stacks,c.level().getGameTime()+ticks,incoming,outgoing,List.copyOf(modifiers));
        int lease=c.lease("effect",ticks,() -> remove(effect));
        try {
            for(Modifier modifier:modifiers) target.getAttribute(modifier.attribute).addTransientModifier(new AttributeModifier(modifier.id,modifier.amount,AttributeModifier.Operation.ADD_VALUE));
            EFFECTS.computeIfAbsent(c.level(),ignored -> new ArrayList<>()).add(effect);
        } catch(RuntimeException failure) { c.releaseLease(lease,"effect"); throw failure; }
        return AbilityValues.number(lease);
    }
    private static void remove(Effect effect) {
        for(Modifier modifier:effect.modifiers) { var attribute=effect.target.getAttribute(modifier.attribute); if(attribute!=null) attribute.removeModifier(modifier.id); }
        var entries=EFFECTS.get(effect.context.level()); if(entries!=null) { entries.remove(effect); if(entries.isEmpty()) EFFECTS.remove(effect.context.level()); }
    }
    static double withAdditiveModifier(AttributeInstance attribute,double amount) {
        double base=attribute.getBaseValue()+amount;
        var modifiers=attribute.getModifiers();
        for(var modifier:modifiers) if(modifier.operation()==AttributeModifier.Operation.ADD_VALUE) base+=modifier.amount();
        double value=base;
        for(var modifier:modifiers) if(modifier.operation()==AttributeModifier.Operation.ADD_MULTIPLIED_BASE) value+=base*modifier.amount();
        for(var modifier:modifiers) if(modifier.operation()==AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) value*=1+modifier.amount();
        return value;
    }
    static int stacks(ServerLevel level,LivingEntity target,String tag) {
        if(target==null) return 0; int count=0;
        for(Effect effect:EFFECTS.getOrDefault(level,List.of())) if(effect.target==target && effect.tag.equals(tag) && effect.live()) count+=effect.stacks;
        return count;
    }
    private static AbilityValue guard(WorldAbilityRuntime.Context c,List<AbilityValue> a) {
        var rule=rule(AbilityGameplayProviders.map(a.get(0))); int ticks=integer(a,1,1,1200);
        if(rule.pool!=null) AbilityActorResources.get(c,rule.pool); // Resolve resource now; no partially effective guard on a missing pool.
        Guard guard=new Guard(c,rule,ticks);
        int lease=c.lease("guard",ticks,() -> { var entries=GUARDS.get(c.level()); if(entries!=null) { entries.remove(guard); if(entries.isEmpty()) GUARDS.remove(c.level()); } });
        GUARDS.computeIfAbsent(c.level(),ignored -> new ArrayList<>()).add(guard); return AbilityValues.number(lease);
    }
    static GuardRule rule(Map<String,AbilityValue> values) {
        Set<String> keys=Set.of("multiplier","absorb","pool","sourceTag","frontDot","maxHits","tag");
        if(!keys.containsAll(values.keySet())) throw new IllegalArgumentException("Unknown damage guard field");
        double multiplier=fieldNumber(values,"multiplier",1),absorb=fieldNumber(values,"absorb",0),hits=fieldNumber(values,"maxHits",128);
        if(hits!=Math.rint(hits)) throw new IllegalArgumentException("maxHits must be an integer");
        String pool=fieldText(values,"pool",null),source=fieldText(values,"sourceTag",null),tag=fieldText(values,"tag","");
        TagKey<DamageType> sourceTag=source==null?null:TagKey.create(Registries.DAMAGE_TYPE,Identifier.parse(source));
        return new GuardRule(multiplier,absorb,pool,sourceTag,values.containsKey("frontDot")?fieldNumber(values,"frontDot",0):null,(int)hits,tag);
    }
    private static double fieldNumber(Map<String,AbilityValue> map,String key,double fallback) {
        if(!map.containsKey(key)) return fallback;
        if(!(map.get(key) instanceof AbilityValue.NumberValue number)) throw new IllegalArgumentException("Guard field must be numeric: "+key);
        return number.getValue();
    }
    private static String fieldText(Map<String,AbilityValue> map,String key,String fallback) {
        if(!map.containsKey(key)) return fallback;
        if(!(map.get(key) instanceof AbilityValue.TextValue text) || text.getValue().length()>128 || text.getValue().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Guard field must be bounded text: "+key);
        return text.getValue();
    }
    /** Called after native armor/magic reduction, before absorption hearts or health change; invulnerability/PvP gates already ran. */
    public static float intercept(LivingEntity target,ServerLevel level,DamageSource source,float damage) {
        if(damage<=0 || !Float.isFinite(damage) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !EFFECTS.containsKey(level) && !GUARDS.containsKey(level)) return damage;
        double multiplier=1;
        for(Effect effect:EFFECTS.getOrDefault(level,List.of())) if(effect.live()) {
            if(effect.target==target) multiplier*=effect.incoming;
            if(effect.target==source.getEntity()) multiplier*=effect.outgoing;
        }
        // At most 128 live resources, with at most two <=8 multipliers per effect: a double
        // holds the entire product. Intermediate clipping would make insertion order alter damage.
        double scaled=scaledDamage(damage,multiplier);
        for(Guard guard:List.copyOf(GUARDS.getOrDefault(level,List.of()))) {
            if(scaled<=0 || guard.context.actor()!=target || !guard.live()) continue;
            GuardRule rule=guard.rule;
            if(rule.sourceTag!=null && !source.is(rule.sourceTag)) continue;
            if(rule.frontDot!=null) {
                Vec3 position=source.getSourcePosition();
                if(position==null || target.getLookAngle().dot(position.subtract(target.position()).normalize())<rule.frontDot) continue;
            }
            double blocked;
            try {
                double available=rule.pool==null?scaled:AbilityActorResources.balance(target,guard.context.snapshot().scope(),rule.pool,level.getGameTime());
                blocked=rule.prevented(scaled,available); if(blocked<=0) continue;
                if(rule.pool!=null && !AbilityActorResources.pay(target,guard.context.snapshot().scope(),Map.of(rule.pool,blocked),level.getGameTime())) continue;
            } catch(RuntimeException corruptResource) {
                guard.hits=rule.maxHits; Worldsmith.LOGGER.error("Damage guard retired after invalid resource state",corruptResource); continue;
            }
            scaled-=blocked; guard.hits++;
            var detail=AbilityValues.map(Map.of("blocked",AbilityValues.number(blocked),"remaining",AbilityValues.number(scaled),"tag",AbilityValues.text(rule.tag)));
            WorldAbilityRuntime.emitInvocation(level,guard.context.invocation(),"damage_guarded",Map.of("event_amount",AbilityValues.number(blocked),"event_entity",WorldAbilityRuntime.handle(source.getEntity()),"event_position",WorldAbilityRuntime.vector(target.position()),"event_tag",AbilityValues.text(rule.tag),"event_data",detail));
        }
        return (float)Math.max(0,Math.min(1e6,scaled));
    }
    static double scaledDamage(double damage,double multiplier) { return Math.max(0,Math.min(1e6,damage*multiplier)); }
}
