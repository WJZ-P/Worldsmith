package com.wjz.worldsmith.content.item;

import com.wjz.worldsmith.core.content.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.*;

/** One registered projectile host. Damage/effects are re-resolved from its immutable world's item definition. */
public final class ItemAbilityProjectile extends ThrowableItemProjectile {
    private static final EntityDataAccessor<Float> GRAVITY = SynchedEntityData.defineId(ItemAbilityProjectile.class, EntityDataSerializers.FLOAT);
    private static EntityType<ItemAbilityProjectile> type;
    private ItemActionTrigger trigger = ItemActionTrigger.USE;
    private int effectIndex;
    private int age;
    public ItemAbilityProjectile(EntityType<? extends ItemAbilityProjectile> type, Level level) { super(type, level); }
    public static synchronized void register() {
        if (type != null) return;
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("worldsmith:item_ability_projectile"));
        type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
            EntityType.Builder.<ItemAbilityProjectile>of(ItemAbilityProjectile::new, MobCategory.MISC).sized(0.25F, 0.25F)
                .clientTrackingRange(8).updateInterval(2).noLootTable().build(key));
    }
    public static EntityType<ItemAbilityProjectile> type() { return java.util.Objects.requireNonNull(type); }
    public void initialize(Player owner, ItemStack source, ItemActionTrigger trigger, int index, ItemEffect.Projectile effect) {
        setOwner(owner); setItem(source); this.trigger = trigger; this.effectIndex = index;
        setPos(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
        getEntityData().set(GRAVITY, effect.getGravity());
        Vec3 direction = owner.getLookAngle(); shoot(direction.x, direction.y, direction.z, effect.getSpeed(), 0);
    }
    @Override protected Item getDefaultItem() { return Items.SNOWBALL; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(GRAVITY, 0.03F); }
    @Override protected double getDefaultGravity() { return getEntityData().get(GRAVITY); }
    @Override public boolean canUsePortal(boolean ignorePassenger) { return false; }
    private ItemEffect.Projectile profile() {
        var definition = CustomItemRuntime.definition(level(), getItem());
        if (definition == null) return null;
        var action = ItemActions.action(definition, trigger);
        return action != null && effectIndex >= 0 && effectIndex < action.getEffects().size()
            && action.getEffects().get(effectIndex) instanceof ItemEffect.Projectile projectile ? projectile : null;
    }
    @Override public void tick() {
        if (!level().isClientSide()) {
            var profile = profile();
            // Native first-tick bubble columns/gravity alter Y only; inertia reduces X/Z.
            // Include neighbor cells used by block collision/inside-block shape queries.
            if (profile == null || ++age > profile.getLifetimeTicks()
                || !ItemActions.loaded((ServerLevel)level(), getBoundingBox().expandTowards(getDeltaMovement()).inflate(1.0))) { discard(); return; }
            getEntityData().set(GRAVITY, profile.getGravity());
        }
        super.tick();
    }
    @Override protected void onHitEntity(EntityHitResult hit) {
        if (!(level() instanceof ServerLevel server) || isRemoved()) return;
        var profile = profile();
        if (profile == null) return;
        boolean damaged = hit.getEntity().hurtServer(server, damageSources().thrown(this, getOwner()), profile.getDamage());
        if ((damaged || profile.getDamage() == 0) && hit.getEntity() instanceof LivingEntity living)
            for (ItemStatusEffect effect : profile.getHitEffects()) living.addEffect(ItemActions.status(effect), getOwner());
    }
    @Override protected void onHit(HitResult result) { super.onHit(result); if (!level().isClientSide()) discard(); }
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output); output.putString("worldsmith_action", trigger.name());
        output.putInt("worldsmith_effect", effectIndex); output.putInt("worldsmith_age", age);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        try { trigger = ItemActionTrigger.valueOf(input.getStringOr("worldsmith_action", "USE")); } catch (IllegalArgumentException ignored) { effectIndex = -1; return; }
        effectIndex = input.getIntOr("worldsmith_effect", -1); age = Math.max(0, Math.min(201, input.getIntOr("worldsmith_age", 0)));
    }
}
