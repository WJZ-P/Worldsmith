package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.content.creative.WorldsmithCreativeContent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One registered tab, with the joined bundle's name; no permanent per-world registry entries. */
@Mixin(CreativeModeTab.class)
public abstract class WorldCreativeTabTitleMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void worldsmith$worldTitle(CallbackInfoReturnable<Component> result) {
        Component title = WorldsmithCreativeContent.displayTitle((CreativeModeTab)(Object)this);
        if (title != null) result.setReturnValue(title);
    }
}
