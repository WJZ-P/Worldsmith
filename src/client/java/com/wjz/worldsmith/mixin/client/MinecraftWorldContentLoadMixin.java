package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.content.WorldContentStartupBarrier;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/** 26.2 doWorldLoad starts with disconnect, then saves the level tag and starts its integrated server. */
@Mixin(Minecraft.class)
public abstract class MinecraftWorldContentLoadMixin {
    @Unique private WorldStem worldsmith$resumingStem;
    @Unique private LevelStorageAccess worldsmith$resumingStorage;
    @Unique private PackRepository worldsmith$resumingPacks;
    @Unique private Optional<GameRules> worldsmith$resumingRules;
    @Unique private boolean worldsmith$resumingNewWorld;
    @Unique private boolean worldsmith$skipRepeatedDisconnect;

    @Inject(method = "doWorldLoad", at = @At("HEAD"), cancellable = true)
    private void worldsmith$loadContentFirst(LevelStorageAccess storage, PackRepository packs, WorldStem stem,
        Optional<GameRules> gameRules, boolean newWorld, CallbackInfo ci) {
        if (worldsmith$resumingStem == stem && worldsmith$resumingStorage == storage && worldsmith$resumingPacks == packs
            && worldsmith$resumingRules == gameRules && worldsmith$resumingNewWorld == newWorld) return;
        ci.cancel();
        Minecraft client = (Minecraft) (Object) this;
        WorldContentStartupBarrier.start(client, storage, stem, newWorld, () -> {
            worldsmith$resumingStem = stem;
            worldsmith$resumingStorage = storage;
            worldsmith$resumingPacks = packs;
            worldsmith$resumingRules = gameRules;
            worldsmith$resumingNewWorld = newWorld;
            worldsmith$skipRepeatedDisconnect = true;
            try { client.doWorldLoad(storage, packs, stem, gameRules, newWorld); }
            finally {
                worldsmith$skipRepeatedDisconnect = false;
                worldsmith$resumingStem = null;
                worldsmith$resumingStorage = null;
                worldsmith$resumingPacks = null;
                worldsmith$resumingRules = null;
            }
        });
    }

    @Redirect(method = "doWorldLoad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;disconnectWithProgressScreen()V"))
    private void worldsmith$disconnectExactlyOnce(Minecraft client) {
        if (worldsmith$skipRepeatedDisconnect) { worldsmith$skipRepeatedDisconnect = false; return; }
        client.disconnectWithProgressScreen();
    }
}
