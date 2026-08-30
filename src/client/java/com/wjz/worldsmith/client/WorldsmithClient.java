package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class WorldsmithClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Worldsmith.LOGGER.info("Worldsmith client initialized");

		// The MCP bridge is a local authoring tool, so it follows the client's
		// life rather than any world's. It stays off unless the settings say so.
		WorldsmithMcpService.apply(WorldsmithConfig.get().getMcp());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> WorldsmithMcpService.stop());
	}
}
