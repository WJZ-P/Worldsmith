package com.wjz.worldsmith;

import com.wjz.worldsmith.worldgen.WorldsmithWorldgen;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Worldsmith implements ModInitializer {
	public static final String MOD_ID = "worldsmith";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		WorldsmithWorldgen.initialize();
		LOGGER.info("Worldsmith initialized for Minecraft 26.2");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
