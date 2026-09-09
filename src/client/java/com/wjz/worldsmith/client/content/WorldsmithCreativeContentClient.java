package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.creative.WorldsmithCreativeContent;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** The menu reads the joined world's verified snapshot, not a preview lease left in the title screen. */
public final class WorldsmithCreativeContentClient {
    private static boolean initialized;
    private static String failedScope;
    private static long failedProviderRevision = -1;
    private WorldsmithCreativeContentClient() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(WorldsmithCreativeContentClient::synchronize);
    }

    /** Also called immediately before vanilla checks its creative-tab cache, so the first open is fresh. */
    public static void synchronize(Minecraft client) {
        if (client == null) { WorldsmithCreativeContent.clear(); return; }
        var blocks = WorldBlockBindings.active();
        var creatures = CreatureRuntime.clientSnapshot();
        String scope = WorldContentClientRuntime.activeScope();
        if (client.level == null || client.player == null || !client.isLocalServer() || blocks == null || creatures == null
            || scope == null || !scope.equals(blocks.getScope()) || !scope.equals(creatures.bundleHash())) {
            WorldsmithCreativeContent.clear();
            failedScope = null;
            return;
        }
        long providers = WorldsmithCreativeContent.providerRevision();
        if (scope.equals(failedScope) && providers == failedProviderRevision) return;
        try {
            WorldsmithCreativeContent.publish(blocks, creatures);
            failedScope = null;
        } catch (RuntimeException failure) {
            WorldsmithCreativeContent.clear();
            failedScope = scope; failedProviderRevision = providers;
            Worldsmith.LOGGER.error("Current-world creative catalog failed to build; unbound content stays hidden", failure);
        }
    }
}
