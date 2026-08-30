package com.wjz.worldsmith.client;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.validation.DiagnosticSeverity;
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithPackExporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Carries a completed MCP pack into Minecraft's own Create World data-pack flow.
 *
 * <p>The selected pack id is persisted separately from the pack itself. If the
 * world-creation screen is open, activation starts immediately; otherwise the
 * next screen initialization sees the selection and applies it. Minecraft still
 * performs the authoritative reload and codec validation before the preset is
 * selected.
 */
public final class WorldsmithWorldCreationBridge {
	private static final Pattern PACK_ID = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern GENERATED_PRESET =
		Pattern.compile("generated/([0-9a-f]{64})/wasteland");
	private static final Pattern GENERATED_BIOME =
		Pattern.compile("generated/([0-9a-f]{64})/([a-z0-9_.-]+)");
	private static final String GENERATED_FOLDER_PREFIX = "worldsmith-generated-";
	private static final String REPOSITORY_PREFIX = "file/" + GENERATED_FOLDER_PREFIX;
	private static final String ACTIVE_FILE_NAME = "active-pack.txt";

	private static final Map<CreateWorldScreen, ScreenState> SCREENS = new WeakHashMap<>();
	private static final Map<String, String> DISPLAY_NAMES = new ConcurrentHashMap<>();
	private static final Map<String, String> BIOME_NAMES = new ConcurrentHashMap<>();
	private static volatile String activePackId;

	private WorldsmithWorldCreationBridge() {
	}

	/** Installs the MCP completion callback and restores the previous selection. */
	public static void initialize() {
		activePackId = readActivePack();
		WorldsmithMcpService.setPackFinishedListener(WorldsmithWorldCreationBridge::activatePack);
	}

	/** Called by Fabric after a Create World screen has initialized. */
	public static void onScreenOpened(CreateWorldScreen screen) {
		ScreenState state = SCREENS.computeIfAbsent(screen, ignored -> new ScreenState());
		installStateListener(screen, state);
		String selected = activePackId;
		if (selected != null) {
			// AFTER_INIT is still inside Minecraft's screen setup. Defer the reload
			// one client task so replacing the screen cannot re-enter that setup.
			Minecraft.getInstance().execute(() -> {
				if (Minecraft.getInstance().gui.screen() == screen) {
					applyPack(screen, state, selected);
				}
			});
		}
	}

