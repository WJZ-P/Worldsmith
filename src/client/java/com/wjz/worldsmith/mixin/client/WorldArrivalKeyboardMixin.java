package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.quest.WorldArrivalOverlay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class WorldArrivalKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void worldsmith$dismissArrival(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (handle == Minecraft.getInstance().getWindow().handle() && action == GLFW.GLFW_PRESS && event.isEscape() && WorldArrivalOverlay.dismiss()) ci.cancel();
    }
}
