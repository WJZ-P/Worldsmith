package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithWorldCreationBridge;
import net.minecraft.client.gui.components.debug.DebugEntryBiome;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces only hash-scoped Worldsmith biome ids with their authored names. */
@Mixin(DebugEntryBiome.class)
public abstract class DebugEntryBiomeMixin {
	@Inject(method = "printBiome", at = @At("HEAD"), cancellable = true)
	private static void worldsmith$printFriendlyBiome(
		Holder<Biome> biome,
		CallbackInfoReturnable<String> callback
	) {
		String name = WorldsmithWorldCreationBridge.debugBiomeName(biome);
		if (name != null) {
			callback.setReturnValue(name);
		}
	}
}
