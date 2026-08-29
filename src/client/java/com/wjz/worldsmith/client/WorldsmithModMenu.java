package com.wjz.worldsmith.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Hands Mod Menu the settings screen.
 *
 * Mod Menu is the only thing that loads this class, and the screen class is
 * only touched by the returned factory, so a player with neither optional mod
 * installed never resolves either dependency. Having Mod Menu without Cloth
 * Config is the one combination that would break, so it is checked and Mod
 * Menu's own null-screen factory is returned instead.
 */
public final class WorldsmithModMenu implements ModMenuApi {
	private static final String CLOTH_CONFIG = "cloth-config";

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded(CLOTH_CONFIG)) {
			return ModMenuApi.super.getModConfigScreenFactory();
		}
		return WorldsmithConfigScreen::create;
	}
}
