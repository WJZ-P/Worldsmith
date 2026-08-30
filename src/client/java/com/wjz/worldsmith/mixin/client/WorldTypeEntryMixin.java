package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithWorldCreationBridge;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives hash-scoped runtime presets the pack's human display name. */
@Mixin(WorldCreationUiState.WorldTypeEntry.class)
public abstract class WorldTypeEntryMixin {
	@Inject(method = "describePreset", at = @At("HEAD"), cancellable = true)
	private void worldsmith$describeGeneratedPreset(CallbackInfoReturnable<Component> callback) {
		WorldCreationUiState.WorldTypeEntry self = (WorldCreationUiState.WorldTypeEntry) (Object) this;
		Component name = WorldsmithWorldCreationBridge.displayName(self.preset());
		if (name != null) {
			callback.setReturnValue(name);
		}
	}
}
