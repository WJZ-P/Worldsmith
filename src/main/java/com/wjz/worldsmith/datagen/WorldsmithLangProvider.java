package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.CompiledBiome;
import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import com.wjz.worldsmith.core.content.CustomBlockProfile;
import com.wjz.worldsmith.core.content.CustomBlockValidation;
import java.util.Locale;
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

		for (CompiledBiome biome : WorldsmithPacks.builtinCompiled().biomes()) {
			builder.add(biome.key().identifier().toLanguageKey("biome"), biome.definition().getDisplayName());
		}

		addSettingsScreen(builder);
		addUnboundBlockHosts(builder);
		addCreativeContent(builder);
	}

	private static void addCreativeContent(TranslationBuilder builder) {
		builder.add("item.worldsmith.content.item.host", "[Worldsmith] Unbound World Item");
		builder.add("itemGroup.worldsmith.world_content", "Worldsmith: Current World");
		builder.add("item.worldsmith.creature_summoner", "Creature Summoner");
		builder.add("item.worldsmith.creature_summoner.named", "Summon %s");
		builder.add("item.worldsmith.creature_summoner.unavailable", "Creature Summoner (Different World or Missing Species)");
		builder.add("worldsmith.creative.creative_only", "Worldsmith creature summoners require Creative mode.");
		builder.add("worldsmith.creative.missing_species", "This summoner does not reference a creature defined in this world.");
		builder.add("worldsmith.creative.wrong_world", "This summoner belongs to a different world. Take a fresh one from this world's Worldsmith tab.");
		builder.add("worldsmith.creative.blocked_spawn", "This creature needs a clear, permitted position with enough room for its body.");
		builder.add("worldsmith.creative.spawn_unavailable", "This creature is unavailable under the current world settings or difficulty.");
	}

	/** Diagnostic fallbacks remain named before a world-scoped resource pack is activated. */
	private static void addUnboundBlockHosts(TranslationBuilder builder) {
		for (CustomBlockProfile profile : CustomBlockProfile.values()) {
			String name = profile.name().toLowerCase(Locale.ROOT);
			for (int slot = 0; slot < CustomBlockValidation.SLOTS_PER_PROFILE; slot++) {
				String index = String.format(Locale.ROOT, "%02d", slot);
				builder.add("block.worldsmith.content.block." + name + "." + index,
					"[Worldsmith] Unbound block slot (" + name + " " + index + ")");
			}
		}
	}

	/** Strings for the optional Cloth Config screen. */
    private static void addSettingsScreen(TranslationBuilder builder) {
        builder.add("worldsmith.draw.approval.title", "Allow AI Java authoring for this world?");
        builder.add("worldsmith.draw.approval.body", "Session %s will run AI-authored Java in a separate process under your account. Time and memory are limited, but this is not a filesystem/network sandbox. Approve only a trusted AI client. Approval ends when the client restarts.");
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

		builder.add("worldsmith.config.category.mcp", "MCP Bridge");
		builder.add("worldsmith.config.mcp.about",
			"Lets an external assistant read and write Worldsmith packs while the game is running. "
				+ "It listens on this machine only and is never reachable from the network.");
		builder.add("worldsmith.config.mcp.enabled", "Enable bridge");
		builder.add("worldsmith.config.mcp.enabled.tooltip",
			"Off by default. Turning this on opens a local port for as long as the game is running.");
		builder.add("worldsmith.config.mcp.port", "Port");
		builder.add("worldsmith.config.mcp.port.tooltip",
			"The port to prefer. If it is busy the bridge takes the next free one, so the address below is what actually bound. The bridge restarts when you save.");
		builder.add("worldsmith.config.mcp.autoApprove", "Run AI code without asking");
		builder.add("worldsmith.config.mcp.autoApprove.tooltip",
			"On by default: a connected assistant may compile and run its own Java for this world without a confirmation. "
				+ "The worker limits time and memory, but it is not a filesystem or network sandbox and the code runs under your account. "
				+ "Turn this off to be asked once per authoring session.");
		builder.add("worldsmith.config.mcp.endpoint", "Address: %s");
		builder.add("worldsmith.config.mcp.discovery",
			"Running bridges announce themselves in %s, so an agent can read the address from there "
				+ "instead of being told it. The file disappears when the bridge stops.");
	}
}
