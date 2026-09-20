package com.wjz.worldsmith.mixin;

import com.wjz.worldsmith.ability.AbilityEventRuntime;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers forced native stop paths which never call Item.releaseUsing, without inventing input packets. */
@Mixin(LivingEntity.class)
public abstract class WorldAbilityUseMixin {
    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void worldsmith$observeUseStop(CallbackInfo info) {
        AbilityEventRuntime.stoppedUsing((LivingEntity)(Object)this);
    }
}
