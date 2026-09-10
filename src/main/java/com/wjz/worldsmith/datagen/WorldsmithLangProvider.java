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
		addQuestJournal(builder);
	}

	private static void addQuestJournal(TranslationBuilder builder) {
		builder.add("key.category.worldsmith.quests", "Worldsmith");
		builder.add("key.worldsmith.quest_journal", "Open quest journal");
		builder.add("worldsmith.quests.title", "World Quest Journal");
		builder.add("worldsmith.quests.local_only", "The quest journal is available in local Worldsmith integrated worlds.");
		builder.add("worldsmith.quests.channel_unavailable", "The quest service is not ready. Refresh in a moment.");
		builder.add("worldsmith.quests.world_changed", "World context changed. Reopen the journal for the current world.");
		builder.add("worldsmith.quests.loading", "Loading the server quest journal...");
		builder.add("worldsmith.quests.processing", "Waiting for the server to confirm this action...");
		builder.add("worldsmith.quests.timeout", "No server response yet. Refresh to confirm the saved state before retrying.");
		builder.add("worldsmith.quests.server_authority", "Progress and rewards are confirmed by the server. The world keeps running while this journal is open.");
		builder.add("worldsmith.quests.previous", "Previous quest page");
		builder.add("worldsmith.quests.next", "Next quest page");
		builder.add("worldsmith.quests.deliver", "Deliver items");
		builder.add("worldsmith.quests.claim", "Claim rewards");
		builder.add("worldsmith.quests.delivery_hint", "Delivery consumes matching items from your main inventory. Partial deliveries count; merely holding items does not.");
		builder.add("worldsmith.quests.claim_hint", "Complete every objective to claim once. If inventory space is insufficient, rewards stay unclaimed and are not dropped.");
		builder.add("worldsmith.quests.refresh", "Refresh");
		builder.add("worldsmith.quests.small_window", "Reduce GUI scale or enlarge the window to view the quest journal.");
		builder.add("worldsmith.quests.empty", "This world has no available main-line quests.");
		builder.add("worldsmith.quests.locked_hint", "Claim the prerequisite quest's rewards to unlock this quest.");
		builder.add("worldsmith.quests.objectives", "Objectives");
		builder.add("worldsmith.quests.rewards", "Rewards");
		builder.add("worldsmith.quests.no_rewards", "No item rewards.");
		builder.add("worldsmith.quests.reward", "%s × %s");
		builder.add("worldsmith.quests.objective.kill_creature", "Defeat %s: %s / %s");
		builder.add("worldsmith.quests.objective.deliver_item", "Delivered %s: %s / %s");
		builder.add("worldsmith.quests.status.locked", "Locked");
		builder.add("worldsmith.quests.status.active", "Active");
		builder.add("worldsmith.quests.status.ready", "Ready");
		builder.add("worldsmith.quests.status.claimed", "Claimed");
		builder.add("worldsmith.quests.feedback.none", "Journal refreshed.");
		builder.add("worldsmith.quests.feedback.delivered", "Items delivered; progress updated.");
		builder.add("worldsmith.quests.feedback.claimed", "Rewards claimed.");
		builder.add("worldsmith.quests.feedback.no_materials", "No matching items to deliver. Inventory and progress are unchanged.");
		builder.add("worldsmith.quests.feedback.no_space", "Not enough inventory space. Rewards remain unclaimed.");
		builder.add("worldsmith.quests.feedback.locked", "This quest is not unlocked yet.");
		builder.add("worldsmith.quests.feedback.not_ready", "Complete every objective before claiming rewards.");
		builder.add("worldsmith.quests.feedback.already_claimed", "These rewards have already been claimed.");
		builder.add("worldsmith.quests.feedback.stale_revision", "Progress changed since the last view. Review the refreshed state before acting again.");
		builder.add("worldsmith.quests.feedback.scope_mismatch", "This request belongs to a different world. Reopen the current journal.");
		builder.add("worldsmith.quests.feedback.unavailable", "No quest journal is available in this world.");
		builder.add("worldsmith.quests.feedback.error", "The server rejected this action. See its message and refresh before retrying.");
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
