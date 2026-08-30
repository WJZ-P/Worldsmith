package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public final class WorldsmithClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Worldsmith.LOGGER.info("Worldsmith client initialized");

		WorldsmithWorldCreationBridge.initialize();
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof CreateWorldScreen createWorldScreen) {
				WorldsmithWorldCreationBridge.onScreenOpened(createWorldScreen);
			}
		});

		// The MCP bridge is a local authoring tool, so it follows the client's
		// life rather than any world's. It stays off unless the settings say so.
		WorldsmithMcpService.apply(WorldsmithConfig.get().getMcp());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> WorldsmithMcpService.stop());
	}
}
