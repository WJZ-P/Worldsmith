package com.wjz.worldsmith.mixin.client;

import com.mojang.serialization.Lifecycle;
import com.wjz.worldsmith.client.WorldsmithExperimentalWarning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Version, deprecated/customized-world, resource-pack and storage recovery prompts stay in vanilla. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsExperimentalMixin {
    @Shadow
    protected abstract void openWorldLoadBundledResourcePack(LevelStorageAccess storage, WorldStem stem,
        PackRepository packs, Runnable onCancel);

    @Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true)
    private static void worldsmith$confirmPreparedBundle(Minecraft client, CreateWorldScreen parent,
        Lifecycle lifecycle, Runnable task, boolean skipWarning, CallbackInfo ci) {
        if (!skipWarning && WorldsmithExperimentalWarning.skipCreation(parent, lifecycle)) {
            ci.cancel();
            task.run();
        }
    }

    @Inject(method = "openWorldCheckWorldStemCompatibility", at = @At("HEAD"), cancellable = true)
    private void worldsmith$openVerifiedBundle(LevelStorageAccess storage, WorldStem stem,
        PackRepository packs, Runnable onCancel, CallbackInfo ci) {
        if (WorldsmithExperimentalWarning.skipSavedWorld(stem)) {
            ci.cancel();
            openWorldLoadBundledResourcePack(storage, stem, packs, onCancel);
        }
    }
}
