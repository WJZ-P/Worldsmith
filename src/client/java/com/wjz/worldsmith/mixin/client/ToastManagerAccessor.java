package com.wjz.worldsmith.mixin.client;

import java.util.List;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the native top-right stack, including slide-out animation until rendering actually ends. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
    @Accessor("visibleToasts") List<?> worldsmith$getVisibleToasts();
}
