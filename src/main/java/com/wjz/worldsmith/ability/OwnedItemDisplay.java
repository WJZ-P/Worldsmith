package com.wjz.worldsmith.ability;

import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/** Canonical item presentation only: never a pickup, inventory, collision or saved reward. */
public final class OwnedItemDisplay extends Display.ItemDisplay {
    private static final EntityDataAccessor<String> SCOPE = SynchedEntityData.defineId(OwnedItemDisplay.class, EntityDataSerializers.STRING);
    private static EntityType<OwnedItemDisplay> type;
    private UUID invocation;
    public OwnedItemDisplay(EntityType<? extends OwnedItemDisplay> type, Level level) { super(type, level); }
    public static synchronized void register() {
        if (type != null) return;
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("worldsmith", "ability_item_display"));
        type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<OwnedItemDisplay>of(OwnedItemDisplay::new, MobCategory.MISC)
            .sized(.1F, .1F).clientTrackingRange(8).updateInterval(1).noSave().noSummon().noLootTable().build(key));
    }
    public static EntityType<OwnedItemDisplay> type() { return java.util.Objects.requireNonNull(type, "Owned item display is not registered"); }
    public void initialize(String scope, UUID owner) {
        if (!scope.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Visual item scope is invalid");
        invocation = java.util.Objects.requireNonNull(owner); getEntityData().set(SCOPE, scope);
    }
    public String scope() { return getEntityData().get(SCOPE); }
    public UUID invocation() { return invocation; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(SCOPE, ""); }
    @Override public void tick() {
        if (level() instanceof ServerLevel server && (invocation == null || !WorldAbilityRuntime.resourceActive(server, invocation, getUUID()))) { discard(); return; }
        super.tick();
    }
    @Override public AABB getBoundingBoxForCulling() { return new AABB(position(), position()).inflate(8); }
    @Override protected void addAdditionalSaveData(ValueOutput output) { /* noSave native type */ }
    @Override protected void readAdditionalSaveData(ValueInput input) { invocation = null; discard(); }
}
