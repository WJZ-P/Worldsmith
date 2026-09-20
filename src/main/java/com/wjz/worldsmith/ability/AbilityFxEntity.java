package com.wjz.worldsmith.ability;

import java.util.UUID;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityDataRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/** Collision-free, non-persistent visual host; native Display supplies real transform interpolation. */
public final class AbilityFxEntity extends Display {
    private static final EntityDataSerializer<AbilityVisualData> SERIALIZER = EntityDataSerializer.forValueType(AbilityVisualData.CODEC);
    private static final EntityDataAccessor<AbilityVisualData> DATA = SynchedEntityData.defineId(AbilityFxEntity.class, SERIALIZER);
    private static EntityType<AbilityFxEntity> type;
    private UUID invocation;
    public interface ClientHooks {
        void tick(AbilityFxEntity entity);
        void removed(AbilityFxEntity entity);
    }
    private static ClientHooks clientHooks;
    public AbilityFxEntity(EntityType<? extends AbilityFxEntity> type, Level level) { super(type, level); }
    public static synchronized void register() {
        if (type != null) return;
        FabricEntityDataRegistry.register(Identifier.fromNamespaceAndPath("worldsmith", "ability_visual_data"), SERIALIZER);
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("worldsmith", "ability_fx"));
        type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<AbilityFxEntity>of(AbilityFxEntity::new, MobCategory.MISC)
            .sized(.1F, .1F).clientTrackingRange(8).updateInterval(1).noSave().noSummon().noLootTable().build(key));
    }
    public static EntityType<AbilityFxEntity> type() { return java.util.Objects.requireNonNull(type, "Ability FX type is not registered"); }
    public static void clientHooks(ClientHooks hooks) { clientHooks = hooks; }
    public void initialize(UUID owner, AbilityVisualData data) { invocation = java.util.Objects.requireNonNull(owner); getEntityData().set(DATA, data); }
    public AbilityVisualData visual() { return getEntityData().get(DATA); }
    public UUID invocation() { return invocation; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(DATA, AbilityVisualData.EMPTY); }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) { super.onSyncedDataUpdated(key); if (key.equals(DATA)) updateRenderState = true; }
    @Override protected void updateRenderSubState(boolean interpolate, float progress) { /* The immutable visual payload itself does not interpolate. Display's transform does. */ }
    @Override public void tick() {
        if (level() instanceof ServerLevel server && (invocation == null || !WorldAbilityRuntime.resourceActive(server, invocation, getUUID()))) { discard(); return; }
        super.tick();
        if (level().isClientSide() && clientHooks != null) clientHooks.tick(this);
    }
    @Override public void onRemoval(Entity.RemovalReason reason) {
        if (level().isClientSide() && clientHooks != null) clientHooks.removed(this);
        super.onRemoval(reason);
    }
    @Override public AABB getBoundingBoxForCulling() { return new AABB(position(), position()).inflate(visual().cullingRadius()); }
    @Override protected void addAdditionalSaveData(ValueOutput output) { /* EntityType.noSave: no live effect is persisted. */ }
    @Override protected void readAdditionalSaveData(ValueInput input) { invocation = null; discard(); }
}
