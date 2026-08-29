package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.CompiledBiome;
import com.wjz.worldsmith.worldgen.CompiledBiomes;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/**
 * English names for the generated biomes and the world preset.
 *
 * <p>Without these the F3 screen and {@code /locate biome} show raw identifiers,
 * which makes it impossible to tell at a glance whether the right biome
 * generated.
 */
public final class WorldsmithLangProvider extends FabricLanguageProvider {
	public WorldsmithLangProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
		builder.add(WorldsmithWorldPresets.WASTELAND.identifier().toLanguageKey("generator"), "Wasteland");

		for (CompiledBiome biome : CompiledBiomes.all()) {
			builder.add(biome.key().identifier().toLanguageKey("biome"), biome.definition().getDisplayName());
		}

		addSettingsScreen(builder);
	}

	/** Strings for the optional Cloth Config screen. */
	private static void addSettingsScreen(TranslationBuilder builder) {
		builder.add("worldsmith.config.title", "Worldsmith");
		builder.add("worldsmith.config.category.model", "Model");
		builder.add("worldsmith.config.category.credentials", "Credentials");

		builder.add("worldsmith.config.provider", "Provider");
		builder.add("worldsmith.config.provider.tooltip",
			"Which API to speak. OPENAI_COMPATIBLE covers Ollama, LM Studio, vLLM, DeepSeek and OpenRouter.");
		builder.add("worldsmith.config.provider.anthropic", "Anthropic (Claude)");
		builder.add("worldsmith.config.provider.openai", "OpenAI");
		builder.add("worldsmith.config.provider.openai_compatible", "OpenAI-compatible");
		builder.add("worldsmith.config.provider.aws_bedrock", "AWS Bedrock (Claude)");

		builder.add("worldsmith.config.model", "Model");
		builder.add("worldsmith.config.model.tooltip", "Leave blank to use the provider's default model.");

		builder.add("worldsmith.config.base_url", "Base URL");
		builder.add("worldsmith.config.base_url.tooltip",
			"Leave blank to use the provider's default endpoint. Set this to point at a local or proxied server.");

		builder.add("worldsmith.config.max_output_tokens", "Max output tokens");
		builder.add("worldsmith.config.max_output_tokens.tooltip",
			"An upper bound on one reply. A whole biome plan needs several thousand.");

		builder.add("worldsmith.config.timeout_seconds", "Request timeout");
		builder.add("worldsmith.config.timeout_seconds.tooltip", "Seconds to wait for one reply before giving up.");

		builder.add("worldsmith.config.api_key", "API key");
		builder.add("worldsmith.config.api_key.tooltip",
			"Stored in plain text in config/worldsmith.json. For AWS Bedrock this is the access key id.");
		builder.add("worldsmith.config.api_key.tooltip.env", "Leave blank to read %s from the environment instead.");

		builder.add("worldsmith.config.api_secret", "API secret");
		builder.add("worldsmith.config.api_secret.tooltip", "AWS Bedrock only: the secret access key.");

		builder.add("worldsmith.config.region", "AWS region");
		builder.add("worldsmith.config.region.tooltip", "AWS Bedrock only. Ignored by every other provider.");
	}
}
