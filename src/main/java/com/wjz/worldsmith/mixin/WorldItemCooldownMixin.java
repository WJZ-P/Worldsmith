package com.wjz.worldsmith.mixin;

import com.wjz.worldsmith.content.item.ItemActions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseCooldown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve the cooldown component for HUD/group identity without charging native use side effects twice. */
@Mixin(UseCooldown.class)
public abstract class WorldItemCooldownMixin {
    @Inject(method="apply",at=@At("HEAD"),cancellable=true)
    private void worldsmith$actionOwnsCooldown(ItemStack stack, LivingEntity user, CallbackInfo ci) {
        if(ItemActions.ownsCooldown(stack,user))ci.cancel();
    }
}
