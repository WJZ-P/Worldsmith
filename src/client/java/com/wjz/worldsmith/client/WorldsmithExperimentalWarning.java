package com.wjz.worldsmith.client;

import com.mojang.serialization.Lifecycle;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.WorldStem;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Omits only the generic experimental-world prompt, without rewriting registry/save lifecycles. */
public final class WorldsmithExperimentalWarning {
    private WorldsmithExperimentalWarning() {}

    public static boolean skipCreation(CreateWorldScreen screen, Lifecycle lifecycle) {
        var settings = ((CreateWorldScreenAccessor) screen).worldsmith$getUiState().getSettings();
        return eligible(lifecycle, FeatureFlags.isExperimental(settings.dataConfiguration().enabledFeatures()),
                settings.options().isOldCustomizedWorld())
            && WorldsmithWorldCreationBridge.isPreparedWorldsmithCreation(screen);
    }

    public static boolean skipSavedWorld(WorldStem stem) {
        var world = stem.worldDataAndGenSettings();
        if (!eligible(world.data().worldGenSettingsLifecycle(), FeatureFlags.isExperimental(world.data().enabledFeatures()),
            world.genSettings().options().isOldCustomizedWorld())) return false;
        if (!(world.genSettings().dimensions().overworld() instanceof NoiseBasedChunkGenerator generator)) return false;
        var key = generator.generatorSettings().unwrapKey().orElse(null);
        if (key == null || !Worldsmith.MOD_ID.equals(key.identifier().getNamespace())) return false;
        String path = key.identifier().getPath();
        if (!path.matches("generated/[0-9a-f]{64}/wasteland")) return false;
        String scope = path.substring("generated/".length(), "generated/".length() + 64);
        try {
            // Selected resources only: validates the immutable bundle and bindings, without activation or save writes.
            return WorldContentRuntime.loadSelected(stem.resourceManager())
                .map(embedded -> scope.equals(embedded.pack().getManifest().getId())).orElse(false);
        } catch (RuntimeException failure) {
            Worldsmith.LOGGER.warn("Retaining experimental confirmation: selected world bundle was not verified", failure);
            return false;
        }
    }

    static boolean eligible(Lifecycle lifecycle, boolean experimentalFeatures, boolean oldCustomized) {
        return lifecycle == Lifecycle.experimental() && !experimentalFeatures && !oldCustomized;
    }
}
