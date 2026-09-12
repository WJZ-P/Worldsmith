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
import com.wjz.worldsmith.content.WorldCreationIntent;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.client.content.WorldContentResources;
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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Carries a completed MCP pack into Minecraft's own Create World data-pack flow.
 *
 * <p>An explicit library action owns its exact Create World screen, including all
 * native reload callbacks. Authoring completion may suggest a pack but cannot
 * replace that player's selection. Native validation and asset loading precede
 * the final guarded creation commit.
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
	private static String defaultPackId;
    private static long nextRequestId;
    private static final Map<Runnable, WorldCreationIntent> OPENINGS = new java.util.IdentityHashMap<>();
    private static StartupExpectation expectedStartup;
    private static final Map<String,PublicationStatus> PUBLICATIONS=new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService EXPORTS=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"worldsmith-native-export");t.setDaemon(true);return t;});
    private static final String COMPILER_VERSION=com.wjz.worldsmith.core.drawhost.DrawingVersions.NATIVE_COMPILER+":content-runtime-3";

	private WorldsmithWorldCreationBridge() {
	}

	/** Installs the MCP completion callback and restores the previous selection. */
	public static void initialize() {
		defaultPackId = readActivePack();
		WorldsmithMcpService.setPackFinishedListener(WorldsmithWorldCreationBridge::activatePack);
		WorldsmithMcpService.setPublicationHost(WorldsmithWorldCreationBridge::requestPublication);
	}

    public static PublicationStatus requestPublication(WorldsmithPack pack,Path directory) {
        String id=pack.getManifest().getId();
        var waiting=new PublicationStatus("WAITING_NATIVE_CONTEXT","Open the Create World screen for native validation and activation",List.of());
        var result=new CompletableFuture<PublicationStatus>();
        // Read UI-owned state on the render thread, not from an MCP handler. A receipt
        // from an older Create World screen must never complete a different context.
        Runnable check=()->{
            try {
                if(Minecraft.getInstance().gui.screen() instanceof CreateWorldScreen screen) {
                    var state=SCREENS.computeIfAbsent(screen,ignored->new ScreenState());
                    if (state.cancelled || (state.intent != null && !state.intent.acceptsPublication(id))) {
                        result.complete(new PublicationStatus("WAITING_ACTIVATION", "The player's Create World selection belongs to another bundle; select this bundle explicitly to activate it", List.of()));
                        return;
                    }
                    if (state.intent != null && !state.intent.packId().equals(id) && state.applyingPackId != null) {
                        result.complete(new PublicationStatus("WAITING_NATIVE_CONTEXT", "The current creation request is still preparing; its result must settle before another authoring request can activate", List.of()));
                        return;
                    }
                    if (state.intent == null || !state.intent.packId().equals(id)) bindIntent(screen, state, newIntent(id, pack.getManifest().getDisplayName(), false));
                    defaultPackId = id;
                    installStateListener(screen,state);applyPack(screen,state);
                    result.complete(progressPublicationStatus(screen,id));
                } else {
                    // A blocking openFresh pumps client work; do not overwrite its explicit request.
                    var explicit=SCREENS.values().stream().filter(state->!state.cancelled && !state.committed
                        && state.intent!=null && state.intent.explicit()).findFirst().orElse(null);
                    if(explicit!=null && id.equals(explicit.intent.packId()) && explicit.applyingPackId!=null
                        && explicit.publicationStatus!=null)result.complete(explicit.publicationStatus);
                    else if (!OPENINGS.isEmpty() || explicit!=null || com.wjz.worldsmith.client.content.WorldContentStartupBarrier.isLoading())
                        result.complete(new PublicationStatus("WAITING_ACTIVATION", "An explicit world creation or world load owns activation", List.of()));
                    else { defaultPackId = id; DISPLAY_NAMES.put(id, pack.getManifest().getDisplayName()); result.complete(waiting); }
                }
            } catch(Exception e){failed(id,e);result.complete(failureStatus(e));}
        };
        var minecraft=Minecraft.getInstance();
        if(minecraft.isSameThread())check.run();else minecraft.execute(check);
        try {return result.get(2,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException e){return waiting;}
        catch(InterruptedException e){Thread.currentThread().interrupt();return waiting;}
        catch(java.util.concurrent.ExecutionException e){failed(id,e);return failureStatus(e);}
    }
    private static PublicationStatus failureStatus(Throwable failure) {
        Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
        String message=cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();
        message=message.substring(0,Math.min(message.length(),1536));
        return new PublicationStatus("FAILED",message,List.of(new com.wjz.worldsmith.core.validation.Diagnostic("native","NATIVE_PUBLICATION_FAILED",DiagnosticSeverity.ERROR,message)));
    }
    private static void failed(String id,Throwable failure) {
        PUBLICATIONS.put(id,failureStatus(failure));
        Worldsmith.LOGGER.error("Native publication failed for {}",id,failure);
    }

    private static void publish(ScreenState state, String id, PublicationStatus status) {
        state.publicationPackId = id; state.publicationStatus = status;
        PUBLICATIONS.put(id, status);
    }

    private static void failed(ScreenState state, String id, Throwable failure) {
        publish(state,id,failureStatus(failure));
        Worldsmith.LOGGER.error("Native publication failed for {}",id,failure);
    }

    /** Render-thread-only read, scoped to the actual screen, selected preset, bundle and native context. */
    public static PublicationStatus progressPublicationStatus(CreateWorldScreen screen, String packId) {
        var client = Minecraft.getInstance();
        if (!client.isSameThread()) throw new IllegalStateException("Create World publication state is render-thread owned");
        var notSelected = new PublicationStatus("NOT_SELECTED", "This bundle is not activated in this Create World context", List.of());
        if (screen == null || client.gui.screen() != screen || packId == null) return notSelected;
        var state = SCREENS.get(screen);
        if (state == null || state.cancelled || state.intent == null || !packId.equals(state.intent.packId())
            || !packId.equals(state.publicationPackId) || state.publicationStatus == null) return notSelected;
        var uiState = ((CreateWorldScreenAccessor)screen).worldsmith$getUiState();
        if ("PUBLISHED".equals(state.publicationStatus.getStage())) {
            var preset = uiState.getWorldType().preset();
            if (!packId.equals(state.appliedPackId) || state.validatedContext != uiState.getSettings().worldgenLoadContext()
                || !packId.equals(WorldContentClientRuntime.activeScope()) || preset == null
                || state.content==null || !WorldContentResources.isLoaded(packId,state.content.contentHash())
                || !uiState.getSettings().dataConfiguration().dataPacks().getEnabled().contains(state.appliedRepositoryId)
                || !java.util.Objects.equals(preset.unwrapKey().orElse(null), state.appliedPresetKey)) return notSelected;
            return state.publicationStatus;
        }
        if (packId.equals(state.applyingPackId)) {
            if ("NATIVE_CHECK".equals(state.publicationStatus.getStage())
                && state.lastAttemptContext != uiState.getSettings().worldgenLoadContext()) return notSelected;
            return state.publicationStatus;
        }
        if ("FAILED".equals(state.publicationStatus.getStage()) && packId.equals(state.lastAttemptPackId)
            && state.lastAttemptContext == uiState.getSettings().worldgenLoadContext()) return state.publicationStatus;
        return notSelected;
    }

    /** False defers vanilla's temp-directory deletion until our export writer has stopped. */
    public static boolean onCancelRequested(CreateWorldScreen screen) {
        var state=SCREENS.get(screen);
        if(state==null)return true;
        if(state.cancelReady) { SCREENS.remove(screen); return true; }
        if(state.cancelled)return false;
        state.cancelled=true;
        if(state.intent!=null)state.intent.cancel(screen);
        ++state.serial;
        String scope=state.content==null ? state.appliedPackId : state.content.scope();
        state.applyingPackId=null;state.appliedPackId=null;state.validatedContext=null;
        if(scope!=null)PUBLICATIONS.put(scope,new PublicationStatus("WAITING_NATIVE_CONTEXT","Creation was cancelled; the immutable bundle is preserved",List.of()));
        var client=Minecraft.getInstance();
        CompletableFuture<?> writer=CompletableFuture.allOf(
            state.exportTask==null ? CompletableFuture.completedFuture(null) : state.exportTask.handle((ignored,error)->null),
            state.nativeTask==null ? CompletableFuture.completedFuture(null) : state.nativeTask.handle((ignored,error)->null));
        if(expectedStartup!=null && expectedStartup.intent()==state.intent)expectedStartup=null;
        if (writer.isDone()) {
            state.cancelReady=true; SCREENS.remove(screen); client.schedule(()->clearCancelledContent(scope));
            return true;
        }
        updateCreateButton(screen,state);
        writer.handle((ignored,error)->null).whenCompleteAsync((ignored,error)-> {
            state.cancelReady=true;
            // No resource writer remains beneath vanilla's temporary repository now.
            if(client.gui.screen()==screen)screen.popScreen();
            else { SCREENS.remove(screen); ((CreateWorldScreenAccessor)screen).worldsmith$removeTempDataPackDir(); }
            clearCancelledContent(scope);
        },client);
        return false;
    }

    private static void clearCancelledContent(String scope) {
        if(scope==null)return;
        var client=Minecraft.getInstance();
        var idle=WorldContentClientRuntime.whenIdle();
        idle.whenCompleteAsync((ignored,error)->client.schedule(()-> {
            // A stale activation may start its rollback as its original future completes.
            // Wait for that newer transaction too rather than racing its ownership reservation.
            if(WorldContentClientRuntime.whenIdle()!=idle){clearCancelledContent(scope);return;}
            if(client.getSingleplayerServer()!=null || client.level!=null || client.gui.screen() instanceof CreateWorldScreen
                || !OPENINGS.isEmpty() || com.wjz.worldsmith.client.content.WorldContentStartupBarrier.isLoading()
                || !scope.equals(WorldContentClientRuntime.activeScope()))return;
            WorldContentClientRuntime.clear(scope).exceptionally(failure->{Worldsmith.LOGGER.error("Cancelled creation resource cleanup failed",failure);return null;});
        }),client);
    }

    /** Captured by both native CompletableFuture callbacks, not re-read when they complete. */
    public static Object captureNativeReload(CreateWorldScreen screen) {
        var state=SCREENS.get(screen);
        return state==null || state.intent==null ? null : new NativeReloadToken(state,state.intent,state.ticket);
    }

    public static boolean acceptsNativeReload(CreateWorldScreen screen,Object ownership) {
        if(ownership==null)return true;
        if(!(ownership instanceof NativeReloadToken token))return false;
        return SCREENS.get(screen)==token.state() && !token.state().cancelled
            && token.state().intent==token.intent() && token.intent().owns(screen,token.ticket());
    }

    public static void trackNativeReload(CreateWorldScreen screen,Object ownership,CompletableFuture<?> completion) {
        if(ownership instanceof NativeReloadToken token && SCREENS.get(screen)==token.state())token.state().nativeTask=completion;
    }

    public static void updateCreateButton(CreateWorldScreen screen) {
        var state=SCREENS.get(screen);if(state!=null)updateCreateButton(screen,state);
    }

    private static void updateCreateButton(CreateWorldScreen screen,ScreenState state) {
        if(state.intent==null)return;
        boolean ready=canCreate(screen,state);
        for(var child:screen.children())if(child instanceof Button button
            && button.getMessage().getContents() instanceof TranslatableContents translated
            && "selectWorld.create".equals(translated.getKey())) {
            button.active=ready;
            button.setTooltip(ready ? null : Tooltip.create(Component.translatable("worldsmith.creation.preparing")));
        }
    }

    private static boolean canCreate(CreateWorldScreen screen,ScreenState state) {
        if(state.cancelled || state.committed || state.intent==null || state.ticket==null
            || !state.intent.owns(screen,state.ticket) || state.applyingPackId!=null || state.resourceReloading
            || state.content==null || state.publicationStatus==null || !"PUBLISHED".equals(state.publicationStatus.getStage()))return false;
        String id=state.intent.packId();
        var ui=((CreateWorldScreenAccessor)screen).worldsmith$getUiState();
        var selected=ui.getWorldType().preset();
        var enabled=ui.getSettings().dataConfiguration().dataPacks().getEnabled();
        return id.equals(state.appliedPackId) && state.validatedContext==ui.getSettings().worldgenLoadContext()
            && selected!=null && java.util.Objects.equals(selected.unwrapKey().orElse(null),state.appliedPresetKey)
            && enabled.stream().filter(entry->entry.startsWith(REPOSITORY_PREFIX)).toList().equals(List.of(state.appliedRepositoryId))
            && id.equals(state.content.scope()) && id.equals(WorldContentClientRuntime.activeScope())
            && id.equals(WorldContentRuntime.activeScope()) && WorldContentResources.isLoaded(id,state.content.contentHash());
    }

    /** onCreate is used by both the button and the Enter key. No stale/default preset may pass. */
    public static boolean allowCreate(CreateWorldScreen screen) {
        var state=SCREENS.get(screen);
        if(state==null || state.intent==null)return true;
        if(!canCreate(screen,state)){creationBlocked(screen,state);return false;}
        var ui=((CreateWorldScreenAccessor)screen).worldsmith$getUiState();
        state.commitContext=ui.getSettings().worldgenLoadContext();
        state.commitDimensions=ui.getSettings().selectedDimensions();
        state.commitTicket=state.ticket;
        return true;
    }

    /** A confirmation dialog may outlive onCreate; check its captured request again before copying data. */
    public static boolean allowCreationCommit(CreateWorldScreen screen) {
        var state=SCREENS.get(screen);
        if(state==null || state.intent==null)return true;
        var ui=((CreateWorldScreenAccessor)screen).worldsmith$getUiState();
        if(!canCreate(screen,state) || state.commitContext!=ui.getSettings().worldgenLoadContext()
            || state.commitDimensions!=ui.getSettings().selectedDimensions() || state.commitTicket!=state.ticket) {
            creationBlocked(screen,state);Minecraft.getInstance().gui.setScreen(screen);return false;
        }
        state.committed=true;
        expectedStartup=new StartupExpectation(state.intent,ui.getTargetFolder());
        Worldsmith.LOGGER.info("Worldsmith creation commit request={} bundle={} preset={} datapack={}",
            state.intent.requestId(),state.intent.packId(),state.appliedPresetKey.identifier(),state.appliedRepositoryId);
        return true;
    }

    private static void creationBlocked(CreateWorldScreen screen,ScreenState state) {
        Worldsmith.LOGGER.warn("Worldsmith creation request {} is not ready to commit its selected bundle {} (stage={})",
            state.intent.requestId(),state.intent.packId(),state.publicationStatus==null ? "UNPREPARED" : state.publicationStatus.getStage());
        SystemToast.add(Minecraft.getInstance().gui.toastManager(),SystemToast.SystemToastId.PACK_LOAD_FAILURE,
            Component.translatable("worldsmith.creation.title"),Component.translatable("worldsmith.creation.preparing"));
        updateCreateButton(screen,state);
    }

    /** One-use check carried from the actual creation commit into the matching integrated-world load. */
    public static String consumeStartupExpectation(String levelId,boolean newWorld) {
        var expected=expectedStartup;expectedStartup=null;
        return newWorld && expected!=null && !expected.intent().cancelled() && expected.folder().equals(levelId)
            ? expected.intent().packId() : null;
    }

	/** Called by Fabric after a Create World screen has initialized. */
	public static void onScreenOpened(CreateWorldScreen screen) {
		ScreenState state = SCREENS.computeIfAbsent(screen, ignored -> new ScreenState());
		if (state.cancelled) return;
        if (state.intent == null) {
            var explicit = OPENINGS.get(((CreateWorldScreenAccessor)screen).worldsmith$getOnClose());
            if (explicit != null) bindIntent(screen,state,explicit);
            else if (defaultPackId != null) {
                var pack = loadManagedPack(defaultPackId);
                bindIntent(screen,state,newIntent(defaultPackId,pack.getManifest().getDisplayName(),false));
            }
        }
		installStateListener(screen, state);
        ScreenEvents.afterTick(screen).register(ignored->updateCreateButton(screen,state));
        updateCreateButton(screen,state);
		if (state.intent != null) {
            var request = state.intent;
            // schedule() queues even on the render thread; execute() may re-enter AFTER_INIT.
			Minecraft.getInstance().schedule(() -> {
				if (Minecraft.getInstance().gui.screen() == screen) {
					if(state.intent==request && !state.cancelled)applyPack(screen, state);
				}
			});
		}
	}

	/** Legacy completion hook: requests authoring activation without acquiring a player's explicit selection. */
	public static void activatePack(String packId) {
		WorldsmithPack pack = loadManagedPack(packId);
        requestPublication(pack,WorldsmithMcpService.packDirectory().resolve(packId));
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

    private static void applyPack(CreateWorldScreen screen,ScreenState state) {
        if(state.cancelled || state.committed || state.intent==null)return;
        var intent=state.intent;
        String packId=intent.packId();
        if(com.wjz.worldsmith.client.content.WorldContentStartupBarrier.isLoading()) {
            publish(state,packId,new PublicationStatus("WAITING_NATIVE_CONTEXT","A local world is starting; reopen Create World before publishing another bundle",List.of()));
            return;
        }
        var access=(CreateWorldScreenAccessor)screen;
        var uiState=access.worldsmith$getUiState();
        if(packId.equals(state.appliedPackId) && state.validatedContext==uiState.getSettings().worldgenLoadContext()
            && packId.equals(WorldContentClientRuntime.activeScope()) && state.content!=null
            && WorldContentResources.isLoaded(packId,state.content.contentHash())) {
            // Re-select explicitly if the player changed the preset on the same screen.
            var preset=state.validatedContext.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(state.appliedPresetKey);
            uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
            if(state.appliedSeed!=null)uiState.setSeed(Long.toString(state.appliedSeed));
            publish(state,packId,new PublicationStatus("PUBLISHED","Native export, reload and preset activation succeeded",List.of()));return;
        }
        if(packId.equals(state.applyingPackId))return;
        if(packId.equals(state.lastAttemptPackId) && state.lastAttemptContext==uiState.getSettings().worldgenLoadContext()
            && state.publicationStatus != null && "FAILED".equals(state.publicationStatus.getStage()))return;
        state.applyingPackId=packId;long serial=++state.serial;
        var ticket=intent.beginAttempt(screen);state.ticket=ticket;
        updateCreateButton(screen,state);
        try {
            var context=uiState.getSettings().worldgenLoadContext();
            state.lastAttemptContext=context;state.lastAttemptPackId=packId;
            var directory=access.worldsmith$getOrCreateTempDataPackDir();
            if(directory==null)throw new IllegalStateException("Minecraft did not create a temporary pack repository");
            String folder=GENERATED_FOLDER_PREFIX+packId.substring(0,16)+"-"+Integer.toHexString(System.identityHashCode(context));
            publish(state,packId,new PublicationStatus("NATIVE_CHECK","Validating against the actual Create World registry context",List.of()));
            var exportTask=CompletableFuture.supplyAsync(()->{
                try {
                    if(intent.cancelled())throw new java.util.concurrent.CancellationException("Creation export was cancelled");
                    var pack=loadManagedPack(packId);var compiled=CompiledPack.scoped(pack);
                    exportAtomically(compiled,context,directory,folder);
                    if(intent.cancelled())throw new java.util.concurrent.CancellationException("Creation export was cancelled");
                    return new Exported(compiled,WorldContentRuntime.prepare(compiled));
                } catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            },EXPORTS);
            state.exportTask=exportTask;
            exportTask.whenComplete((exported,failure)->Minecraft.getInstance().execute(()->{
                if(state.serial!=serial || state.intent!=intent || !intent.owns(screen,ticket) || !packId.equals(state.applyingPackId))return;
                if(failure!=null){state.applyingPackId=null;failed(state,packId,failure);updateCreateButton(screen,state);return;}
                if(Minecraft.getInstance().gui.screen()!=screen || uiState.getSettings().worldgenLoadContext()!=context) {
                    state.applyingPackId=null;publish(state,packId,new PublicationStatus("WAITING_NATIVE_CONTEXT","Creation context changed; return to Create World to retry",List.of()));return;
                }
                try {
                    var compiled=exported.compiled();
                    state.preparedContent=exported.content();
                    state.resourceReloading=false;
                    var repository=repository(access,uiState.getSettings().dataConfiguration());String repositoryId="file/"+folder;
                    if(!repository.getAvailableIds().contains(repositoryId))throw new IllegalStateException("Minecraft did not discover the exported pack");
                    var selected=new ArrayList<>(repository.getSelectedIds());selected.removeIf(id->id.startsWith(REPOSITORY_PREFIX));selected.add(repositoryId);repository.setSelected(selected);
                    state.repositoryId=repositoryId;state.presetKey=compiled.worldPresetKey();state.seed=compiled.terrain().getSeed();
                    publish(state,packId,new PublicationStatus("NATIVE_DATA_RELOAD","Minecraft is validating the selected data pack in its native registry context",List.of()));
                    access.worldsmith$tryApplyNewDataPacks(repository,false,configuration->{
                        if(state.serial==serial && state.intent==intent && intent.owns(screen,ticket))abort(screen,state,configuration);
                    });
                    trySelectPreset(screen,state,uiState);
                } catch(Exception e){state.applyingPackId=null;failed(state,packId,e);}
            }));
        } catch(Exception e){state.applyingPackId=null;failed(state,packId,e);}
    }

	private static void installStateListener(CreateWorldScreen screen, ScreenState state) {
		if (state.listenerInstalled) {
			return;
		}
		state.listenerInstalled = true;
		((CreateWorldScreenAccessor) screen).worldsmith$getUiState()
			.addListener(uiState -> { trySelectPreset(screen, state, uiState); updateCreateButton(screen,state); });
	}

	private static void trySelectPreset(
		CreateWorldScreen screen,
		ScreenState state,
		WorldCreationUiState uiState
	) {
		String packId = state.applyingPackId;
		ResourceKey<WorldPreset> presetKey = state.presetKey;
		if (packId == null || presetKey == null || state.resourceReloading || state.cancelled || state.intent == null) {
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
        String repositoryId=state.repositoryId;
        var intent=state.intent;var ticket=state.ticket;
        var targetContext=uiState.getSettings().worldgenLoadContext();
        state.lastAttemptContext=targetContext;
        var preparedContent=state.preparedContent;
        if(preparedContent==null){state.applyingPackId=null;failed(state,packId,new IllegalStateException("Missing prepared native content"));return;}
        state.resourceReloading=true;
        publish(state,packId,new PublicationStatus("CLIENT_RESOURCES","Native data validated; preparing or reusing verified client assets",List.of()));
        // Preparing the client transaction before another transition settles captures stale resource epochs.
        WorldContentClientRuntime.whenIdle().thenRunAsync(()-> {
            if(state.serial!=serial || state.intent!=intent || !intent.owns(screen,ticket))return;
            if(uiState.getSettings().worldgenLoadContext()!=targetContext) {
                state.resourceReloading=false;state.applyingPackId=null;
                publish(state,packId,new PublicationStatus("WAITING_NATIVE_CONTEXT","Native context changed before client activation; retry this creation request",List.of()));
                return;
            }
            var content=WorldContentClientRuntime.prepare(preparedContent);state.content=content;
            content.activate().whenCompleteAsync((ignored,failure)->{
                if(state.serial!=serial || state.intent!=intent || !intent.owns(screen,ticket)
                    || uiState.getSettings().worldgenLoadContext()!=targetContext) {
                    if(failure==null)content.rollback().exceptionally(rollback->{Worldsmith.LOGGER.error("Stale publication rollback failed",rollback);return null;});
                    if(state.serial==serial){state.applyingPackId=null;state.resourceReloading=false;}
                    return;
                }
                state.resourceReloading=false;state.applyingPackId=null;state.repositoryId=null;state.presetKey=null;state.seed=null;
                if(failure!=null){state.appliedPackId=null;state.validatedContext=null;failed(state,packId,failure);updateCreateButton(screen,state);return;}
                try {
                    uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
                    if(seed!=null)uiState.setSeed(Long.toString(seed));
                    state.appliedPackId=packId;state.appliedPresetKey=presetKey;state.appliedSeed=seed;state.validatedContext=targetContext;
                    state.appliedRepositoryId=repositoryId;
                    publish(state,packId,new PublicationStatus("PUBLISHED","The selected creation request passed native data validation, verified asset activation and preset binding",List.of()));
                    updateCreateButton(screen,state);
                    Worldsmith.LOGGER.info("Worldsmith creation request {} bound bundle {} to preset {}",intent.requestId(),packId,presetKey.identifier());
                } catch(Exception error) {
                    state.appliedPackId=null;state.validatedContext=null;failed(state,packId,error);updateCreateButton(screen,state);
                    content.rollback().exceptionally(rollback->{Worldsmith.LOGGER.error("Publication rollback failed",rollback);return null;});
                }
            },Minecraft.getInstance());
        },Minecraft.getInstance()).exceptionally(failure -> {
            if(state.intent==intent && intent.owns(screen,ticket)) {
                state.resourceReloading=false;state.applyingPackId=null;failed(state,packId,failure);updateCreateButton(screen,state);
            }
            return null;
        });
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
		if(packId!=null)failed(state,packId,new IllegalStateException("Minecraft rejected the data-pack reload"));
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

	private static final class ScreenState {
		private WorldCreationIntent intent;
        private WorldCreationIntent.Ticket ticket;
        private boolean cancelled, cancelReady, committed;
        private CompletableFuture<?> exportTask, nativeTask;
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
        private String appliedRepositoryId;
        private Long appliedSeed;
        private WorldContentRuntime.Prepared preparedContent;
        private WorldContentClientRuntime.Prepared content;
        private boolean resourceReloading;
        private String publicationPackId;
        private PublicationStatus publicationStatus;
        private Object commitContext, commitDimensions;
        private WorldCreationIntent.Ticket commitTicket;
	}

    private static WorldCreationIntent newIntent(String id,String displayName,boolean explicit) {
        return new WorldCreationIntent(++nextRequestId,id,displayName,explicit);
    }

    private static void bindIntent(CreateWorldScreen screen,ScreenState state,WorldCreationIntent intent) {
        if(state.intent==intent)return;
        if(state.intent!=null)state.intent.cancel(screen);
        ++state.serial;state.intent=intent;intent.bind(screen);
        state.ticket=null;state.applyingPackId=null;state.appliedPackId=null;state.validatedContext=null;
        state.repositoryId=null;state.presetKey=null;state.seed=null;state.appliedRepositoryId=null;
        state.lastAttemptContext=null;state.lastAttemptPackId=null;state.preparedContent=null;state.content=null;
        state.resourceReloading=false;state.publicationStatus=null;state.publicationPackId=null;
        state.commitContext=null;state.commitDimensions=null;state.commitTicket=null;state.committed=false;
        DISPLAY_NAMES.put(intent.packId(),intent.displayName());
        Worldsmith.LOGGER.info("Worldsmith creation request {} selected bundle {} (explicit={})",intent.requestId(),intent.packId(),intent.explicit());
    }

    /** File IO and validation only; called by the library worker and never changes a selection. */
    static CreationSelection prepareCreationSelection(String packId) {
        WorldsmithPack pack = loadManagedPack(packId);
        return new CreationSelection(packId, pack.getManifest().getDisplayName());
    }

    /** The unique close callback binds openFresh's exact screen even while managedBlock pumps queued MCP work. */
    static void openForCreation(CreationSelection selection, Screen expectedScreen) {
        var client = Minecraft.getInstance();
        if (!client.isSameThread() || client.gui.screen() != expectedScreen || client.level != null || client.getSingleplayerServer() != null)
            throw new IllegalStateException("World creation context changed; select the resource pack again from the main menu");
        var intent=newIntent(selection.id(),selection.displayName(),true);
        Runnable onClose=()->client.gui.setScreen(expectedScreen);
        OPENINGS.put(onClose,intent);
        defaultPackId=selection.id();
        DISPLAY_NAMES.put(selection.id(), selection.displayName());
        try { CreateWorldScreen.openFresh(client,onClose); }
        finally { OPENINGS.remove(onClose); }
    }

    record CreationSelection(String id, String displayName) {}
    private record NativeReloadToken(ScreenState state,WorldCreationIntent intent,WorldCreationIntent.Ticket ticket) {}
    private record StartupExpectation(WorldCreationIntent intent,String folder) {}
    private record Exported(CompiledPack compiled,WorldContentRuntime.Prepared content) {}
}
