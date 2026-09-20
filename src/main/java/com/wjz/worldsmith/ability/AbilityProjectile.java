package com.wjz.worldsmith.ability;

import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Motion and collision only. The owning program's projectile_hit handler decides every gameplay effect. */
public final class AbilityProjectile extends ThrowableItemProjectile {
    private static final EntityDataAccessor<Float> GRAVITY = SynchedEntityData.defineId(AbilityProjectile.class, EntityDataSerializers.FLOAT);
    private static EntityType<AbilityProjectile> type;
    private UUID invocation;
    private String eventTag = "";
    private int remaining;
    private boolean delivered;
    public AbilityProjectile(EntityType<? extends AbilityProjectile> type, Level level) { super(type, level); }
    public static void register() {
        if (type != null) return;
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("worldsmith", "program_projectile"));
        type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<AbilityProjectile>of(AbilityProjectile::new, MobCategory.MISC)
            .sized(.25F, .25F).clientTrackingRange(10).updateInterval(1).noLootTable().build(key));
    }
    public static EntityType<AbilityProjectile> type() { return java.util.Objects.requireNonNull(type, "Ability projectile is not registered"); }
    public void initialize(UUID invocation, LivingEntity caster, Vec3 origin, Vec3 velocity, float gravity, int ticks, String tag) {
        this.invocation = invocation; remaining = ticks; eventTag = tag; setOwner(caster); setPos(origin); setDeltaMovement(velocity);
        getEntityData().set(GRAVITY, gravity);
    }
    public UUID invocation() { return invocation; }
    @Override protected Item getDefaultItem() { return Items.SNOWBALL; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(GRAVITY, .03F); }
    @Override protected double getDefaultGravity() { return getEntityData().get(GRAVITY); }
    @Override public boolean canUsePortal(boolean ignorePassenger) { return false; }
    @Override public void tick() {
        if (level() instanceof ServerLevel server && (invocation == null || --remaining < 0 || !WorldAbilityRuntime.projectileActive(server, invocation, getUUID())
            || !WorldAbilityRuntime.loaded(server, getBoundingBox().expandTowards(getDeltaMovement()).inflate(1)))) { discard(); return; }
        super.tick();
    }
    @Override protected void onHit(HitResult result) {
        if (level() instanceof ServerLevel server && !delivered && !isRemoved()) {
            delivered = true;
            Entity target = result instanceof EntityHitResult hit ? hit.getEntity() : null;
            WorldAbilityRuntime.projectileHit(server, invocation, getUUID(), target, result.getLocation(), eventTag, impactContext(result));
            discard();
        }
    }
    /** Exact block-face normals; entity collisions expose an explicitly labelled incoming-direction approximation. */
    public Map<String, AbilityValue> impactContext(HitResult result) {
        var data = new LinkedHashMap<String, AbilityValue>();
        Vec3 incoming = getDeltaMovement();
        data.put("kind", AbilityValues.text(result.getType().name().toLowerCase(java.util.Locale.ROOT)));
        data.put("projectile", AbilityValues.entity(getUUID().toString()));
        data.put("incoming_velocity", AbilityValues.vector(incoming.x, incoming.y, incoming.z));
        Vec3 normal = incoming.lengthSqr() > 1e-12 ? incoming.normalize().scale(-1) : new Vec3(0, 1, 0);
        if (result instanceof BlockHitResult block) {
            var direction = block.getDirection(); normal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            var position = block.getBlockPos();
            data.put("normal_kind", AbilityValues.text("block_face"));
            data.put("block_position", AbilityValues.vector(position.getX(), position.getY(), position.getZ()));
            boolean loaded = level().hasChunkAt(position);
            data.put("block_loaded", AbilityValues.bool(loaded));
            data.put("block_id", AbilityValues.text(loaded ? BuiltInRegistries.BLOCK.getKey(level().getBlockState(position).getBlock()).toString() : ""));
            data.put("inside", AbilityValues.bool(block.isInside()));
            data.put("world_border", AbilityValues.bool(block.isWorldBorderHit()));
        } else data.put("normal_kind", AbilityValues.text("incoming_velocity_approximation"));
        data.put("normal", AbilityValues.vector(normal.x, normal.y, normal.z));
        return Map.copyOf(data);
    }
    @Override public void onRemoval(Entity.RemovalReason reason) {
        if (level() instanceof ServerLevel server) WorldAbilityRuntime.projectileRemoved(server, invocation, getUUID());
        super.onRemoval(reason);
    }
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        // No invocation continuation is persisted. A reloaded projectile is retired before ticking/hitting.
    }
    @Override protected void readAdditionalSaveData(ValueInput input) { super.readAdditionalSaveData(input); invocation = null; remaining = 0; }
}
