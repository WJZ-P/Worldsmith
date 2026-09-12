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
		addResourcePackLibrary(builder);
		addGenerationProgress(builder);
	}

	private static void addGenerationProgress(TranslationBuilder builder) {
		builder.add("worldsmith.progress.kind.anchor", "Landmarks");
		builder.add("worldsmith.progress.kind.blueprint", "Blueprints");
		builder.add("worldsmith.progress.world_waiting", "A world waiting to take shape");
		builder.add("worldsmith.progress.world_previous", "Previous world");
		builder.add("worldsmith.progress.world_next", "Next world");
		builder.add("worldsmith.progress.world_picker_hint", "Choose a world to follow");
		builder.add("worldsmith.progress.overview_waiting", "World blueprint");
		builder.add("worldsmith.progress.overview_count", "World content %s / %s");
		builder.add("worldsmith.progress.phase.waiting", "Waiting to begin");
		builder.add("worldsmith.progress.phase.planning", "Planning");
		builder.add("worldsmith.progress.phase.creating", "Taking shape");
		builder.add("worldsmith.progress.phase.refining", "Needs refinement");
		builder.add("worldsmith.progress.phase.saved", "Content saved");
		builder.add("worldsmith.progress.phase.finishing", "Ready to finalize");
		builder.add("worldsmith.progress.phase.confirm", "Needs confirmation");
		builder.add("worldsmith.progress.phase.ready", "Ready to create");
		builder.add("worldsmith.progress.phase.select_world", "Choose this world");
		builder.add("worldsmith.progress.phase.loading_world", "Preparing the world");
		builder.add("worldsmith.progress.card.prompt", "Prompt");
		builder.add("worldsmith.progress.card.world_prompt", "World prompt");
		builder.add("worldsmith.progress.card.no_prompt", "The design for this part will appear as the world takes shape.");
		builder.add("worldsmith.progress.card.generating", "Generating: %s");
		builder.add("worldsmith.progress.card.generated", "Created: %s");
		builder.add("worldsmith.progress.card.saved", "Saved: %s");
		builder.add("worldsmith.progress.card.pending", "Up next: %s");
		builder.add("worldsmith.progress.card.repair", "Needs refinement: %s");
		builder.add("worldsmith.progress.card.needs_asset", "Needs textures: %s");
		builder.add("worldsmith.progress.card.confirm", "Confirm to continue: %s");
		builder.add("worldsmith.progress.card.queued", "Queued: %s");
		builder.add("worldsmith.progress.card.existing", "%s entries available");
		builder.add("worldsmith.progress.card.waiting", "Waiting for its design");
		builder.add("worldsmith.progress.card.waiting_plan", "Awaiting a target count");
		builder.add("worldsmith.progress.card.not_planned", "Not planned for this world");
		builder.add("worldsmith.progress.card.written", "%s entries created");
		builder.add("worldsmith.progress.card.remaining", "%s entries remaining");
		builder.add("worldsmith.progress.feed_delayed", "No new progress has arrived yet.");
		builder.add("worldsmith.progress.attention_needed", "Some content needs a little refinement before continuing.");
		builder.add("worldsmith.progress.tab", "Worldsmith");
		builder.add("worldsmith.progress.auto", "Auto");
		builder.add("worldsmith.progress.auto.hint", "Follow a world session explicitly started, resumed or edited on this bridge. No historical session is guessed after restart.");
		builder.add("worldsmith.progress.session_previous", "Select the previous session manually");
		builder.add("worldsmith.progress.session_next", "Select the next session manually");
		builder.add("worldsmith.progress.choose_session", "Choose a session");
		builder.add("worldsmith.progress.session_unavailable", "Selected session unavailable");
		builder.add("worldsmith.progress.session_hint", "Use the arrows or click the name to select a session. Manual selection stays on that world.");
		builder.add("worldsmith.progress.following_auto", "AUTO · Following explicit authoring activity");
		builder.add("worldsmith.progress.following_manual", "PINNED · Showing your selected world session");
		builder.add("worldsmith.progress.pause", "Pause view");
		builder.add("worldsmith.progress.pause.hint", "Pause this panel only. The AI and drawing jobs keep running.");
		builder.add("worldsmith.progress.resume", "Resume view");
		builder.add("worldsmith.progress.resume.hint", "Resume cached progress updates for this panel.");
		builder.add("worldsmith.progress.paused", "VIEW PAUSED · The AI may still be working");
		builder.add("worldsmith.progress.category", "Targets");
		builder.add("worldsmith.progress.category.hint", "Cycle target categories. Counts and progress remain based on the complete plan.");
		builder.add("worldsmith.progress.category_value", "Targets: %s");
		builder.add("worldsmith.progress.targets_previous", "Previous target page");
		builder.add("worldsmith.progress.targets_next", "Next target page");
		builder.add("worldsmith.progress.disconnected", "BRIDGE OFFLINE · No fresh authoring data");
		builder.add("worldsmith.progress.refreshing", "SYNCING · Reading bounded progress metadata");
		builder.add("worldsmith.progress.empty_title", "Your world plan will appear here");
		builder.add("worldsmith.progress.empty_auto", "Ask your AI to begin or resume a Worldsmith world session, or select one with the session picker. No old world is selected automatically just because it exists.");
		builder.add("worldsmith.progress.empty_manual", "The chosen session has no live view on this bridge. Resume that session or explicitly choose another; the panel will not silently switch worlds.");
		builder.add("worldsmith.progress.not_an_ai_runner", "This tab observes existing AI work. It does not start an AI request, execute source code or create a world.");
		builder.add("worldsmith.progress.session_line", "Session %s · revision %s · %s");
		builder.add("worldsmith.progress.running_tool", "Running: %s");
		builder.add("worldsmith.progress.tool_failed", "Last tool needs attention: %s");
		builder.add("worldsmith.progress.draft_progress", "Submitted target drafts %s / %s");
		builder.add("worldsmith.progress.no_plan_progress", "Plan target total not yet established");
		builder.add("worldsmith.progress.progress_boundary", "Draft coverage, not a gameplay-completion percentage. Extra definitions do not fill missing plan targets.");
		builder.add("worldsmith.progress.no_plan_boundary", "Without an exact plan denominator, no completion percentage is inferred. Cards show submitted definitions.");
		builder.add("worldsmith.progress.native_line", "THIS CREATE WORLD PAGE · %s");
		builder.add("worldsmith.progress.native_boundary", "Native activation is checked separately for this screen and selected bundle. Historical receipts are not current activation.");
		builder.add("worldsmith.progress.targets_heading", "Plan targets · %s");
		builder.add("worldsmith.progress.no_targets", "No planned targets in this category.");
		builder.add("worldsmith.progress.targets_need_plan", "Submit a named world plan to see individual targets and draft coverage.");
		builder.add("worldsmith.progress.targets_truncated", "The target list is bounded. An oversized plan does not receive a fabricated completion denominator.");
		builder.add("worldsmith.progress.jobs_heading", "Drawing workers · %s recent jobs");
		builder.add("worldsmith.progress.no_jobs", "No drawing jobs are recorded for this session.");
		builder.add("worldsmith.progress.approval_hint", "Waiting for your source-execution approval in the host prompt; this panel does not grant approval.");
		builder.add("worldsmith.progress.jobs_more", "Showing up to 6 jobs, active work first. The cached snapshot contains more job details.");
		builder.add("worldsmith.progress.next_heading", "Next step / attention needed");
		builder.add("worldsmith.progress.issues_more", "More diagnostics are available in the generation-progress tool response.");
		builder.add("worldsmith.progress.verification_boundary", "Declared and frozen states describe saved draft records. They do not prove a fresh geometry/texture check, a ready resource pack or in-game completion.");
		builder.add("worldsmith.progress.card_declared", "Declared: %s");
		builder.add("worldsmith.progress.card_progress", "%s / %s drafts");
		builder.add("worldsmith.progress.card_hint", "Counts are declared plan targets / planned targets. %s extra definitions are not counted as planned progress.");
		builder.add("worldsmith.progress.scroll_hint", "Scroll through content categories and their prompts.");
		builder.add("worldsmith.progress.kind.all", "All categories");
		builder.add("worldsmith.progress.kind.biome", "Biomes");
		builder.add("worldsmith.progress.kind.structure", "Structures");
		builder.add("worldsmith.progress.kind.creature", "Creatures");
		builder.add("worldsmith.progress.kind.block", "Blocks");
		builder.add("worldsmith.progress.kind.item", "Items");
		builder.add("worldsmith.progress.kind.quest", "Quests");
		builder.add("worldsmith.progress.kind.feature", "Features");
		builder.add("worldsmith.progress.kind.theme", "Theme");
		builder.add("worldsmith.progress.kind.terrain", "Terrain");
		builder.add("worldsmith.progress.kind.narrative_beat", "Story beats");
		builder.add("worldsmith.progress.target.declared", "Draft submitted");
		builder.add("worldsmith.progress.target.missing", "Not submitted");
		builder.add("worldsmith.progress.target.needs_asset", "Needs texture");
		builder.add("worldsmith.progress.target.repair", "Needs repair");
		builder.add("worldsmith.progress.target.frozen", "Freeze recorded");
		builder.add("worldsmith.progress.stage.UNKNOWN", "No stage yet");
		builder.add("worldsmith.progress.stage.ARCHIVED", "Archived session");
		builder.add("worldsmith.progress.stage.WAITING_USER", "Waiting for you");
		builder.add("worldsmith.progress.stage.DESIGN_PLAN", "Planning the world");
		builder.add("worldsmith.progress.stage.FROZEN_REPAIR", "Repairing pack validation");
		builder.add("worldsmith.progress.stage.AUTHORING", "Authoring content");
		builder.add("worldsmith.progress.stage.STANDALONE_ARTIFACT", "Standalone artifact");
		builder.add("worldsmith.progress.stage.NATIVE_COMPLETE", "Historical native receipt");
		builder.add("worldsmith.progress.stage.CORE_SAVED", "Core bundle saved");
		builder.add("worldsmith.progress.stage.READY_FOR_FROZEN_CHECK", "Ready for pack validation");
		builder.add("worldsmith.progress.stage.RUNNING_TOOL", "Tool call running");
		builder.add("worldsmith.progress.stage.HISTORICAL_NATIVE_RECEIPT", "Historical native receipt");
		builder.add("worldsmith.progress.stage.DRAFT", "Draft");
		builder.add("worldsmith.progress.stage.WAITING_APPROVAL", "Waiting for approval");
		builder.add("worldsmith.progress.stage.QUEUED", "Queued");
		builder.add("worldsmith.progress.stage.COMPILING", "Compiling source");
		builder.add("worldsmith.progress.stage.DRAWING", "Drawing geometry");
		builder.add("worldsmith.progress.stage.VALIDATING", "Validating drawing");
		builder.add("worldsmith.progress.stage.SUCCEEDED", "Build succeeded");
		builder.add("worldsmith.progress.stage.FAILED", "Failed");
		builder.add("worldsmith.progress.stage.CANCELLED", "Cancelled");
		builder.add("worldsmith.progress.stage.INTERRUPTED", "Interrupted");
		builder.add("worldsmith.progress.stage.PUBLISHED", "Native activation verified");
		builder.add("worldsmith.progress.stage.WAITING_NATIVE_CONTEXT", "Waiting for native context");
		builder.add("worldsmith.progress.stage.NATIVE_CHECK", "Native validation");
		builder.add("worldsmith.progress.stage.RELOADING", "Reloading world data");
		builder.add("worldsmith.progress.stage.CLIENT_RESOURCES", "Reloading client assets");
		builder.add("worldsmith.progress.stage.NOT_SELECTED", "No active bundle for this page");
	}

	private static void addResourcePackLibrary(TranslationBuilder builder) {
		builder.add("worldsmith.packs.directory", "World-pack folder");
		builder.add("worldsmith.packs.directory.hint", "Add .wspack files here; the list updates automatically.");
		builder.add("worldsmith.packs.select_pack", "Select a world pack");
		builder.add("worldsmith.packs.empty_details", "Place a .wspack in the world-pack folder to see it in the list.");
		builder.add("worldsmith.packs.no_description", "No description.");
		builder.add("worldsmith.packs.contents", "World contents");
		builder.add("worldsmith.packs.loading_details", "Reading world configuration...");
		builder.add("worldsmith.packs.unreadable", "%s world packs could not be read yet.");
		builder.add("worldsmith.packs.count.biomes", "Biomes");
		builder.add("worldsmith.packs.count.structures", "Structures");
		builder.add("worldsmith.packs.count.features", "Features");
		builder.add("worldsmith.packs.count.blocks", "Blocks");
		builder.add("worldsmith.packs.count.creatures", "Creatures");
		builder.add("worldsmith.packs.count.items", "Items");
		builder.add("worldsmith.packs.count.quests", "Quests");
		builder.add("worldsmith.packs.count.pngAssets", "Textures");
		builder.add("worldsmith.packs.open", "World packs");
		builder.add("worldsmith.packs.open.hint", "Browse world packs and choose one for a new world.");
		builder.add("worldsmith.packs.title", "Worldsmith Resource Packs");
		builder.add("worldsmith.packs.version", ".wspack ZIP v1 | Minecraft %s | Native activation is separate");
		builder.add("worldsmith.packs.library", "Library");
		builder.add("worldsmith.packs.inbox", "Import inbox");
		builder.add("worldsmith.packs.refresh", "Refresh");
		builder.add("worldsmith.packs.refresh.hint", "Refresh the managed library and the import-folder file list.");
		builder.add("worldsmith.packs.previous", "Previous page");
		builder.add("worldsmith.packs.next", "Next page");
		builder.add("worldsmith.packs.inspect", "Inspect");
		builder.add("worldsmith.packs.inspect.hint", "Validate the selected .wspack and show its identity before importing. No source code is executed.");
		builder.add("worldsmith.packs.import", "Import");
		builder.add("worldsmith.packs.import.hint", "Validate and copy this archive into the library. Import does not select a world preset or change the current world.");
		builder.add("worldsmith.packs.export", "Export .wspack");
		builder.add("worldsmith.packs.export.hint", "Export the selected library bundle as one reusable .wspack in the export folder.");
		builder.add("worldsmith.packs.create", "Use for new world");
		builder.add("worldsmith.packs.create.hint", "Begin a new adventure in this world.");
		builder.add("worldsmith.packs.copy_path", "Copy path");
		builder.add("worldsmith.packs.copy_path.hint", "Copy the complete result or selected package path to the clipboard.");
		builder.add("worldsmith.packs.path_copied", "Path copied.");
		builder.add("worldsmith.packs.open_inbox", "Import folder");
		builder.add("worldsmith.packs.open_inbox.hint", "Open the fixed import folder. Put a .wspack file here, then refresh and select it.");
		builder.add("worldsmith.packs.open_exports", "Export folder");
		builder.add("worldsmith.packs.open_exports.hint", "Open the fixed folder containing exported .wspack files.");
		builder.add("worldsmith.packs.loading", "Reading world packs...");
		builder.add("worldsmith.packs.checking", "Validating archive integrity and content...");
		builder.add("worldsmith.packs.importing", "Importing the validated resource pack...");
		builder.add("worldsmith.packs.exporting", "Writing and verifying the .wspack archive...");
		builder.add("worldsmith.packs.opening_folder", "Opening the exchange folder...");
		builder.add("worldsmith.packs.preparing_creation", "Validating the selected bundle for a new world...");
		builder.add("worldsmith.packs.busy_hint", "You can go back when this operation finishes.");
		builder.add("worldsmith.packs.boundary", "Data-only import: no automatic activation, no current-world changes, no Java source execution.");
		builder.add("worldsmith.packs.refreshed", "Refreshed: %s library packs, %s import files.");
		builder.add("worldsmith.packs.inspected", "Validated %s. Import remains a separate action.");
		builder.add("worldsmith.packs.imported", "Imported %s into the library. Not activated.");
		builder.add("worldsmith.packs.imported_existing", "%s already exists in the library; existing metadata was retained. Not activated.");
		builder.add("worldsmith.packs.exported", "Exported and verified %s. The full path is shown below.");
		builder.add("worldsmith.packs.folder_requested", "Asked the system to open this folder. If no window appears, copy the path below.");
		builder.add("worldsmith.packs.error", "Action failed: %s");
		builder.add("worldsmith.packs.small_window", "Enlarge the window or lower GUI scale to at least 320 x 200.");
		builder.add("worldsmith.packs.empty_library", "No world packs yet");
		builder.add("worldsmith.packs.empty_inbox", "No importable .wspack files. Open the import folder and copy a resource archive into it.");
		builder.add("worldsmith.packs.bundle_id", "Bundle ID: %s");
		builder.add("worldsmith.packs.pack_details", "Bundle format %s | PNG assets: %s");
		builder.add("worldsmith.packs.archive_version", "Archive v%s | Bundle format %s");
		builder.add("worldsmith.packs.file_size", "Archive size: %s bytes");
		builder.add("worldsmith.packs.library_limit", "Catalog: up to 64 bundles. Metadata listing is not a native-validation receipt.");
		builder.add("worldsmith.packs.inbox_truncated", "This folder has more files than the bounded list displays. Move completed imports out and refresh.");
	}

	private static void addQuestJournal(TranslationBuilder builder) {
		builder.add("worldsmith.creation.title", "A world awaits");
		builder.add("worldsmith.creation.preparing", "This world is still taking shape. Please wait a moment.");
		builder.add("worldsmith.arrival.current", "Your next chapter · %s");
		builder.add("worldsmith.arrival.complete", "The main journey is complete. More stories await beyond the horizon.");
		builder.add("worldsmith.arrival.explore", "Follow the stories of this land and begin your journey.");
		builder.add("worldsmith.arrival.claim", "Your journey has borne fruit. Press %s to claim your gifts.");
		builder.add("worldsmith.arrival.journal", "Press %s to open your journey journal.");
		builder.add("worldsmith.arrival.skip", "Esc · Continue your journey");
		builder.add("worldsmith.config.category.experience", "Journey experience");
		builder.add("worldsmith.config.arrival", "Arrival story and objectives");
		builder.add("worldsmith.config.arrival.tooltip", "Show the world's story and your current objectives for about eight seconds when you enter. Press Esc to skip.");
		builder.add("worldsmith.advancements.claim_hint", "Complete the objectives, then claim the reward in the quest journal to complete this advancement.");
		builder.add("key.category.worldsmith.quests", "Worldsmith");
		builder.add("key.worldsmith.quest_journal", "Open quest journal");
		builder.add("worldsmith.quests.title", "World Quest Journal");
		builder.add("worldsmith.quests.local_only", "The journey journal unfolds in your local worlds.");
		builder.add("worldsmith.quests.channel_unavailable", "The journey journal is not ready yet. Please wait a moment.");
		builder.add("worldsmith.quests.world_changed", "You have entered another land. Open its journey journal again.");
		builder.add("worldsmith.quests.loading", "Opening your journey journal...");
		builder.add("worldsmith.quests.processing", "Recording this part of your journey...");
		builder.add("worldsmith.quests.timeout", "Your journal is taking a moment. Checking its latest progress...");
		builder.add("worldsmith.quests.previous", "Previous quest page");
		builder.add("worldsmith.quests.next", "Next quest page");
		builder.add("worldsmith.quests.deliver", "Deliver items");
		builder.add("worldsmith.quests.claim", "Claim rewards");
		builder.add("worldsmith.quests.delivery_hint", "Contribute the required items from your main inventory. You may deliver them in parts.");
		builder.add("worldsmith.quests.claim_hint", "Complete your objectives to claim your gifts. Leave room in your main inventory.");
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
		builder.add("worldsmith.quests.feedback.none", "Your journey journal is up to date.");
		builder.add("worldsmith.quests.feedback.delivered", "Items delivered; progress updated.");
		builder.add("worldsmith.quests.feedback.claimed", "Rewards claimed.");
		builder.add("worldsmith.quests.feedback.no_materials", "There are no more required items in your main inventory.");
		builder.add("worldsmith.quests.feedback.no_space", "Not enough inventory space. Rewards remain unclaimed.");
		builder.add("worldsmith.quests.feedback.locked", "This quest is not unlocked yet.");
		builder.add("worldsmith.quests.feedback.not_ready", "Complete every objective before claiming rewards.");
		builder.add("worldsmith.quests.feedback.already_claimed", "These rewards have already been claimed.");
		builder.add("worldsmith.quests.feedback.stale_revision", "Your journey has advanced. Continue from the latest objectives.");
		builder.add("worldsmith.quests.feedback.scope_mismatch", "This journal belongs to another land. Open the current journal again.");
		builder.add("worldsmith.quests.feedback.unavailable", "No quest journal is available in this world.");
		builder.add("worldsmith.quests.feedback.error", "This action is not complete yet. Try again in a moment.");
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
