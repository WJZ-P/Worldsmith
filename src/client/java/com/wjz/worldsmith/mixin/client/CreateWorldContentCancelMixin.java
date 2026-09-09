package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithWorldCreationBridge;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** popScreen means cancellation, unlike removed(), which also occurs for vanilla reload progress screens. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldContentCancelMixin {
    @Inject(method="popScreen",at=@At("HEAD"))
    private void worldsmith$cancelContent(CallbackInfo ci) {
        WorldsmithWorldCreationBridge.onCancelled((CreateWorldScreen)(Object)this);
    }
}
