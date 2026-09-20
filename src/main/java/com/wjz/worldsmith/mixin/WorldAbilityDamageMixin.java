package com.wjz.worldsmith.mixin;

import com.wjz.worldsmith.ability.AbilityCombatEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Both native implementations have armor then magic stores; modify the second result, before health/absorption. */
@Mixin({LivingEntity.class,Player.class})
public abstract class WorldAbilityDamageMixin {
    @ModifyVariable(method="actuallyHurt",at=@At(value="STORE",ordinal=1),ordinal=0,argsOnly=true)
    private float worldsmith$guardDamage(float damage,ServerLevel level,DamageSource source,float original) {
        return AbilityCombatEffects.intercept((LivingEntity)(Object)this,level,source,damage);
    }
}
