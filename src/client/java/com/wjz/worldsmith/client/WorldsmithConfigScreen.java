package com.wjz.worldsmith.client;

import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.core.ai.LlmProvider;
import com.wjz.worldsmith.core.ai.LlmSettings;
import com.wjz.worldsmith.core.mcp.McpHttpServer;
import com.wjz.worldsmith.core.settings.McpSettings;
import com.wjz.worldsmith.core.settings.WorldsmithSettings;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen, built with Cloth Config.
 *
 * This class is only ever loaded through {@link WorldsmithModMenu}, which
 * checks that Cloth Config is present first, so nothing here runs on an
 * installation that does not have it.
 *
 * Edits are collected into a {@link Draft} and only written when Cloth reports
 * the screen was saved, so backing out of the screen changes nothing on disk.
 */
public final class WorldsmithConfigScreen {
	private WorldsmithConfigScreen() {
	}

	public static Screen create(Screen parent) {
		Draft draft = Draft.of(WorldsmithConfig.get());

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("worldsmith.config.title"))
			.setSavingRunnable(() -> save(draft));

		ConfigEntryBuilder entries = builder.entryBuilder();
		addModelCategory(builder, entries, draft);
		addCredentialCategory(builder, entries, draft);
		addBridgeCategory(builder, entries, draft);
		return builder.build();
	}

	/**
	 * Writes the settings, then brings the bridge in line with them.
	 *
	 * The bridge is applied from the stored settings rather than the draft so it
	 * sees the clamped port, not whatever was typed.
	 */
	private static void save(Draft draft) {
		WorldsmithConfig.set(new WorldsmithSettings(
			WorldsmithSettings.SCHEMA_VERSION,
			draft.toLlmSettings(),
			draft.toMcpSettings()
		));
		WorldsmithMcpService.apply(WorldsmithConfig.get().getMcp());
	}

	private static void addModelCategory(ConfigBuilder builder, ConfigEntryBuilder entries, Draft draft) {
		ConfigCategory category = builder.getOrCreateCategory(Component.translatable("worldsmith.config.category.model"));

		category.addEntry(entries
			.startEnumSelector(Component.translatable("worldsmith.config.provider"), LlmProvider.class, draft.provider)
			.setDefaultValue(LlmProvider.ANTHROPIC)
			.setEnumNameProvider(value -> Component.translatable(providerTranslationKey((LlmProvider) value)))
			.setTooltip(Component.translatable("worldsmith.config.provider.tooltip"))
			.setSaveConsumer(value -> draft.provider = value)
			.build());

		category.addEntry(entries
			.startStrField(Component.translatable("worldsmith.config.model"), draft.model)
			.setDefaultValue("")
			.setTooltip(Component.translatable("worldsmith.config.model.tooltip"))
			.setSaveConsumer(value -> draft.model = value)
			.build());

		category.addEntry(entries
			.startStrField(Component.translatable("worldsmith.config.base_url"), draft.baseUrl)
			.setDefaultValue("")
			.setTooltip(Component.translatable("worldsmith.config.base_url.tooltip"))
			.setSaveConsumer(value -> draft.baseUrl = value)
			.build());

		category.addEntry(entries
			.startIntField(Component.translatable("worldsmith.config.max_output_tokens"), draft.maxOutputTokens)
			.setDefaultValue(new LlmSettings().getMaxOutputTokens())
			.setMin(LlmSettings.MIN_OUTPUT_TOKENS)
			.setMax(LlmSettings.MAX_OUTPUT_TOKENS)
			.setTooltip(Component.translatable("worldsmith.config.max_output_tokens.tooltip"))
			.setSaveConsumer(value -> draft.maxOutputTokens = value)
			.build());

		category.addEntry(entries
			.startIntField(Component.translatable("worldsmith.config.timeout_seconds"), draft.timeoutSeconds)
			.setDefaultValue(new LlmSettings().getTimeoutSeconds())
			.setMin(LlmSettings.MIN_TIMEOUT_SECONDS)
			.setMax(LlmSettings.MAX_TIMEOUT_SECONDS)
			.setTooltip(Component.translatable("worldsmith.config.timeout_seconds.tooltip"))
			.setSaveConsumer(value -> draft.timeoutSeconds = value)
			.build());
	}

	private static void addCredentialCategory(ConfigBuilder builder, ConfigEntryBuilder entries, Draft draft) {
		ConfigCategory category =
			builder.getOrCreateCategory(Component.translatable("worldsmith.config.category.credentials"));

		category.addEntry(entries
			.startStrField(Component.translatable("worldsmith.config.api_key"), draft.apiKey)
			.setDefaultValue("")
			.setTooltip(
				Component.translatable("worldsmith.config.api_key.tooltip"),
				Component.translatable("worldsmith.config.api_key.tooltip.env", LlmSettings.API_KEY_ENV)
			)
			.setSaveConsumer(value -> draft.apiKey = value)
			.build());

		category.addEntry(entries
			.startStrField(Component.translatable("worldsmith.config.api_secret"), draft.apiSecret)
			.setDefaultValue("")
			.setTooltip(
				Component.translatable("worldsmith.config.api_secret.tooltip"),
				Component.translatable("worldsmith.config.api_key.tooltip.env", LlmSettings.API_SECRET_ENV)
			)
			.setSaveConsumer(value -> draft.apiSecret = value)
			.build());

		category.addEntry(entries
			.startStrField(Component.translatable("worldsmith.config.region"), draft.region)
			.setDefaultValue(new LlmSettings().getRegion())
			.setTooltip(Component.translatable("worldsmith.config.region.tooltip"))
			.setSaveConsumer(value -> draft.region = value)
			.build());
	}

	/**
	 * The local MCP bridge.
	 *
	 * Off by default, and the description says plainly what turning it on does,
	 * because opening a port is not something a settings screen should do
	 * quietly.
	 */
	private static void addBridgeCategory(ConfigBuilder builder, ConfigEntryBuilder entries, Draft draft) {
		ConfigCategory category = builder.getOrCreateCategory(Component.translatable("worldsmith.config.category.mcp"));

		category.addEntry(entries
			.startTextDescription(Component.translatable("worldsmith.config.mcp.about"))
			.build());

		category.addEntry(entries
			.startBooleanToggle(Component.translatable("worldsmith.config.mcp.enabled"), draft.mcpEnabled)
			.setDefaultValue(new McpSettings().getEnabled())
			.setTooltip(Component.translatable("worldsmith.config.mcp.enabled.tooltip"))
			.setSaveConsumer(value -> draft.mcpEnabled = value)
			.build());

		category.addEntry(entries
			.startIntField(Component.translatable("worldsmith.config.mcp.port"), draft.mcpPort)
			.setDefaultValue(McpSettings.DEFAULT_PORT)
			.setMin(McpSettings.MIN_PORT)
			.setMax(McpSettings.MAX_PORT)
			.setTooltip(Component.translatable("worldsmith.config.mcp.port.tooltip"))
			.setSaveConsumer(value -> draft.mcpPort = value)
			.build());

		category.addEntry(entries
			.startTextDescription(Component.translatable("worldsmith.config.mcp.endpoint", endpointLabel(draft)))
			.build());

		category.addEntry(entries
			.startTextDescription(Component.translatable(
				"worldsmith.config.mcp.discovery",
				WorldsmithMcpService.discoveryFile().toString()
			))
			.build());
	}

	/** The live address when the bridge is up, otherwise the one it would take. */
	private static String endpointLabel(Draft draft) {
		java.net.URI endpoint = WorldsmithMcpService.endpoint();
		if (endpoint != null) {
			return endpoint.toString();
		}
		return "http://" + McpHttpServer.LOOPBACK_HOST + ":" + draft.mcpPort + McpHttpServer.MCP_PATH;
	}

	private static String providerTranslationKey(LlmProvider provider) {
		return switch (provider) {
			case ANTHROPIC -> "worldsmith.config.provider.anthropic";
			case OPENAI -> "worldsmith.config.provider.openai";
			case OPENAI_COMPATIBLE -> "worldsmith.config.provider.openai_compatible";
			case AWS_BEDROCK -> "worldsmith.config.provider.aws_bedrock";
		};
	}

	/** Mutable while the screen is open, because Cloth reports each field separately. */
	private static final class Draft {
		private LlmProvider provider;
		private String baseUrl;
		private String apiKey;
		private String apiSecret;
		private String region;
		private String model;
		private int maxOutputTokens;
		private int timeoutSeconds;
		private boolean mcpEnabled;
		private int mcpPort;

		private static Draft of(WorldsmithSettings settings) {
			LlmSettings llm = settings.getLlm();
			McpSettings mcp = settings.getMcp();
			Draft draft = new Draft();
			draft.provider = llm.getProvider();
			draft.baseUrl = llm.getBaseUrl();
			draft.apiKey = llm.getApiKey();
			draft.apiSecret = llm.getApiSecret();
			draft.region = llm.getRegion();
			draft.model = llm.getModel();
			draft.maxOutputTokens = llm.getMaxOutputTokens();
			draft.timeoutSeconds = llm.getTimeoutSeconds();
			draft.mcpEnabled = mcp.getEnabled();
			draft.mcpPort = mcp.getPort();
			return draft;
		}

		private LlmSettings toLlmSettings() {
			return new LlmSettings(
				provider, baseUrl, apiKey, apiSecret, region, model, maxOutputTokens, timeoutSeconds
			);
		}

		private McpSettings toMcpSettings() {
			return new McpSettings(mcpEnabled, mcpPort);
		}
	}
}
