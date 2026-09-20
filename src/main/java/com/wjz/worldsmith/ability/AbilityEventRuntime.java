package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import com.wjz.worldsmith.core.content.AbilityEventBinding;
import com.wjz.worldsmith.core.content.CustomItemDefinition;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Real native inputs -> owned invocation UUIDs. Event callbacks never execute source synchronously. */
public final class AbilityEventRuntime {
    private static final int MAX_ACTORS_PER_LEVEL = 512, MAX_EVENTS_PER_LEVEL_TICK = 4096, MAX_EVENTS_PER_ACTOR_TICK = 64;
    private static final Map<LivingEntity, ActorState> ACTORS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Admission> ADMISSION = new IdentityHashMap<>();
    private static boolean registered;
    private AbilityEventRuntime() {}

    public static synchronized void register() {
        if (registered) return;
        ServerEntityEvents.EQUIPMENT_CHANGE.register((actor, slot, previous, next) -> syncEquipment(actor, slot));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof LivingEntity living) for (EquipmentSlot slot : EquipmentSlot.values()) syncEquipment(living, slot);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> { if (entity instanceof LivingEntity living) forget(living); });
        ServerLevelEvents.UNLOAD.register((server, level) -> clearLevel(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (LivingEntity actor : List.copyOf(ACTORS.keySet())) if (actor.level() instanceof ServerLevel level && level.getServer() == server) forget(actor);
            ADMISSION.keySet().removeIf(level -> level.getServer() == server);
        });
        // Registered before the VM's server-END runner by CustomItemRuntime.register().
        ServerTickEvents.END_SERVER_TICK.register(AbilityEventRuntime::tick);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> interact(player, level, hand, entity,
            hit == null ? entity.position() : hit.getLocation()));
        registered = true;
    }

    public static boolean hasUseBindings(CustomItemDefinition definition) {
        return definition.getAbilityBindings().stream().anyMatch(binding -> events(binding).stream().anyMatch(event -> event.startsWith("use_")));
    }

    /** Native Item.use entry. Client prediction starts only the normal use state; only the server starts programs. */
    public static InteractionResult use(Level level, Player player, InteractionHand hand, CustomItemDefinition definition) {
        if (!hasUseBindings(definition) || !player.isAlive() || player.isSpectator()) return InteractionResult.PASS;
        if (level.isClientSide()) {
            if (definition.getMaxUseTicks() > 0 && !player.isUsingItem()) player.startUsingItem(hand);
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer)) return InteractionResult.FAIL;
        ActorState state = state(player, true);
        if (state == null) return InteractionResult.FAIL;
        if (state.use != null) return state.use.matches(player, hand, definition) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        if (player.isUsingItem()) return InteractionResult.FAIL;
        Source source = syncEquipment(player, hand.asEquipmentSlot());
        if (source == null || !source.logical.equals(definition.getId())) return InteractionResult.FAIL;
        UseSession pending = new UseSession(source, hand, player.getInventory().getSelectedSlot(), definition.getMaxUseTicks());
        boolean accepted = dispatchUse(pending, "use_start", null, "press", Origin.USE);
        boolean hasStart = source.bindings.stream().anyMatch(binding -> binding.getStartOn().contains("use_start"));
        if (hasStart && !accepted) return InteractionResult.FAIL; // No held session is installed on a rejected start.
        if (definition.getMaxUseTicks() == 0) return accepted ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        state.use = pending;
        player.startUsingItem(hand);
        if (!player.isUsingItem() || player.getUsedItemHand() != hand) {
            endUse(state, "use_cancel", "start_rejected", true);
            return InteractionResult.FAIL;
        }
        renewUse(pending);
        return InteractionResult.SUCCESS;
    }

    public static void useTick(Level level, LivingEntity actor, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel) || !(actor instanceof Player player)) return;
        ActorState state = ACTORS.get(actor);
        UseSession use = state == null ? null : state.use;
        if (use == null) return;
        if (!use.matches(player, actor.getUsedItemHand(), CustomItemRuntime.definition(level, stack))) {
            endUse(state, "use_cancel", "source_changed", false);
            actor.stopUsingItem();
            return;
        }
        use.elapsed = Math.max(0, Math.min(use.maximum, use.maximum - remaining));
        if (use.lastTick == use.source.level.getGameTime()) return;
        use.lastTick = use.source.level.getGameTime();
        dispatchUse(use, "use_tick", null, "hold", Origin.USE);
        renewUse(use);
    }

    /** A physical button release, not a synthesized packet; duplicate/native stop paths see no live session afterwards. */
    public static void releaseUse(Level level, LivingEntity actor, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel) || !(actor instanceof Player player)) return;
        ActorState state = ACTORS.get(actor); UseSession use = state == null ? null : state.use;
        if (use == null) return;
        boolean same = use.matches(player, actor.getUsedItemHand(), CustomItemRuntime.definition(level, stack));
        use.elapsed = Math.max(0, Math.min(use.maximum, use.maximum - remaining));
        endUse(state, same ? "use_release" : "use_cancel", same ? "release" : "source_changed", false);
    }

    public static void finishUse(Level level, LivingEntity actor, ItemStack stack) {
        if (!(level instanceof ServerLevel)) return;
        ActorState state = ACTORS.get(actor);
        if (state != null && state.use != null) {
            if (!(actor instanceof Player player) || !state.use.matches(player, actor.getUsedItemHand(), CustomItemRuntime.definition(level, stack))) {
                endUse(state, "use_cancel", "source_changed", false);
                return;
            }
            state.use.elapsed = state.use.maximum;
            endUse(state, "use_release", "duration_complete", false);
        }
    }

    /** Called at LivingEntity.stopUsingItem HEAD, while the old stack and hand still exist. */
    public static void stoppedUsing(LivingEntity actor) {
        if (!(actor.level() instanceof ServerLevel)) return;
        ActorState state = ACTORS.get(actor);
        if (state != null && state.use != null) endUse(state, "use_cancel", "native_stop", !actor.isAlive() || actor.isRemoved());
    }

    public static void melee(ItemStack stack, LivingEntity target, LivingEntity actor) {
        if (!(actor instanceof ServerPlayer player) || !(actor.level() instanceof ServerLevel)) return;
        var definition = CustomItemRuntime.definition(actor.level(), stack);
        if (definition == null || definition.getAbilityBindings().stream().noneMatch(binding -> events(binding).contains("melee_hit"))) return;
        Source source = syncEquipment(actor, EquipmentSlot.MAINHAND);
        if (source != null && source.logical.equals(definition.getId()))
            dispatch(source, "melee_hit", target, target.position(), 0, "", "confirmed_hit", Origin.PULSE, true);
    }

    /** Root's AFTER_DAMAGE hook already sent hurt to existing machines. Only fresh starts/cancels belong here. */
    public static void afterHurt(LivingEntity actor, DamageSource damageSource, float damage) {
        if (!(actor instanceof CreatureEntity creature)) return;
        Source source = creatureSource(creature);
        if (source != null) dispatch(source, "hurt", damageSource.getEntity(), actor.position(),
            Float.isFinite(damage) ? Math.max(0, Math.min(1e9, damage)) : 0, "", "after_damage", Origin.CREATURE, false);
    }

    public static void creatureTick(CreatureEntity creature) {
        Source source = creatureSource(creature);
        if (source == null) return;
        if (!source.spawned) {
            source.spawned = true;
            dispatch(source, "spawn", null, creature.position(), 0, "", creature.isLoadedFromDisk() ? "loaded" : "spawned", Origin.CREATURE, true);
        }
        long now=source.level.getGameTime();
        for (AbilityEventBinding binding:source.bindings) if (events(binding).contains("tick")
            && tickDue(creature.getUUID(),binding.getId(),now,binding.getIntervalTicks())
            && (source.live(binding.getId())!=null || WorldAbilityRuntime.backgroundSlotAvailable(creature)))
            dispatchOne(source,binding,"tick",null,creature.position(),0,"","tick",Origin.CREATURE,true);
        for (AbilityEventBinding binding : source.bindings) {
            if (!events(binding).contains("enter") && !events(binding).contains("exit")) continue;
            Set<UUID> prior = source.nearby.computeIfAbsent(binding.getId(), ignored -> new HashSet<>());
            Map<UUID, ServerPlayer> nearby = new LinkedHashMap<>();
            for (ServerPlayer player : source.level.players()) if (player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(creature) <= binding.getRange() * binding.getRange()) nearby.put(player.getUUID(), player);
            for (ServerPlayer player : nearby.values()) if (!prior.contains(player.getUUID()))
                dispatchOne(source, binding, "enter", player, player.position(), 0, "", "range_enter", Origin.CREATURE, true);
            for (UUID id : List.copyOf(prior)) if (!nearby.containsKey(id)) {
                Entity player = source.level.getEntity(id);
                dispatchOne(source, binding, "exit", player, player == null ? creature.position() : player.position(), 0, "", "range_exit", Origin.CREATURE, true);
            }
            prior.clear(); prior.addAll(nearby.keySet());
        }
        for (Owned invocation : source.owned.values()) if (!invocation.binding.getListenTo().isEmpty())
            WorldAbilityRuntime.holdInvocation(source.level, invocation.id, 2);
    }

    public static void creatureRemoved(CreatureEntity creature) { forget(creature); }

    /** Deterministic staggering survives a load and requires no unbounded timer/task allocation. */
    static boolean tickDue(UUID actor,String binding,long now,int interval) {
        if(interval<1||interval>1200)throw new IllegalArgumentException("Creature observation interval must be 1..1200 ticks");
        long phase=Math.floorMod(actor.getMostSignificantBits()^actor.getLeastSignificantBits()^binding.hashCode(),interval);
        return Math.floorMod(now,interval)==phase;
    }

    public static String useNonce(LivingEntity actor) { ActorState state = ACTORS.get(actor); return state == null || state.use == null ? null : state.use.nonce; }

    private static InteractionResult interact(Player player, Level level, InteractionHand hand, Entity target, Vec3 point) {
        if (!player.isAlive() || player.isSpectator() || !target.isAlive() || target.level() != level
            || !player.isWithinEntityInteractionRange(target, 0)) return InteractionResult.PASS;
        var item = CustomItemRuntime.definition(level, player.getItemInHand(hand));
        boolean itemClaims = item != null && item.getAbilityBindings().stream().anyMatch(binding -> events(binding).contains("interact_entity"));
        boolean creatureClaims = target instanceof CreatureEntity creature && creature.definition() != null
            && creature.definition().getAbilityBindings().stream().anyMatch(binding -> events(binding).contains("interact_entity"));
        if (!itemClaims && !creatureClaims) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel)) return InteractionResult.FAIL;
        if (itemClaims) {
            Source source = syncEquipment(player, hand.asEquipmentSlot());
            return source != null && dispatch(source, "interact_entity", target, point, 0, "", "interaction", Origin.PULSE, true)
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        Source source = creatureSource((CreatureEntity)target);
        return source != null && dispatch(source, "interact_entity", player, point, 0, "", "interaction", Origin.CREATURE, true)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    /** Logical source comparison deliberately ignores ordinary wear, quantity changes, repair and display names. */
    private static Source syncEquipment(LivingEntity actor, EquipmentSlot slot) {
        if (!(actor.level() instanceof ServerLevel level) || WorldAbilityRuntime.snapshot(level) == null) return null;
        var definition = CustomItemRuntime.definition(level, actor.getItemBySlot(slot));
        if (definition != null && definition.getAbilityBindings().isEmpty()) definition = null;
        ActorState state = state(actor, definition != null);
        if (state == null) return null;
        Source prior = state.equipment.get(slot);
        String scope = WorldAbilityRuntime.snapshot(level).scope();
        if (prior != null && definition != null && prior.scope.equals(scope) && prior.logical.equals(definition.getId())) return prior;
        if (prior != null) {
            state.equipment.remove(slot);
            dispatch(prior, "unequip", null, actor.position(), 0, "", "source_removed", Origin.PULSE, true);
            for (Owned owned : prior.owned.values()) if (owned.origin == Origin.EQUIPMENT)
                retire(state, prior.level, owned.id, 2);
            if (state.use != null && state.use.source == prior) endUse(state, "use_cancel", "source_changed", false);
        }
        if (definition == null) return null;
        Source next = new Source(state, scope, definition.getId(), slot.name().toLowerCase(Locale.ROOT), definition.getAbilityBindings(), false);
        state.equipment.put(slot, next);
        dispatch(next, "equip", null, actor.position(), 0, "", "equipped", Origin.EQUIPMENT, true);
        return next;
    }

    private static Source creatureSource(CreatureEntity creature) {
        if (!(creature.level() instanceof ServerLevel level) || creature.isNoAi() || !creature.isAlive() || creature.isRemoved()
            || !creature.isWithinHome(creature.blockPosition())) return null;
        var definition = creature.definition(); var snapshot = WorldAbilityRuntime.snapshot(level);
        if (definition == null || definition.getAbilityBindings().isEmpty() || snapshot == null || !snapshot.scope().equals(creature.bundleHash())) return null;
        ActorState state = state(creature, true); if (state == null) return null;
        if (state.creature == null) state.creature = new Source(state, snapshot.scope(), definition.getId(), "", definition.getAbilityBindings(), true);
        return state.creature;
    }

    private static boolean dispatch(Source source, String event, Entity related, Vec3 position, double amount, String nonce, String reason, Origin origin, boolean existing) {
        boolean handled = false;
        for (AbilityEventBinding binding : source.bindings) handled |= dispatchOne(source, binding, event, related, position, amount, nonce, reason, origin, existing);
        return handled;
    }

    private static boolean dispatchOne(Source source, AbilityEventBinding binding, String event, Entity related, Vec3 position,
                                       double amount, String nonce, String reason, Origin origin, boolean existing) {
        Owned owned = source.live(binding.getId());
        if (binding.getCancelOn().contains(event)) {
            if (owned == null) return false;
            WorldAbilityRuntime.cancelInvocation(source.level, owned.id); source.owned.remove(binding.getId()); return true;
        }
        if (!binding.getStartOn().contains(event) && !binding.getListenTo().contains(event)) return false;
        if (related != null && (event.equals("enter") || event.equals("interact_entity")) && related.distanceToSqr(source.state.actor) > binding.getRange() * binding.getRange()) return false;
        if (!source.valid() || !admit(source.state)) return false;
        if (owned == null) {
            if (!binding.getStartOn().contains(event)) return false;
            LivingEntity target = related instanceof LivingEntity living && living.isAlive() && living.level() == source.level ? living : null;
            UUID launched = null;
            try (var prepared = WorldAbilityRuntime.prepareStart(source.level, source.state.actor, binding.getProgram(), source.state.actor.position(),
                target, binding.getCooldownTicks(), false)) {
                if (prepared == null) return false;
                UUID id = prepared.commit(); launched = id;
                owned = new Owned(binding, id, origin); source.owned.put(binding.getId(), owned);
            } catch (RuntimeException rejected) {
                if (launched != null) {
                    WorldAbilityRuntime.cancelInvocation(source.level, launched);
                    Owned current = source.owned.get(binding.getId());
                    if (current != null && launched.equals(current.id)) source.owned.remove(binding.getId());
                }
                Worldsmith.LOGGER.warn("Ability event binding {}:{} rejected {}: {}", source.logical, binding.getId(), event, rejected.getMessage());
                return false;
            }
        } else if (!existing) return false;
        if (origin == Origin.EQUIPMENT || source.creature && !binding.getListenTo().isEmpty()) WorldAbilityRuntime.holdInvocation(source.level, owned.id, 2);
        // Constructor queued start first. This host event follows it; normal cooperative handler ordering applies.
        WorldAbilityRuntime.emitInvocation(source.level, owned.id, event, payload(source, binding, related, position, amount, nonce, reason));
        return true;
    }

    private static Map<String, AbilityValue> payload(Source source, AbilityEventBinding binding, Entity related, Vec3 position, double amount, String nonce, String reason) {
        Map<String, AbilityValue> data = new LinkedHashMap<>();
        data.put("binding_id", AbilityValues.text(binding.getId())); data.put("source_id", AbilityValues.text(source.logical));
        data.put("source_kind", AbilityValues.text(source.creature ? "creature" : "item")); data.put("slot", AbilityValues.text(source.slot));
        data.put("nonce", AbilityValues.text(nonce)); data.put("reason", AbilityValues.text(reason));
        return Map.of("event_entity", related == null ? AbilityValues.none() : AbilityValues.entity(related.getUUID().toString()),
            "event_position", AbilityValues.vector(position.x, position.y, position.z), "event_amount", AbilityValues.number(amount),
            "event_tag", AbilityValues.text(binding.getId()), "event_data", AbilityValues.map(data));
    }

    private static boolean dispatchUse(UseSession use, String event, Entity related, String reason, Origin origin) {
        boolean handled = false;
        for (AbilityEventBinding binding : use.source.bindings) {
            if (!events(binding).contains(event)) continue;
            Owned recorded = use.receipts.get(binding.getId());
            Owned current = use.source.live(binding.getId());
            // A use nonce never adopts a replacement invocation, even when it has the same program/binding name.
            if (recorded != null && (current == null || !recorded.id.equals(current.id))) continue;
            Entity target = event.equals("use_start") ? WorldAbilityRuntime.aimTarget(use.source.level, use.source.state.actor, Math.min(16, binding.getRange())) : related;
            if (dispatchOne(use.source, binding, event, target, use.source.state.actor.position(), use.elapsed, use.nonce, reason, origin, true)) {
                handled = true;
                current = use.source.live(binding.getId());
                if (current != null) use.receipts.putIfAbsent(binding.getId(), current);
            }
        }
        return handled;
    }

    private static void endUse(ActorState state, String event, String reason, boolean hard) {
        UseSession use = state.use; if (use == null) return;
        state.use = null; // stopUsingItem/release callbacks cannot close the same nonce twice.
        if (!hard && use.source.valid()) dispatchUse(use, event, null, reason, Origin.PULSE);
        for (Owned receipt : use.receipts.values()) {
            if (hard || !use.source.valid()) {
                if (receipt.origin == Origin.USE) WorldAbilityRuntime.cancelInvocation(use.source.level, receipt.id);
            } else if (event.equals("use_cancel") && receipt.origin == Origin.USE) retire(state, use.source.level, receipt.id, 2);
            else WorldAbilityRuntime.holdInvocation(use.source.level, receipt.id, 2);
        }
        if (event.equals("use_cancel") && !reason.equals("native_stop") && state.actor.isUsingItem()) state.actor.stopUsingItem();
    }

    private static void renewUse(UseSession use) {
        for (Owned receipt : use.receipts.values()) WorldAbilityRuntime.holdInvocation(use.source.level, receipt.id, 2);
    }

    private static void retire(ActorState state, ServerLevel level, UUID id, int grace) {
        WorldAbilityRuntime.holdInvocation(level, id, grace);
        state.retiring.put(id, new Retirement(level, level.getGameTime() + grace));
    }

    private static void tick(MinecraftServer server) {
        for (ActorState state : List.copyOf(ACTORS.values())) {
            if (state.level.getServer() != server) continue;
            if (!state.valid()) { forget(state.actor); continue; }
            for (var entry : List.copyOf(state.retiring.entrySet())) if (state.level.getGameTime() >= entry.getValue().at) {
                WorldAbilityRuntime.cancelInvocation(entry.getValue().level, entry.getKey()); state.retiring.remove(entry.getKey());
            }
            for (EquipmentSlot slot : List.copyOf(state.equipment.keySet())) syncEquipment(state.actor, slot);
            for (Source equipment : state.equipment.values()) for (AbilityEventBinding binding : equipment.bindings) {
                Owned owned = equipment.live(binding.getId());
                if (owned == null && binding.getStartOn().contains("equip"))
                    dispatchOne(equipment, binding, "equip", null, state.actor.position(), 0, "", "while_equipped", Origin.EQUIPMENT, true);
                owned = equipment.live(binding.getId());
                if (owned != null && owned.origin == Origin.EQUIPMENT) WorldAbilityRuntime.holdInvocation(state.level, owned.id, 2);
            }
            if (state.use != null) {
                UseSession use = state.use;
                if (!(state.actor instanceof Player player) || !player.isUsingItem()
                    || !use.matches(player, player.getUsedItemHand(), CustomItemRuntime.definition(state.level, player.getUseItem())))
                    endUse(state, "use_cancel", "source_changed", false);
                else renewUse(use);
            }
        }
    }

    private static ActorState state(LivingEntity actor, boolean create) {
        ActorState value = ACTORS.get(actor);
        if (value != null || !create || !(actor.level() instanceof ServerLevel level) || !actor.isAlive() || WorldAbilityRuntime.snapshot(level) == null) return value;
        if (ACTORS.values().stream().filter(state -> state.level == level).count() >= MAX_ACTORS_PER_LEVEL) return null;
        value = new ActorState(actor, level); ACTORS.put(actor, value); return value;
    }

    private static void forget(LivingEntity actor) {
        ActorState state = ACTORS.remove(actor); if (state == null) return;
        if (state.use != null) for (Owned receipt : state.use.receipts.values()) WorldAbilityRuntime.cancelInvocation(state.level, receipt.id);
        for (Source source : state.sources()) for (Owned owned : source.owned.values()) WorldAbilityRuntime.cancelInvocation(source.level, owned.id);
        for (var entry : state.retiring.entrySet()) WorldAbilityRuntime.cancelInvocation(entry.getValue().level, entry.getKey());
    }

    private static void clearLevel(ServerLevel level) {
        for (ActorState state : List.copyOf(ACTORS.values())) if (state.level == level) forget(state.actor);
        ADMISSION.remove(level);
    }

    private static boolean admit(ActorState state) {
        long now = state.level.getGameTime();
        Admission level = ADMISSION.computeIfAbsent(state.level, ignored -> new Admission());
        if (level.tick != now) { level.tick = now; level.count = 0; }
        if (state.eventTick != now) { state.eventTick = now; state.events = 0; }
        return ++level.count <= MAX_EVENTS_PER_LEVEL_TICK && ++state.events <= MAX_EVENTS_PER_ACTOR_TICK;
    }

    private static Set<String> events(AbilityEventBinding binding) {
        Set<String> result = new HashSet<>(binding.getStartOn()); result.addAll(binding.getListenTo()); result.addAll(binding.getCancelOn()); return result;
    }

    private enum Origin { PULSE, USE, EQUIPMENT, CREATURE }
    private record Owned(AbilityEventBinding binding, UUID id, Origin origin) {}
    private record Retirement(ServerLevel level, long at) {}
    private static final class Admission { long tick = Long.MIN_VALUE; int count; }
    private static final class ActorState {
        final LivingEntity actor; final ServerLevel level;
        final Map<EquipmentSlot, Source> equipment = new EnumMap<>(EquipmentSlot.class);
        final Map<UUID, Retirement> retiring = new LinkedHashMap<>();
        Source creature; UseSession use; long eventTick = Long.MIN_VALUE; int events;
        ActorState(LivingEntity actor, ServerLevel level) { this.actor = actor; this.level = level; }
        boolean valid() { return actor.isAlive() && !actor.isRemoved() && actor.level() == level && WorldAbilityRuntime.snapshot(level) != null; }
        List<Source> sources() { List<Source> values = new ArrayList<>(equipment.values()); if (creature != null) values.add(creature); return values; }
    }
    private static final class Source {
        final ActorState state; final ServerLevel level; final String scope, logical, slot;
        final List<AbilityEventBinding> bindings; final boolean creature;
        final Map<String, Owned> owned = new LinkedHashMap<>();
        final Map<String, Set<UUID>> nearby = new HashMap<>(); boolean spawned;
        Source(ActorState state, String scope, String logical, String slot, List<AbilityEventBinding> bindings, boolean creature) {
            this.state = state; level = state.level; this.scope = scope; this.logical = logical; this.slot = slot;
            this.bindings = List.copyOf(bindings); this.creature = creature;
        }
        boolean valid() { var current = WorldAbilityRuntime.snapshot(level); return state.valid() && current != null && scope.equals(current.scope())
            && !(state.actor instanceof Mob mob && mob.isNoAi()) && !(state.actor instanceof Player player && player.isSpectator()); }
        Owned live(String binding) {
            Owned entry = owned.get(binding);
            if (entry != null && !WorldAbilityRuntime.invocationActive(level, entry.id)) { owned.remove(binding); return null; }
            return entry;
        }
    }
    private static final class UseSession {
        final Source source; final InteractionHand hand; final int selectedSlot, maximum; final String nonce = UUID.randomUUID().toString();
        final Map<String, Owned> receipts = new LinkedHashMap<>(); int elapsed; long lastTick = Long.MIN_VALUE;
        UseSession(Source source, InteractionHand hand, int selectedSlot, int maximum) {
            this.source = source; this.hand = hand; this.selectedSlot = selectedSlot; this.maximum = maximum;
        }
        boolean matches(Player player, InteractionHand hand, CustomItemDefinition definition) {
            var identity = player.getItemInHand(hand).get(CustomItemRuntime.identityComponent());
            return source.valid() && this.hand == hand && definition != null && source.logical.equals(definition.getId())
                && identity != null && source.scope.equals(identity.bundleHash()) && source.logical.equals(identity.itemId())
                && (hand != InteractionHand.MAIN_HAND || selectedSlot == player.getInventory().getSelectedSlot())
                && source.state.equipment.get(hand.asEquipmentSlot()) == source;
        }
    }
}
