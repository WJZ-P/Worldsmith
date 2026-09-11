package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

public final class WorldsmithClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		com.wjz.worldsmith.client.content.WorldContentClientLifecycle.initialize();
		com.wjz.worldsmith.client.content.WorldsmithCreativeContentClient.initialize();
		com.wjz.worldsmith.client.quest.QuestJournalClient.initialize();
		Worldsmith.LOGGER.info("Worldsmith client initialized");

        WorldsmithWorldCreationBridge.initialize();
        WorldsmithMcpService.setSourceApprovalListener(WorldsmithSourceApproval::request);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof CreateWorldScreen createWorldScreen) {
				WorldsmithWorldCreationBridge.onScreenOpened(createWorldScreen);
				WorldsmithGenerationProgressTab.attachTo(createWorldScreen);
			}
			if (screen instanceof TitleScreen || screen instanceof SelectWorldScreen) {
				var buttons = Screens.getWidgets(screen);
				var label = Component.translatable("worldsmith.packs.open");
				// AFTER_INIT also runs after resize; vanilla may only reposition existing widgets.
				buttons.removeIf(button -> button.getMessage().equals(label));
				buttons.add(Button.builder(label,
					button -> client.gui.setScreen(new WorldsmithResourcePackScreen(screen)))
					.bounds(Math.max(4, width - 104), 3, 100, 20)
					.tooltip(Tooltip.create(Component.translatable("worldsmith.packs.open.hint"))).build());
			}
		});

		// The MCP bridge is a local authoring tool, so it follows the client's
		// life rather than any world's. It stays off unless the settings say so.
		WorldsmithMcpService.apply(WorldsmithConfig.get().getMcp());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> WorldsmithMcpService.stop());
	}
}
