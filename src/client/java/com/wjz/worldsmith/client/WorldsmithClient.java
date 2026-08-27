package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import net.fabricmc.api.ClientModInitializer;

public final class WorldsmithClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Worldsmith.LOGGER.info("Worldsmith client initialized");
	}
}
