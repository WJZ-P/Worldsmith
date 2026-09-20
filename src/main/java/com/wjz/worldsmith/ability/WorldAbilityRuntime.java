package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One cooperative engine for creature, item and scene adapters. No named attack is implemented here. */
public final class WorldAbilityRuntime {
    public static final int MAX_ACTIVE = 64, MAX_PER_ACTOR = 4, OPS_PER_TICK = 128, MAX_NATIVE_CALLS = 32;
    private static final Map<ServerLevel, Bound> WORLDS = new ConcurrentHashMap<>();
    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Map<LivingEntity, Map<String, String>> FAILURES = Collections.synchronizedMap(new WeakHashMap<>());
    private static AttachmentType<AbilityActorMemory> memoryType;
    private static AttachmentType<String> ownedByType;
    private static Snapshot clientSnapshot;
    private static boolean defaults, registered;
    private WorldAbilityRuntime() {}

    @FunctionalInterface public interface Capability { AbilityValue invoke(Context context, List<AbilityValue> arguments); }
    private record Provider(AbilityCapabilitySpec specification, Capability function) {}

    /** Installed native extensions contribute their own versioned operations; program code needs no new VM branch. */
    public static synchronized void registerCapability(AbilityCapabilitySpec specification, Capability function) {
        defaults();
        if (!WORLDS.isEmpty() || clientSnapshot != null) throw new IllegalStateException("Register ability capabilities before world publication");
        if (AbilityCapabilities.standard().lookup(specification.getName()) != null)
            throw new IllegalArgumentException("A built-in capability cannot be replaced: " + specification.getName());
        var frozen = capabilities().extend(specification).lookup(specification.getName());
        if (PROVIDERS.putIfAbsent(specification.getName(), new Provider(frozen, Objects.requireNonNull(function))) != null)
            throw new IllegalArgumentException("Ability capability already registered: " + specification.getName());
    }
    static void builtin(String name, Capability function) {
        AbilityCapabilitySpec spec = Objects.requireNonNull(AbilityCapabilities.standard().lookup(name), "Missing core capability signature " + name);
        if (PROVIDERS.putIfAbsent(name, new Provider(spec, function)) != null) throw new IllegalStateException("Duplicate native ability provider " + name);
    }
    private static synchronized void defaults() {
        if (defaults) return;
        defaults = true; NativeAbilityCapabilities.install();
    }
    public static synchronized AbilityCapabilityRegistry capabilities() {
        defaults();
        var registry = AbilityCapabilities.standard();
        for (Provider provider : PROVIDERS.values()) {
            var existing = registry.lookup(provider.specification.getName());
            if (existing == null) registry = registry.extend(provider.specification);
            else if (!existing.equals(provider.specification)) throw new IllegalArgumentException("Ability capability signature mismatch: " + provider.specification.getName());
        }
        return registry;
    }