	/**
	 * Called from the MCP request thread when finish_world proves the pack valid.
	 * The file write makes the choice survive closing the current screen; all
	 * Minecraft UI work is moved back onto the render thread.
	 */
	public static void activatePack(String packId) {
		WorldsmithPack pack = loadManagedPack(packId);
		writeActivePack(packId);
		activePackId = packId;
		DISPLAY_NAMES.put(packId, pack.getManifest().getDisplayName());

		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			Screen current = minecraft.gui.screen();
			if (current instanceof CreateWorldScreen createWorldScreen) {
				onScreenOpened(createWorldScreen);
			}
		});
	}

	/** Returns a literal name for one hash-scoped preset, otherwise null. */
	public static Component displayName(Holder<WorldPreset> preset) {
		if (preset == null) {
			return null;
		}
		Optional<ResourceKey<WorldPreset>> key = preset.unwrapKey();
		if (key.isEmpty() || !key.get().identifier().getNamespace().equals(Worldsmith.MOD_ID)) {
			return null;
		}
		Matcher matcher = GENERATED_PRESET.matcher(key.get().identifier().getPath());
		if (!matcher.matches()) {
			return null;
		}
		String packId = matcher.group(1);
		String name = DISPLAY_NAMES.computeIfAbsent(packId, id -> {
			try {
				return loadManagedPack(id).getManifest().getDisplayName();
			} catch (RuntimeException failure) {
				return id.substring(0, 12);
			}
		});
		return Component.literal(name);
	}

	/** Friendly F3 label for a generated biome, or null for every normal biome. */
	public static String debugBiomeName(Holder<Biome> biome) {
		if (biome == null) {
			return null;
		}
		Optional<ResourceKey<Biome>> key = biome.unwrapKey();
		if (key.isEmpty() || !key.get().identifier().getNamespace().equals(Worldsmith.MOD_ID)) {
			return null;
		}
		Matcher matcher = GENERATED_BIOME.matcher(key.get().identifier().getPath());
		if (!matcher.matches()) {
			return null;
		}
		String packId = matcher.group(1);
		String biomeId = matcher.group(2);
		return BIOME_NAMES.computeIfAbsent(packId + "/" + biomeId, ignored -> {
			try {
				WorldsmithPack pack = loadManagedPack(packId);
				String biomeName = pack.getBiomes().getBiomes().stream()
					.filter(definition -> definition.getId().equals(biomeId))
					.map(definition -> definition.getDisplayName())
					.findFirst()
					.orElse(biomeId);
				return pack.getManifest().getDisplayName() + " / " + biomeName;
			} catch (RuntimeException failure) {
				return null;
			}
		});
	}

	private static void applyPack(CreateWorldScreen screen, ScreenState state, String packId) {
		if (packId.equals(state.appliedPackId) || packId.equals(state.applyingPackId)) {
			return;
		}

		try {
			WorldsmithPack pack = loadManagedPack(packId);
			CompiledPack compiled = CompiledPack.scoped(pack);
			CreateWorldScreenAccessor access = (CreateWorldScreenAccessor) screen;
			WorldCreationUiState uiState = access.worldsmith$getUiState();
			Path tempDataPackDirectory = access.worldsmith$getOrCreateTempDataPackDir();
			if (tempDataPackDirectory == null) {
				throw new IllegalStateException("Minecraft did not create its temporary data-pack directory");
			}

			// The registry ids keep the full hash. The temporary repository folder
			// only needs to be distinct within one Create World screen, and keeping
			// it short avoids Windows path-length trouble once nested worldgen ids
			// are appended below it.
			String folderName = GENERATED_FOLDER_PREFIX + packId.substring(0, 16);
			exportAtomically(compiled, uiState, tempDataPackDirectory, folderName);
			PackRepository repository = repository(access, uiState.getSettings().dataConfiguration());
			String repositoryId = "file/" + folderName;
			if (!repository.getAvailableIds().contains(repositoryId)) {
				throw new IllegalStateException("Minecraft did not discover exported pack " + repositoryId);
			}

			ArrayList<String> selected = new ArrayList<>(repository.getSelectedIds());
			selected.removeIf(id -> id.startsWith(REPOSITORY_PREFIX));
			selected.add(repositoryId);
			repository.setSelected(selected);

			state.applyingPackId = packId;
			state.repositoryId = repositoryId;
			state.presetKey = compiled.worldPresetKey();
			state.seed = pack.getTerrain().getSeed();
			access.worldsmith$tryApplyNewDataPacks(
				repository,
				false,
				configuration -> abort(screen, state, configuration)
			);
			// When the same immutable pack is already loaded Minecraft may take a
			// fast path that does not replace the context, so also try immediately.
			trySelectPreset(screen, state, uiState);
		} catch (Exception failure) {
			state.applyingPackId = null;
			Worldsmith.LOGGER.error("Could not activate Worldsmith pack {} in Create World", packId, failure);
		}
	}

	private static void installStateListener(CreateWorldScreen screen, ScreenState state) {
		if (state.listenerInstalled) {
			return;
		}
		state.listenerInstalled = true;
		((CreateWorldScreenAccessor) screen).worldsmith$getUiState()
			.addListener(uiState -> trySelectPreset(screen, state, uiState));
	}

	private static void trySelectPreset(
		CreateWorldScreen screen,
		ScreenState state,
		WorldCreationUiState uiState
	) {
		String packId = state.applyingPackId;
		ResourceKey<WorldPreset> presetKey = state.presetKey;
		if (packId == null || presetKey == null) {
			return;
		}
		if (!uiState.getSettings().dataConfiguration().dataPacks().getEnabled().contains(state.repositoryId)) {
			return;
		}

		Holder.Reference<WorldPreset> preset = uiState.getSettings().worldgenLoadContext()
			.lookup(Registries.WORLD_PRESET)
			.flatMap(registry -> registry.get(presetKey))
			.orElse(null);
		if (preset == null) {
			return;
		}

		Long seed = state.seed;
		state.applyingPackId = null;
		state.appliedPackId = packId;
		state.repositoryId = null;
		state.presetKey = null;
		state.seed = null;
		uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
		if (seed != null) {
			uiState.setSeed(Long.toString(seed));
		}
		Worldsmith.LOGGER.info("Worldsmith pack {} is available and selected in More World Options", packId);
	}

	private static void abort(
		CreateWorldScreen screen,
		ScreenState state,
		WorldDataConfiguration ignored
	) {
		String packId = state.applyingPackId;
		state.applyingPackId = null;
		state.repositoryId = null;
		state.presetKey = null;
		state.seed = null;
		Worldsmith.LOGGER.warn("Minecraft rejected Worldsmith pack {} while reloading Create World", packId);
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(screen));
	}

	private static PackRepository repository(
		CreateWorldScreenAccessor access,
		WorldDataConfiguration configuration
	) {
		PackRepository existing = access.worldsmith$getTempDataPackRepository();
		if (existing != null) {
			existing.reload();
			return existing;
		}
		Pair<Path, PackRepository> created = access.worldsmith$getDataPackSelectionSettings(configuration);
		if (created == null) {
			throw new IllegalStateException("Minecraft did not create its temporary pack repository");
		}
		return created.getSecond();
	}

	private static void exportAtomically(
		CompiledPack pack,
		WorldCreationUiState uiState,
		Path repositoryRoot,
		String folderName
	) throws IOException {
		Path root = repositoryRoot.toAbsolutePath().normalize();
		Path target = root.resolve(folderName).normalize();
		if (!target.startsWith(root)) {
			throw new IllegalArgumentException("Generated pack path escaped Minecraft's temporary directory");
		}
		if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("pack.mcmeta"))) {
			return;
		}

		Path pending = Files.createTempDirectory(root, ".worldsmith-pending-");
		try {
			WorldsmithPackExporter.export(pack, uiState.getSettings().worldgenLoadContext(), pending);
			try {
				Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(pending, target);
			} catch (FileAlreadyExistsException ignored) {
				if (!Files.isRegularFile(target.resolve("pack.mcmeta"))) {
					throw ignored;
				}
			}
		} finally {
			deleteKnownTempTree(root, pending);
		}
	}

	private static void deleteKnownTempTree(Path root, Path pending) throws IOException {
		Path normalized = pending.toAbsolutePath().normalize();
		if (!normalized.startsWith(root) || !normalized.getFileName().toString().startsWith(".worldsmith-pending-")) {
			throw new IllegalArgumentException("Refusing to clean an unexpected path " + normalized);
		}
		if (!Files.exists(normalized)) {
			return;
		}
		try (var paths = Files.walk(normalized)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static WorldsmithPack loadManagedPack(String packId) {
		if (packId == null || !PACK_ID.matcher(packId).matches()) {
			throw new IllegalArgumentException("Pack id must be a lowercase SHA-256");
		}
		Path root = WorldsmithMcpService.packDirectory().toAbsolutePath().normalize();
		Path directory = root.resolve(packId).normalize();
		if (!directory.startsWith(root) || !Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
			throw new IllegalArgumentException("Managed Worldsmith pack does not exist: " + packId);
		}
		WorldsmithPack pack = WorldsmithPackLoader.loadDirectory(directory);
		boolean invalid = WorldsmithPackValidator.INSTANCE.validate(pack).stream()
			.anyMatch(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.ERROR);
		if (invalid) {
			throw new IllegalArgumentException("Managed Worldsmith pack is invalid: " + packId);
		}
		return pack;
	}

	private static Path activePackFile() {
		return WorldsmithMcpService.packDirectory().getParent().resolve(ACTIVE_FILE_NAME);
	}

	private static String readActivePack() {
		Path file = activePackFile();
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			String id = Files.readString(file, StandardCharsets.UTF_8).trim();
			loadManagedPack(id);
			return id;
		} catch (Exception failure) {
			Worldsmith.LOGGER.warn("Ignoring invalid Worldsmith active-pack selection in {}", file, failure);
			return null;
		}
	}

	private static void writeActivePack(String packId) {
		Path target = activePackFile();
		try {
			Files.createDirectories(target.getParent());
			Path pending = Files.createTempFile(target.getParent(), ".active-pack-", ".tmp");
			try {
				Files.writeString(pending, packId + System.lineSeparator(), StandardCharsets.UTF_8);
				try {
					Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException ignored) {
					Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(pending);
			}
		} catch (IOException failure) {
			throw new IllegalStateException("Could not save active Worldsmith pack selection", failure);
		}
	}

	private static final class ScreenState {
		private boolean listenerInstalled;
		private String applyingPackId;
		private String appliedPackId;
		private String repositoryId;
		private ResourceKey<WorldPreset> presetKey;
		private Long seed;
	}
}
