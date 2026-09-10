package com.wjz.worldsmith.client;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.mcp.PublicationStatus;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.validation.DiagnosticSeverity;
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithPackExporter;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
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
    private static final Map<String,PublicationStatus> PUBLICATIONS=new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService EXPORTS=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"worldsmith-native-export");t.setDaemon(true);return t;});
    private static final String COMPILER_VERSION=com.wjz.worldsmith.core.drawhost.DrawingVersions.NATIVE_COMPILER+":content-runtime-2";

	private WorldsmithWorldCreationBridge() {
	}

	/** Installs the MCP completion callback and restores the previous selection. */
	public static void initialize() {
		activePackId = readActivePack();
		WorldsmithMcpService.setPackFinishedListener(WorldsmithWorldCreationBridge::activatePack);
		WorldsmithMcpService.setPublicationHost(WorldsmithWorldCreationBridge::requestPublication);
	}

    public static PublicationStatus requestPublication(WorldsmithPack pack,Path directory) {
        String id=pack.getManifest().getId();
        if(!id.equals(activePackId))activatePack(id);
        var waiting=new PublicationStatus("WAITING_NATIVE_CONTEXT","Open the Create World screen for native validation and activation",List.of());
        var result=new CompletableFuture<PublicationStatus>();
        // Read UI-owned state on the render thread, not from an MCP handler. A receipt
        // from an older Create World screen must never complete a different context.
        Runnable check=()->{
            try {
                if(Minecraft.getInstance().gui.screen() instanceof CreateWorldScreen screen) {
                    var state=SCREENS.computeIfAbsent(screen,ignored->new ScreenState());
                    installStateListener(screen,state);applyPack(screen,state,id);
                    result.complete(PUBLICATIONS.getOrDefault(id,waiting));
                } else result.complete(waiting);
            } catch(Exception e){failed(id,e);result.complete(PUBLICATIONS.get(id));}
        };
        var minecraft=Minecraft.getInstance();
        if(minecraft.isSameThread())check.run();else minecraft.execute(check);
        try {return result.get(2,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException e){return waiting;}
        catch(InterruptedException e){Thread.currentThread().interrupt();return waiting;}
        catch(java.util.concurrent.ExecutionException e){failed(id,e);return PUBLICATIONS.get(id);}
    }
    private static void failed(String id,Throwable failure) {
        Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
        String message=cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();
        PUBLICATIONS.put(id,new PublicationStatus("FAILED",message,List.of(new com.wjz.worldsmith.core.validation.Diagnostic("native","NATIVE_PUBLICATION_FAILED",DiagnosticSeverity.ERROR,message))));
        Worldsmith.LOGGER.error("Native publication failed for {}",id,failure);
    }

    /** Genuine cancellation only; temporary native reload screens must retain the same publication. */
    public static void onCancelled(CreateWorldScreen screen) {
        var state=SCREENS.remove(screen);
        if(state==null)return;
        ++state.serial;
        String scope=state.content==null ? state.appliedPackId : state.content.scope();
        state.applyingPackId=null;state.appliedPackId=null;state.validatedContext=null;
        if(scope==null)return;
        PUBLICATIONS.put(scope,new PublicationStatus("WAITING_NATIVE_CONTEXT","Creation was cancelled; the immutable bundle is preserved",List.of()));
        var client=Minecraft.getInstance();
        WorldContentClientRuntime.whenIdle().thenComposeAsync(ignored -> {
            if(client.getSingleplayerServer()!=null || client.level!=null || client.gui.screen() instanceof CreateWorldScreen
                || com.wjz.worldsmith.client.content.WorldContentStartupBarrier.isLoading()
                || !scope.equals(WorldContentClientRuntime.activeScope()))return CompletableFuture.completedFuture(null);
            return WorldContentClientRuntime.clear(scope);
        },client).exceptionally(error->{Worldsmith.LOGGER.error("Cancelled creation resource cleanup failed",error);return null;});
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

    private static void applyPack(CreateWorldScreen screen,ScreenState state,String packId) {
        if(com.wjz.worldsmith.client.content.WorldContentStartupBarrier.isLoading()) {
            PUBLICATIONS.put(packId,new PublicationStatus("WAITING_NATIVE_CONTEXT","A local world is starting; reopen Create World before publishing another bundle",List.of()));
            return;
        }
        var access=(CreateWorldScreenAccessor)screen;
        var uiState=access.worldsmith$getUiState();
        if(packId.equals(state.appliedPackId) && state.validatedContext==uiState.getSettings().worldgenLoadContext()
            && packId.equals(WorldContentClientRuntime.activeScope())) {
            // Re-select explicitly if the player changed the preset on the same screen.
            var preset=state.validatedContext.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(state.appliedPresetKey);
            uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
            if(state.appliedSeed!=null)uiState.setSeed(Long.toString(state.appliedSeed));
            PUBLICATIONS.put(packId,new PublicationStatus("PUBLISHED","Native export, reload and preset activation succeeded",List.of()));return;
        }
        if(packId.equals(state.applyingPackId))return;
        if(packId.equals(state.lastAttemptPackId) && state.lastAttemptContext==uiState.getSettings().worldgenLoadContext()
            && PUBLICATIONS.getOrDefault(packId,new PublicationStatus("", "",List.of())).getStage().equals("FAILED"))return;
        state.applyingPackId=packId;long serial=++state.serial;
        try {
            var context=uiState.getSettings().worldgenLoadContext();
            state.lastAttemptContext=context;state.lastAttemptPackId=packId;
            var directory=access.worldsmith$getOrCreateTempDataPackDir();
            if(directory==null)throw new IllegalStateException("Minecraft did not create a temporary pack repository");
            String folder=GENERATED_FOLDER_PREFIX+packId.substring(0,16)+"-"+Integer.toHexString(System.identityHashCode(context));
            PUBLICATIONS.put(packId,new PublicationStatus("NATIVE_CHECK","Validating against the actual Create World registry context",List.of()));
            CompletableFuture.supplyAsync(()->{
                try {
                    var pack=loadManagedPack(packId);var compiled=CompiledPack.scoped(pack);
                    exportAtomically(compiled,context,directory,folder);
                    return new Exported(compiled,WorldContentRuntime.prepare(compiled));
                } catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            },EXPORTS).whenComplete((exported,failure)->Minecraft.getInstance().execute(()->{
                if(state.serial!=serial || !packId.equals(state.applyingPackId))return;
                if(failure!=null){state.applyingPackId=null;failed(packId,failure);return;}
                if(Minecraft.getInstance().gui.screen()!=screen || uiState.getSettings().worldgenLoadContext()!=context || !packId.equals(activePackId)) {
                    state.applyingPackId=null;PUBLICATIONS.put(packId,new PublicationStatus("WAITING_NATIVE_CONTEXT","Creation context changed; return to Create World to retry",List.of()));return;
                }
                try {
                    var compiled=exported.compiled();
                    state.content=WorldContentClientRuntime.prepare(exported.content());
                    state.resourceReloading=false;
                    var repository=repository(access,uiState.getSettings().dataConfiguration());String repositoryId="file/"+folder;
                    if(!repository.getAvailableIds().contains(repositoryId))throw new IllegalStateException("Minecraft did not discover the exported pack");
                    var selected=new ArrayList<>(repository.getSelectedIds());selected.removeIf(id->id.startsWith(REPOSITORY_PREFIX));selected.add(repositoryId);repository.setSelected(selected);
                    state.repositoryId=repositoryId;state.presetKey=compiled.worldPresetKey();state.seed=compiled.terrain().getSeed();
                    PUBLICATIONS.put(packId,new PublicationStatus("RELOADING","Minecraft is reloading the validated pack",List.of()));
                    access.worldsmith$tryApplyNewDataPacks(repository,false,configuration->abort(screen,state,configuration));
                    trySelectPreset(screen,state,uiState);
                } catch(Exception e){state.applyingPackId=null;failed(packId,e);}
            }));
        } catch(Exception e){state.applyingPackId=null;failed(packId,e);}
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
		if (packId == null || presetKey == null || state.resourceReloading) {
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

        Long seed=state.seed; long serial=state.serial;
        var targetContext=uiState.getSettings().worldgenLoadContext();
        var content=state.content;
        if(content==null){state.applyingPackId=null;failed(packId,new IllegalStateException("Missing prepared native content"));return;}
        state.resourceReloading=true;
        PUBLICATIONS.put(packId,new PublicationStatus("CLIENT_RESOURCES","Reloading and verifying generated block and creature assets",List.of()));
        content.activate().whenCompleteAsync((ignored,failure)->{
            if(state.serial!=serial || !packId.equals(activePackId) || uiState.getSettings().worldgenLoadContext()!=targetContext) {
                if(failure==null)content.rollback().exceptionally(rollback->{Worldsmith.LOGGER.error("Stale publication rollback failed",rollback);return null;});
                if(state.serial==serial){state.applyingPackId=null;state.resourceReloading=false;}
                return;
            }
            state.resourceReloading=false;state.applyingPackId=null;state.repositoryId=null;state.presetKey=null;state.seed=null;
            if(failure!=null){state.appliedPackId=null;state.validatedContext=null;failed(packId,failure);return;}
            try {
                uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
                if(seed!=null)uiState.setSeed(Long.toString(seed));
                state.appliedPackId=packId;state.appliedPresetKey=presetKey;state.appliedSeed=seed;state.validatedContext=targetContext;
                PUBLICATIONS.put(packId,new PublicationStatus("PUBLISHED","Native data reload, verified client assets, world bindings and preset activation succeeded",List.of()));
                Worldsmith.LOGGER.info("Worldsmith bundle {} is available with verified content resources",packId);
            } catch(Exception error) {
                state.appliedPackId=null;state.validatedContext=null;failed(packId,error);
                content.rollback().exceptionally(rollback->{Worldsmith.LOGGER.error("Publication rollback failed",rollback);return null;});
            }
        },Minecraft.getInstance());
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
		if(packId!=null)failed(packId,new IllegalStateException("Minecraft rejected the data-pack reload"));
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
		HolderLookup.Provider context,
		Path repositoryRoot,
		String folderName
	) throws IOException {
		Path root = repositoryRoot.toAbsolutePath().normalize();
		Path target = root.resolve(folderName).normalize();
		if (!target.startsWith(root)) {
			throw new IllegalArgumentException("Generated pack path escaped Minecraft's temporary directory");
		}
		String stamp=pack.id()+":"+SharedConstants.getCurrentVersion().dataVersion().version()+":"+COMPILER_VERSION+":"+System.identityHashCode(context);
        if(Files.isRegularFile(target.resolve("worldsmith-native.stamp")) && Files.readString(target.resolve("worldsmith-native.stamp")).equals(stamp))return;
        if(Files.exists(target))throw new IOException("Stale native export cache; reopen Create World to obtain a fresh repository");

		Path pending = Files.createTempDirectory(root, ".worldsmith-pending-");
		try {
			WorldsmithPackExporter.export(pack, context, pending);
			Files.writeString(pending.resolve("worldsmith-native.stamp"),stamp);
			try {
				Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(pending, target);
			} catch (FileAlreadyExistsException ignored) {
				if (!Files.isRegularFile(target.resolve("worldsmith-native.stamp")) || !Files.readString(target.resolve("worldsmith-native.stamp")).equals(stamp)) {
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
		private long serial;
		private HolderLookup.Provider validatedContext;
        private HolderLookup.Provider lastAttemptContext;
        private String lastAttemptPackId;
        private ResourceKey<WorldPreset> appliedPresetKey;
        private Long appliedSeed;
        private WorldContentClientRuntime.Prepared content;
        private boolean resourceReloading;
	}
    private record Exported(CompiledPack compiled,WorldContentRuntime.Prepared content) {}
}