    public static synchronized void register() {
        if (registered) return;
        defaults(); AbilityProjectile.register(); AbilityVisualRuntime.register(); AbilityActorResources.register();
        ownedByType = AttachmentRegistry.<String>create(Worldsmith.id("ability_owned_by"), builder -> builder.persistent(com.mojang.serialization.Codec.STRING));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            String marker = ((AttachmentTarget)entity).getAttached(ownedByType);
            if (marker != null) {
                boolean valid;
                try { valid = resourceActive(level,UUID.fromString(marker),entity.getUUID()); }
                catch (IllegalArgumentException invalid) { valid = false; }
                if (!valid) entity.discard();
            }
        });
        memoryType = AttachmentRegistry.<AbilityActorMemory>create(Worldsmith.id("ability_memory"), builder -> builder.persistent(AbilityActorMemory.CODEC).copyOnDeath());
        ServerTickEvents.END_SERVER_TICK.register(WorldAbilityRuntime::tick);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((actor, source, base, damage, blocked) -> {
            if (actor.level() instanceof ServerLevel level && damage > 0) {
                emit(level, actor, "hurt", Map.of("event_amount", AbilityValues.number(Math.min(1e9, damage)),
                    "event_entity", handle(source.getEntity()), "event_position", vector(actor.position())));
                AbilityEventRuntime.afterHurt(actor, source, damage);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((actor, source) -> cancelOwner(actor));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel server)) return;
            Bound bound = WORLDS.get(server); if (bound == null) return;
            for (Instance instance : List.copyOf(bound.instances.values())) if (instance.committed && instance.origin.distanceToSqr(Vec3.atCenterOf(pos)) <= 32 * 32)
                instance.machine.emit("block_break", Map.of("event_position", vector(Vec3.atLowerCornerOf(pos)), "event_entity", handle(player)));
        });
        registered = true;
    }

    public static Snapshot prepare(WorldsmithPack pack, CustomItemRuntime.Snapshot items, CreatureRuntime.Snapshot creatures, WorldBlockBindings.Resolver blocks) {
        var registry = capabilities();
        var diagnostics = AbilityPrograms.validate(pack.getAbilities(), registry);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Ability program compilation failed: " + diagnostics);
        var frozen = AbilityPrograms.freeze(pack.getAbilities());
        Map<String, CompiledAbilityProgram> programs = new LinkedHashMap<>();
        Map<String, Provider> providers;
        synchronized (WorldAbilityRuntime.class) { providers = Map.copyOf(PROVIDERS); }
        for (var definition : frozen.getPrograms()) {
            var compiled = AbilityCompiler.compile(definition, registry);
            for (var capability : compiled.getUsedCapabilities().keySet()) if (!registry.isPure(capability) && !providers.containsKey(capability))
                throw new IllegalArgumentException("Missing installed ability provider: " + capability);
            programs.put(definition.getId(), compiled);
        }
        return new Snapshot(pack.getManifest().getId(), frozen, Map.copyOf(programs), providers, items, creatures, blocks,
            pack.getManifest().getAssets().stream().map(com.wjz.worldsmith.core.content.ContentAsset::getId).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
    public static void bind(ServerLevel level, Snapshot snapshot) {
        Bound existing=WORLDS.get(level);
        if(existing!=null) {
            if(!existing.snapshot.scope.equals(snapshot.scope) || !existing.snapshot.library.equals(snapshot.library)) throw new IllegalStateException("A running level owns another ability library");
            return;
        }
        Bound proposed = new Bound(level,snapshot);
        proposed.worldEdits = AbilityWorldSavedData.load(level,snapshot.scope);
        Bound old = WORLDS.putIfAbsent(level, proposed);
        if (old != null && (!old.snapshot.scope.equals(snapshot.scope) || !old.snapshot.library.equals(snapshot.library)))
            throw new IllegalStateException("A running level owns another ability library");
    }
    public static void unbind(ServerLevel level) {
        Bound bound = WORLDS.get(level); if (bound == null) return;
        for (Instance instance : List.copyOf(bound.instances.values())) finish(instance, "world-unbound");
        AbilityActorControl.clearLevel(level);
        NativeAbilityDebug.unbound(level);
        WORLDS.remove(level, bound);
    }
    public static Snapshot snapshot(ServerLevel level) { Bound bound = WORLDS.get(level); return bound == null ? null : bound.snapshot; }
    static MinecraftServer connectedServer() { return WORLDS.keySet().stream().findFirst().map(ServerLevel::getServer).orElse(null); }
    record DebugInvocation(UUID id,String program,UUID parent,String scene,int resources,AbilityMachine machine) {}
    static List<DebugInvocation> debugInvocations(LivingEntity actor) {
        return allInstances().stream().filter(i -> i.actor==actor && i.committed).map(i -> new DebugInvocation(i.id,i.programId,i.parent,i.context.sceneKey(),i.context.resourceCount(),i.machine)).toList();
    }
    public static void activateClient(Snapshot snapshot) { clientSnapshot = Objects.requireNonNull(snapshot); }
    public static void clearClient() { clientSnapshot = null; }
    public static Snapshot clientSnapshot() { return clientSnapshot; }
    /** Initial target acquisition only; subsequent tracking is an explicit source-code query. */
    public static LivingEntity aimTarget(ServerLevel level, LivingEntity actor, double range) {
        if (!Double.isFinite(range) || range <= 0 || range > 16 || actor.level() != level) return null;
        Vec3 start = actor.getEyePosition(), end = start.add(actor.getLookAngle().scale(range));
        if (!loaded(level, new AABB(start, end).inflate(1))) return null;
        end = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getLocation();
        LivingEntity closest = null; double distance = start.distanceToSqr(end);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, new AABB(start, end).inflate(1),
            entity -> entity != actor && entity.isAlive() && !(entity instanceof Player player && (player.isSpectator() || player.isCreative())))) {
            var hit = candidate.getBoundingBox().inflate(.2).clip(start, end);
            if (hit.isPresent() && start.distanceToSqr(hit.get()) <= distance) {
                distance = start.distanceToSqr(hit.get()); closest = candidate;
            }
        }
        return closest;
    }
    public record Snapshot(String scope, AbilityLibrary library, Map<String, CompiledAbilityProgram> programs,
                           Map<String, Provider> providers, CustomItemRuntime.Snapshot items, CreatureRuntime.Snapshot creatures, WorldBlockBindings.Resolver blocks, Set<String> assetIds) {
        public String programName(String id) { var program = programs.get(id); return program == null ? id : program.getDefinition().getName(); }
    }

    public static boolean canStart(ServerLevel level, LivingEntity actor, String program, Vec3 origin) {
        requireThread(level);
        Bound bound = WORLDS.get(level);
        if (memoryType == null || bound == null || !bound.snapshot.programs.containsKey(program) || !sourceValid(level, actor)
            || !finite(origin) || actor.position().distanceToSqr(origin) > 24 * 24
            || !level.isInValidBounds(BlockPos.containing(origin)) || !level.getWorldBorder().isWithinBounds(BlockPos.containing(origin))
            || !loaded(level, new AABB(origin, origin).inflate(1))) return false;
        if (allInstances().size() >= MAX_ACTIVE) return false;
        int owned = 0;
        for (Instance instance : allInstances()) if (instance.actor.getUUID().equals(actor.getUUID())) {
            if (instance.programId.equals(program)) return false;
            owned++;
        }
        if (owned >= MAX_PER_ACTOR) return false;
        try { return memory(actor, bound.snapshot.scope).entry(program).readyAt() <= level.getGameTime(); }
        catch (RuntimeException corrupt) { return false; }
    }
    public static PreparedCast prepareStart(ServerLevel level, LivingEntity actor, String program, Vec3 origin,
                                            LivingEntity target, int cooldownTicks, boolean cancelOnTargetLoss) {
        return prepareStart(level,actor,program,origin,target,cooldownTicks,cancelOnTargetLoss,AbilityValues.none());
    }
    public static PreparedCast prepareStart(ServerLevel level, LivingEntity actor, String program, Vec3 origin,
                                            LivingEntity target, int cooldownTicks, boolean cancelOnTargetLoss, AbilityValue args) {
        AbilityValues.validate(args);
        if (cooldownTicks < 1 || cooldownTicks > 72000) throw new IllegalArgumentException("Ability cooldown must be 1..72000 ticks");
        if (!canStart(level, actor, program, origin)) return null;
        if (target != null && (target.level() != level || !target.isAlive())) return null;
        Bound bound = WORLDS.get(level);
        var instance = new Instance(bound, actor, program, origin, target, cooldownTicks, cancelOnTargetLoss,null,args);
        // Validate the pending recovery-debt/state entry before an adapter charges costs or writes its ledger.
        try { memory(actor, bound.snapshot.scope).withEntry(program, instance.persistedEntry.withReadyAt(Math.addExact(level.getGameTime(), cooldownTicks))); }
        catch (IllegalArgumentException capacity) { return null; }
        bound.instances.put(instance.id, instance); // Reservation only. No code, costs or actor memory writes yet.
        return new PreparedCast(instance);
    }
    public static boolean start(ServerLevel level, LivingEntity actor, String program, Vec3 origin, LivingEntity target, int cooldownTicks, boolean cancelOnTargetLoss) {
        try (var prepared = prepareStart(level, actor, program, origin, target, cooldownTicks, cancelOnTargetLoss)) {
            if (prepared == null) return false;
            prepared.commit(); return true;
        }
    }
    public static final class PreparedCast implements AutoCloseable {
        private final Instance instance;
        private boolean closed;
        private PreparedCast(Instance instance) { this.instance = instance; }
        public UUID commit() {
            requireThread(instance.bound.level);
            if (closed || instance.committed || !instance.valid() || !instance.bound.instances.containsKey(instance.id))
                throw new IllegalStateException("Ability activation reservation became unavailable");
            persist(instance); instance.committed = true;
            NativeAbilityDebug.started(instance.bound.level,instance.actor,instance.programId,instance.id,instance.machine);
            return instance.id;
        }
        @Override public void close() {
            if (!closed && !instance.committed) { instance.machine.cancel("reservation-closed"); instance.bound.instances.remove(instance.id); }
            closed = true;
        }
    }

    public static int activeCount(ServerLevel level) { Bound bound = WORLDS.get(level); return bound == null ? 0 : bound.instances.size(); }
    /** Periodic observation cannot consume the last sixteen global slots or an actor's last reaction slot. */
    public static boolean backgroundSlotAvailable(LivingEntity actor) {
        if (!(actor.level() instanceof ServerLevel level)) return false;
        requireThread(level);
        var instances=allInstances();
        return WORLDS.containsKey(level) && instances.size()<MAX_ACTIVE-16
            && instances.stream().filter(instance -> instance.actor==actor).count()<MAX_PER_ACTOR-1;
    }
    public static boolean isActive(LivingEntity actor, String program) {
        return allInstances().stream().anyMatch(instance -> instance.actor == actor && instance.programId.equals(program) && instance.committed);
    }
    public static boolean hasActiveProgram(LivingEntity actor) {
        if (!(actor.level() instanceof ServerLevel level)) return false;
        Bound bound = WORLDS.get(level);
        return bound != null && bound.instances.values().stream().anyMatch(instance -> instance.actor == actor && instance.committed);
    }
    public static Map<String, AbilityValue> state(LivingEntity actor, String program) {
        for (Instance instance : allInstances()) if (instance.actor == actor && instance.programId.equals(program)) return instance.machine.snapshotState();
        var stored = memoryType == null ? null : ((AttachmentTarget)actor).getAttached(memoryType);
        return stored == null ? Map.of() : stored.entry(program).state();
    }
    public static String lastFailure(LivingEntity actor, String program) { return FAILURES.getOrDefault(actor, Map.of()).get(program); }
    public static void cancelOwner(LivingEntity actor) { for (Instance instance : allInstances()) if (instance.actor == actor) finish(instance, "owner-cancelled"); }
    public static void cancelProgram(LivingEntity actor, String program) {
        for (Instance instance : allInstances()) if (instance.actor == actor && instance.programId.equals(program)) finish(instance, "program-cancelled");
    }
    public static void emit(ServerLevel level, LivingEntity actor, String event, Map<String, AbilityValue> payload) {
        requireThread(level);
        for (Instance instance : allInstances()) if (instance.bound.level == level && instance.actor == actor && instance.committed)
            instance.machine.emit(event, payload);
    }
    public static UUID invocation(LivingEntity actor, String program) {
        for (Instance instance : allInstances()) if (instance.actor == actor && instance.programId.equals(program) && instance.committed) return instance.id;
        return null;
    }
    public static boolean emitInvocation(ServerLevel level, UUID id, String event, Map<String, AbilityValue> payload) {
        requireThread(level);
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        return instance != null && instance.committed && instance.valid() && instance.machine.emit(event, payload);
    }
    public static boolean holdInvocation(ServerLevel level, UUID id, int ticks) {
        requireThread(level);
        if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("Event observation lease must be 1..1200 ticks");
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        if (instance == null || !instance.committed || !instance.valid()) return false;
        instance.context.observerUntil = Math.max(instance.context.observerUntil, level.getGameTime() + ticks); return true;
    }
    public static boolean cancelInvocation(ServerLevel level, UUID id) {
        requireThread(level);
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        if (instance == null) return false;
        finish(instance, "invocation-cancelled"); return true;
    }
    public static String caption(CreatureEntity actor) {
        if (!(actor.level() instanceof ServerLevel level)) return "";
        Bound bound = WORLDS.get(level); if (bound == null) return "";
        Context latest = null; long now = level.getGameTime();
        for (Instance instance : bound.instances.values()) if (instance.actor == actor && instance.committed
            && instance.context.captionUntil > now && (latest == null || instance.context.captionOrder > latest.captionOrder)) latest = instance.context;
        return latest == null ? "" : latest.caption;
    }

    /** Latest live presentation wins. Retiring an unrelated/older invocation never clears another owner's cue. */
    private static void refreshPose(Bound bound, LivingEntity actor) {
        if (!(actor instanceof CreatureEntity creature)) return;
        Context latest = null; long now = bound.level.getGameTime();
        for (Instance instance : bound.instances.values()) if (instance.actor == actor && instance.committed
            && instance.context.poseUntil > now && (latest == null || instance.context.poseOrder > latest.poseOrder)) latest = instance.context;
        if (latest != null) {
            bound.poseOwners.put(actor.getUUID(), latest.invocation());
            creature.setAbilityPose(latest.pose);
        } else if (bound.poseOwners.remove(actor.getUUID()) != null) creature.setAbilityPose("idle");
    }

    /** The adapter acquires a target; every action after activation belongs to source code. */
    public static void creatureTick(CreatureEntity actor, LivingEntity target) {
        var definition = actor.definition(); if (definition == null || definition.getAbility() == null || !(actor.level() instanceof ServerLevel level)) return;
        var binding = definition.getAbility();
        if (isActive(actor,binding.getProgram()) || AbilityActorControl.hasControl(actor)) return;
        var scope = snapshot(level); if (scope == null) return;
        if (memory(actor, scope.scope).entry(binding.getProgram()).readyAt() > level.getGameTime()) { actor.getNavigation().stop(); actor.setAbilityPose("recovery"); return; }
        actor.getLookControl().setLookAt(target, 30, 30);
        if (actor.distanceToSqr(target) > binding.getRange() * binding.getRange() || !actor.getSensing().hasLineOfSight(target)) {
            actor.setAbilityPose("chase");
            if (actor.tickCount % 10 == 0 || actor.getNavigation().isDone()) actor.getNavigation().moveTo(target, 1.1);
            return;
        }
        actor.getNavigation().stop(); actor.setAbilityPose("idle");
        start(level, actor, binding.getProgram(), actor.position(), target, binding.getCooldownTicks(), binding.getCancelOnTargetLoss());
    }

    private static void tick(MinecraftServer server) {
        for(Bound bound:WORLDS.values()) if(bound.level.getServer()==server) { bound.worldEdits.tick(bound.level); NativeAbilityDebug.tick(bound.level); }
        List<Instance> instances = allInstances().stream().filter(instance -> instance.bound.level.getServer() == server).toList();
        int operations = 8192;
        for (int offset = 0; offset < instances.size(); offset++) {
            Instance instance = instances.get((offset + Math.floorMod(server.getTickCount(), Math.max(1, instances.size()))) % instances.size());
            if (!instance.committed) { if (instance.bound.level.getGameTime() > instance.reservedAt + 1) finish(instance, "expired-reservation"); continue; }
            if (!instance.valid()) { finish(instance, "owner-or-target-unavailable"); continue; }
            if (operations <= 0) break;
            try {
                long now = instance.bound.level.getGameTime();
                instance.context.nativeCalls = 0; instance.context.tick(now);
                if (instance.machine.isIdle() && !instance.context.hasResources()) { finish(instance, "completed"); continue; }
                var result = instance.machine.tick(now, Math.min(OPS_PER_TICK, operations));
                operations -= result.getOperations();
                if (instance.machine.failure() != null) {
                    String failure = instance.machine.failure();
                    Map<String, String> errors = FAILURES.computeIfAbsent(instance.actor, ignored -> new HashMap<>());
                    if (!failure.equals(errors.put(instance.programId, failure))) Worldsmith.LOGGER.warn("Ability {} stopped: {}", instance.programId, failure);
                    finish(instance, "program-error");
                } else {
                    persist(instance);
                    if (instance.machine.isStopped() || instance.machine.isIdle() && !instance.context.hasResources()) finish(instance, "completed");
                }
            } catch (RuntimeException failure) {
                FAILURES.computeIfAbsent(instance.actor, ignored -> new HashMap<>()).put(instance.programId, String.valueOf(failure.getMessage()));
                Worldsmith.LOGGER.warn("Ability host stopped {}", instance.programId, failure); finish(instance, "host-error");
            }
        }
    }

    private static void persist(Instance instance) {
        var memory = memory(instance.actor, instance.bound.snapshot.scope);
        long ready = Math.addExact(instance.bound.level.getGameTime(), instance.cooldown);
        long revision = instance.machine.stateRevision();
        var entry = revision == instance.persistedRevision ? instance.persistedEntry.withReadyAt(ready)
            : AbilityActorMemory.Entry.fromState(ready, instance.machine.snapshotState());
        var updated = memory.withEntry(instance.programId, entry);
        if (updated != memory) ((AttachmentTarget)instance.actor).setAttached(memoryType, updated);
        instance.persistedEntry = entry; instance.persistedRevision = revision;
    }
    private static void finish(Instance instance, String reason) {
        if (instance.bound.instances.remove(instance.id) == null) return;
        for (Instance child : List.copyOf(instance.bound.instances.values())) if (instance.id.equals(child.parent)) finish(child,"parent-" + reason);
        if (instance.committed) try { persist(instance); } catch (RuntimeException error) { Worldsmith.LOGGER.error("Ability state could not be saved for {}", instance.programId, error); }
        instance.machine.cancel(reason); instance.context.cleanup();
    }
    private static AbilityActorMemory memory(LivingEntity actor, String scope) {
        var stored = ((AttachmentTarget)actor).getAttached(memoryType);
        if (stored == null) return AbilityActorMemory.empty(scope);
        if (!stored.scope().equals(scope)) throw new IllegalStateException("Ability memory belongs to a different world bundle");
        return stored;
    }
    private static List<Instance> allInstances() {
        var result = new ArrayList<Instance>(); WORLDS.values().forEach(bound -> result.addAll(bound.instances.values())); return result;
    }
    private static void requireThread(ServerLevel level) { if (!level.getServer().isSameThread()) throw new IllegalStateException("Abilities execute on the owning server thread"); }
    private static boolean sourceValid(ServerLevel level, LivingEntity actor) {
        return actor != null && actor.level() == level && actor.isAlive() && !actor.isRemoved()
            && !(actor instanceof Player player && player.isSpectator()) && !(actor instanceof Mob mob && mob.isNoAi());
    }
    static boolean finite(Vec3 pos) { return Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z); }
    public static boolean loaded(ServerLevel level, AABB box) {
        if (box.getXsize() > 80 || box.getZsize() > 80) return false;
        for (int x = ((int)Math.floor(box.minX)) >> 4; x <= ((int)Math.floor(box.maxX)) >> 4; x++)
            for (int z = ((int)Math.floor(box.minZ)) >> 4; z <= ((int)Math.floor(box.maxZ)) >> 4; z++) if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
    static AbilityValue.VectorValue vector(Vec3 vector) { return AbilityValues.vector(vector.x, vector.y, vector.z); }
    static AbilityValue handle(Entity entity) { return entity == null ? AbilityValues.none() : AbilityValues.entity(entity.getUUID().toString()); }

    private static final class Bound {
        final ServerLevel level; final Snapshot snapshot; final Map<UUID, Instance> instances = new LinkedHashMap<>();
        final Map<UUID, UUID> poseOwners = new HashMap<>();
        final Map<UUID, Scene> inheritedScenes = new HashMap<>();
        AbilitySceneSavedData sceneData;
        AbilityWorldSavedData worldEdits;
        long presentationOrder;
        Bound(ServerLevel level, Snapshot snapshot) { this.level = level; this.snapshot = snapshot; }
    }
    private static final class Instance {
        final UUID id = UUID.randomUUID();
        final Bound bound; final LivingEntity actor, target; final String programId; final Vec3 origin;
        final int cooldown; final boolean cancelOnTargetLoss; final Context context; final AbilityMachine machine; final long reservedAt;
        AbilityActorMemory.Entry persistedEntry;
        long persistedRevision;
        boolean committed;
        final UUID parent;
        final int depth;
        Scene scene;
        Instance(Bound bound, LivingEntity actor, String program, Vec3 origin, LivingEntity target, int cooldown, boolean cancelOnTargetLoss) {
            this(bound,actor,program,origin,target,cooldown,cancelOnTargetLoss,null,AbilityValues.none());
        }
        Instance(Bound bound, LivingEntity actor, String program, Vec3 origin, LivingEntity target, int cooldown, boolean cancelOnTargetLoss, Instance parent, AbilityValue args) {
            this.bound = bound; this.actor = actor; this.programId = program; this.origin = origin; this.target = target; this.cooldown = cooldown; this.cancelOnTargetLoss = cancelOnTargetLoss;
            this.parent = parent == null ? null : parent.id; depth = parent == null ? 0 : parent.depth+1;
            scene = parent == null ? bound.inheritedScenes.getOrDefault(actor.getUUID(),new Scene("default",BlockPos.containing(origin))) : parent.scene;
            reservedAt = bound.level.getGameTime(); context = new Context(this);
            persistedEntry = memory(actor, bound.snapshot.scope).entry(program);
            machine = new AbilityMachine(bound.snapshot.programs.get(program), context,
                Map.of("self", handle(actor), "target", handle(target), "origin", vector(origin), "args", args), persistedEntry.state());
        }
        boolean valid() {
            if (WORLDS.get(bound.level) != bound || !sourceValid(bound.level, actor) || actor.position().distanceToSqr(origin) > 32 * 32
                || !loaded(bound.level, actor.getBoundingBox().inflate(1))) return false;
            if (cancelOnTargetLoss && target != null && (target.level() != bound.level || !target.isAlive()
                || target instanceof Player player && (player.isCreative() || player.isSpectator()) || actor instanceof Mob mob && mob.getTarget() != target)) return false;
            return true;
        }
    }

    /** World-aware API context handed only to installed providers, never to pack source code. */
    public static final class Context implements AbilityHost {
        private final Instance instance;
        private final Map<Integer, Cue> cues = new LinkedHashMap<>();
        private final Map<UUID, Long> projectiles = new HashMap<>();
        private final Map<UUID, Long> entities = new HashMap<>();
        private final Map<Integer, Lease> leases = new HashMap<>();
        private int nextCue, nativeCalls;
        private int permanentWrites;
        private int controlHandle;
        private long poseUntil, captionUntil, poseOrder, captionOrder, observerUntil;
        private String pose = "idle", caption = "";
        Context(Instance instance) { this.instance = instance; }
        public ServerLevel level() { return instance.bound.level; }
        public LivingEntity actor() { return instance.actor; }
        public Vec3 origin() { return instance.origin; }
        public Snapshot snapshot() { return instance.bound.snapshot; }
        public UUID invocation() { return instance.id; }
        public boolean active() { return instance.committed && instance.valid(); }
        AbilityWorldSavedData worldEdits() { return instance.bound.worldEdits; }
        public void chargePermanentWrite() { if (permanentWrites >= 16) throw new IllegalArgumentException("Permanent block write budget exceeded"); permanentWrites++; }
        public AbilityValue startChild(String program, AbilityValue args) {
            guard(); AbilityValues.validate(args);
            if (instance.depth >= 4 || !canStart(level(),actor(),program,origin())) return AbilityValues.none();
            Instance child = new Instance(instance.bound,actor(),program,origin(),instance.target,20,false,instance,args);
            try { memory(actor(),snapshot().scope).withEntry(program,child.persistedEntry.withReadyAt(Math.addExact(level().getGameTime(),20))); }
            catch (IllegalArgumentException capacity) { return AbilityValues.none(); }
            instance.bound.instances.put(child.id,child);
            try (var prepared = new PreparedCast(child)) { return AbilityValues.text(prepared.commit().toString()); }
        }
        public boolean cancelChild(String id) {
            Instance other = find(id);
            if (other == null || other == instance || !descendsFrom(other,instance)) return false;
            finish(other,"parent-cancelled"); return true;
        }
        private Instance find(String id) {
            try { return instance.bound.instances.get(UUID.fromString(id)); }
            catch (IllegalArgumentException invalid) { return null; }
        }
        public boolean send(String id,String tag,AbilityValue data) {
            Instance other = find(id);
            if (other == null || !other.committed || !other.valid() || other.actor.distanceToSqr(actor()) > 32*32
                || !(other.scene.equals(instance.scene) || descendsFrom(other,instance) || descendsFrom(instance,other))) return false;
            return other.machine.emit("signal",Map.of("event_tag",AbilityValues.text(tag),"event_data",data,"event_entity",handle(actor())));
        }
        public boolean joinScene(String name, Vec3 anchor) { AbilityData.id(name); instance.scene = new Scene(name,BlockPos.containing(anchor)); return true; }
        public Vec3 sceneAnchor() { return Vec3.atCenterOf(instance.scene.anchor); }
        public String sceneKey() { return instance.scene.name + "@" + instance.scene.anchor.asLong(); }
        public int emitScene(String tag,AbilityValue data) {
            int sent = 0;
            for (Instance other : List.copyOf(instance.bound.instances.values())) if (other.scene.equals(instance.scene) && send(other.id.toString(),tag,data)) sent++;
            return sent;
        }
        private AbilitySceneSavedData scenes() {
            if (instance.bound.sceneData == null) instance.bound.sceneData = AbilitySceneSavedData.load(level(),snapshot().scope);
            return instance.bound.sceneData;
        }
        public AbilityValue sharedSceneGet(String key) { return scenes().get(sceneKey(),key); }
        public boolean sharedSceneSet(String key,AbilityValue value) { return scenes().put(sceneKey(),key,value); }
        @Override public AbilityValue call(String name, List<? extends AbilityValue> arguments) {
            guard(); if (++nativeCalls > MAX_NATIVE_CALLS) throw new IllegalArgumentException("Native capability calls exceed the per-tick budget");
            Provider provider = snapshot().providers.get(name);
            if (provider == null) throw new IllegalArgumentException("No native capability provider: " + name);
            return provider.function.invoke(this, List.copyOf(arguments));
        }
        public void guard() { if (!instance.valid()) throw new IllegalStateException("Ability source is no longer available"); }
        public Entity resolve(AbilityValue value) {
            if (!(value instanceof AbilityValue.EntityValue ref)) return null;
            try {
                Entity entity = level().getEntity(UUID.fromString(ref.getId()));
                if (entity == null || entity.isRemoved() || entity.level() != level() || entity.position().distanceToSqr(origin()) > 32 * 32
                    || !loaded(level(), entity.getBoundingBox().inflate(1))) return null;
                return entity;
            } catch (IllegalArgumentException malformed) { return null; }
        }
        public LivingEntity living(AbilityValue value) { Entity entity = resolve(value); return entity instanceof LivingEntity living && living.isAlive() ? living : null; }
        public Vec3 point(AbilityValue value) {
            if (!(value instanceof AbilityValue.VectorValue v)) throw new IllegalArgumentException("Expected vector");
            Vec3 point = new Vec3(v.getX(), v.getY(), v.getZ());
            if (!finite(point) || origin().distanceToSqr(point) > 24 * 24 || !level().isInValidBounds(BlockPos.containing(point))
                || !level().getWorldBorder().isWithinBounds(BlockPos.containing(point)) || !loaded(level(), new AABB(point, point).inflate(1)))
                throw new IllegalArgumentException("Ability point is outside its loaded world scope");
            return point;
        }
        public AABB region(AbilityValue value) {
            var b = AbilityRegions.bounds(value);
            var box = new AABB(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
            if (box.getXsize() > 32 || box.getYsize() > 32 || box.getZsize() > 32 || box.getCenter().distanceToSqr(origin()) > 24 * 24
                || !level().getWorldBorder().isWithinBounds(box) || !loaded(level(), box.inflate(1))) throw new IllegalArgumentException("Ability region is outside its loaded bounds");
            return box;
        }
        public int telegraph(AbilityValue region, int ticks, String color) {
            region(region); reserveResource();
            int rgb = switch (color) { case "amber" -> 0xFFB52E; case "red" -> 0xEF5350; case "blue" -> 0x55AAFF; case "white" -> 0xFFFFFF; case "green" -> 0x6DDF91; default -> throw new IllegalArgumentException("Unknown telegraph color"); };
            int handle = ++nextCue; cues.put(handle, new Cue(region, level().getGameTime() + ticks, rgb)); return handle;
        }
        public boolean clearCue(int handle) { return cues.remove(handle) != null || releaseLease(handle); }
        public AbilityValue ownEntity(Entity entity, int ticks) {
            guard(); reserveResource();
            if (ticks < 1 || ticks > 1200 || entity.level() != level() || entity.isRemoved()) throw new IllegalArgumentException("Invalid owned entity lease");
            point(vector(entity.position()));
            if (!loaded(level(), entity.getBoundingBox().inflate(1)) || !level().getWorldBorder().isWithinBounds(entity.getBoundingBox()))
                throw new IllegalArgumentException("Owned entity is outside loaded bounds");
            entities.put(entity.getUUID(), level().getGameTime() + ticks + 1L);
            instance.bound.inheritedScenes.put(entity.getUUID(),instance.scene);
            ((AttachmentTarget)entity).setAttached(ownedByType,invocation().toString());
            if (!level().addFreshEntity(entity) || entity.isRemoved()) {
                entities.remove(entity.getUUID()); instance.bound.inheritedScenes.remove(entity.getUUID()); entity.discard();
                throw new IllegalStateException("Owned entity insertion failed");
            }
            return handle(entity);
        }
        public boolean ownsEntity(Entity entity) {
            if (entity == null || entity.level() != level() || entity.isRemoved() || !instance.valid()) return false;
            long now = level().getGameTime();
            return entities.getOrDefault(entity.getUUID(), Long.MIN_VALUE) > now || projectiles.getOrDefault(entity.getUUID(), Long.MIN_VALUE) > now;
        }
        public boolean retireEntity(Entity entity) {
            if (!ownsEntity(entity)) return false;
            entities.remove(entity.getUUID()); instance.bound.inheritedScenes.remove(entity.getUUID()); projectiles.remove(entity.getUUID()); entity.discard(); return true;
        }
        public int lease(int ticks, Runnable cleanup) {
            return lease("generic",ticks,cleanup);
        }
        public int lease(String kind,int ticks,Runnable cleanup) {
            guard(); reserveResource();
            if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("Owned lease must be 1..1200 ticks");
            int id = ++nextCue; leases.put(id, new Lease(kind,level().getGameTime() + ticks, Objects.requireNonNull(cleanup))); return id;
        }
        public boolean releaseLease(int id,String kind) { var lease = leases.get(id); return lease != null && lease.kind.equals(kind) && releaseLease(id); }
        public boolean releaseLease(int id) {
            Lease lease = leases.remove(id); if (lease == null) return false;
            if (id == controlHandle && lease.kind.equals("control")) controlHandle = 0;
            try { lease.cleanup.run(); }
            catch (RuntimeException failure) { Worldsmith.LOGGER.error("Ability lease cleanup failed: {} / {}", instance.programId, id, failure); }
            return true;
        }
        public void pose(String pose, int ticks) {
            if (actor() instanceof CreatureEntity creature) {
                creature.setAbilityPose(pose); // Validate the primitive before changing ownership.
                this.pose = pose; poseUntil = level().getGameTime() + ticks; poseOrder = ++instance.bound.presentationOrder;
                instance.bound.poseOwners.put(actor().getUUID(), invocation());
            }
        }
        public void caption(String value, int ticks) {
            caption = value; captionUntil = level().getGameTime() + ticks; captionOrder = ++instance.bound.presentationOrder;
        }
        /** Actor arbitration is a regular owned resource; mere source execution never owns native AI. */
        public int claimControl(int priority,int ticks) {
            guard();
            if (priority < 0 || priority > 100 || ticks < 1 || ticks > 1200) throw new IllegalArgumentException("Control needs priority 0..100 and ticks 1..1200");
            if (!(actor() instanceof CreatureEntity creature) || !AbilityActorControl.canClaim(creature,invocation(),priority)) return 0;
            // Replacing our own token reuses its resource slot, but no old motion is resurrected.
            if (controlHandle != 0) releaseLease(controlHandle,"control");
            reserveResource();
            int id = ++nextCue;
            leases.put(id,new Lease("control",Math.addExact(level().getGameTime(),ticks),() -> AbilityActorControl.release(creature,invocation(),id)));
            controlHandle = id;
            try {
                if (AbilityActorControl.claim(creature,invocation(),id,priority,Math.addExact(level().getGameTime(),ticks),() -> releaseLease(id,"control"))) return id;
                releaseLease(id,"control"); return 0;
            } catch (RuntimeException failure) { releaseLease(id,"control"); throw failure; }
        }
        public boolean holdsControl(int handle) {
            var lease=leases.get(handle);
            return lease!=null && lease.kind.equals("control") && actor() instanceof CreatureEntity creature
                && AbilityActorControl.held(creature,invocation(),handle);
        }
        public boolean controlsActor() { return controlHandle!=0 && holdsControl(controlHandle); }
        public boolean acceptControlPath(net.minecraft.world.level.pathfinder.Path path) {
            return actor() instanceof CreatureEntity creature && controlsActor() && AbilityActorControl.acceptPath(creature,invocation(),path);
        }
        public AbilityValue projectile(Vec3 position, Vec3 velocity, float gravity, int lifetime, String tag) {
            reserveResource();
            var projectile = new AbilityProjectile(AbilityProjectile.type(), level());
            projectile.initialize(invocation(), actor(), position, velocity, gravity, lifetime, tag);
            if (!level().addFreshEntity(projectile)) throw new IllegalStateException("Ability projectile insertion failed");
            // World time bounds the resource even if physics pauses in a loaded, non-ticking chunk.
            projectiles.put(projectile.getUUID(), level().getGameTime() + lifetime + 1L); return handle(projectile);
        }
        public boolean signal(String name, AbilityValue data) { return instance.machine.emit("signal", Map.of("event_tag", AbilityValues.text(name), "event_data", data)); }
        private int resourceCount() { return cues.size() + projectiles.size() + entities.size() + leases.size(); }
        public void reserveResource() {
            guard();
            if (resourceCount() >= 8) throw new IllegalArgumentException("Ability instance resource budget exceeded");
            int total = 0;
            for (Instance active : instance.bound.instances.values()) total += active.context.resourceCount();
            if (total >= 128) throw new IllegalArgumentException("Ability world resource budget exceeded");
        }
        private void tick(long now) {
            cues.entrySet().removeIf(entry -> entry.getValue().until <= now);
            for (var entry : List.copyOf(leases.entrySet())) if (entry.getValue().until <= now) releaseLease(entry.getKey());
            for (var entry : List.copyOf(entities.entrySet())) {
                Entity entity = level().getEntity(entry.getKey());
                if (entry.getValue() <= now || entity != null && (!entity.isAlive() || entity.isRemoved())) {
                    entities.remove(entry.getKey()); instance.bound.inheritedScenes.remove(entry.getKey()); if (entity != null && !entity.isRemoved()) entity.discard();
                }
            }
            for (var entry : List.copyOf(projectiles.entrySet())) if (entry.getValue() <= now) {
                projectiles.remove(entry.getKey()); // Retire ownership before removal callbacks.
                Entity entity = level().getEntity(entry.getKey());
                if (entity instanceof AbilityProjectile projectile && invocation().equals(projectile.invocation())) projectile.discard();
            }
            if (now % 4 == 0) for (Cue cue : cues.values()) for (var point : AbilityRegions.outline(cue.region, 32)) {
                var pos = new Vec3(point.getX(), point.getY() + .08, point.getZ());
                if (loaded(level(), new AABB(pos, pos).inflate(1))) level().sendParticles(new DustParticleOptions(cue.color, .9F), pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            }
            if (poseUntil > 0 && poseUntil <= now) { poseUntil = 0; refreshPose(instance.bound, actor()); }
        }
        private boolean hasResources() {
            long now = level().getGameTime();
            return !cues.isEmpty() || !projectiles.isEmpty() || !entities.isEmpty() || !leases.isEmpty()
                || poseUntil > now || captionUntil > now || observerUntil > now
                || instance.bound.instances.values().stream().anyMatch(child -> invocation().equals(child.parent));
        }
        private void cleanup() {
            cues.clear(); for (UUID id : List.copyOf(projectiles.keySet())) { Entity projectile = level().getEntity(id); if (projectile instanceof AbilityProjectile) projectile.discard(); }
            projectiles.clear();
            for (UUID id : List.copyOf(entities.keySet())) { instance.bound.inheritedScenes.remove(id); Entity entity = level().getEntity(id); if (entity != null) entity.discard(); }
            entities.clear();
            for (int id : List.copyOf(leases.keySet())) releaseLease(id);
            if (poseOrder > 0) { poseUntil = 0; refreshPose(instance.bound, actor()); }
        }
    }
    private record Scene(String name,BlockPos anchor) { Scene { anchor = anchor.immutable(); } }
    private static boolean descendsFrom(Instance child,Instance ancestor) {
        for (int depth = 0; child != null && depth <= 4; depth++) {
            if (child == ancestor) return true;
            child = child.parent == null ? null : child.bound.instances.get(child.parent);
        }
        return false;
    }
    private record Lease(String kind,long until, Runnable cleanup) {}
    private record Cue(AbilityValue region, long until, int color) {}
    public static boolean invocationActive(ServerLevel level, UUID id) { Bound bound = WORLDS.get(level); return bound != null && bound.instances.containsKey(id) && bound.instances.get(id).committed; }
    public static boolean resourceActive(ServerLevel level, UUID id, UUID entity) {
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        return instance != null && instance.committed && instance.valid()
            && (instance.context.entities.getOrDefault(entity, Long.MIN_VALUE) > level.getGameTime()
                || instance.context.projectiles.getOrDefault(entity, Long.MIN_VALUE) > level.getGameTime());
    }
    static boolean projectileActive(ServerLevel level, UUID id, UUID projectile) {
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        return instance != null && instance.committed && instance.valid()
            && instance.context.projectiles.getOrDefault(projectile, Long.MIN_VALUE) > level.getGameTime();
    }
    static void projectileHit(ServerLevel level, UUID id, UUID projectile, Entity target, Vec3 position, String tag) {
        projectileHit(level, id, projectile, target, position, tag, Map.of());
    }
    static void projectileHit(ServerLevel level, UUID id, UUID projectile, Entity target, Vec3 position, String tag, Map<String, AbilityValue> details) {
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        if (projectileActive(level, id, projectile)) instance.machine.emit("projectile_hit", Map.of("event_entity", handle(target), "event_position", vector(position),
            "event_tag", AbilityValues.text(tag), "event_data", AbilityValues.map(details)));
    }
    static void projectileRemoved(ServerLevel level, UUID id, UUID projectile) {
        Bound bound = WORLDS.get(level); Instance instance = bound == null ? null : bound.instances.get(id);
        if (instance != null) instance.context.projectiles.remove(projectile);
    }
}
