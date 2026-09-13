package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.mcp.ResourcePackDetails;
import com.wjz.worldsmith.core.mcp.ResourcePackExchange;
import com.wjz.worldsmith.core.mcp.ResourcePackSummary;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import com.wjz.worldsmith.mixin.client.WorldCreationUiStateAccessor;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Menu-only pack choices: catalog reads do not compile, register presets or activate world assets. */
public final class WorldsmithWorldTypeMenu {
    private static final Map<WorldCreationUiState, State> STATES = new WeakHashMap<>();
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worldsmith-world-type-catalog"); t.setDaemon(true); return t;
    });
    private WorldsmithWorldTypeMenu() {}

    public static void attach(CreateWorldScreen screen) {
        var ui = ui(screen);
        if (!STATES.containsKey(ui)) STATES.put(ui, new State(screen));
        tick(screen);
    }
    public static void detach(CreateWorldScreen screen) { var s = STATES.remove(ui(screen)); if (s != null) s.closed = true; }
    public static List<ResourcePackSummary> packs(CreateWorldScreen screen) { var s = STATES.get(ui(screen)); return s == null ? List.of() : s.packs; }
    public static ResourcePackDetails details(CreateWorldScreen screen) {
        var s = STATES.get(ui(screen)); String id = WorldsmithWorldCreationBridge.selectedPackId(screen);
        return s == null || id == null ? null : s.details.get(id);
    }
    public static void tick(CreateWorldScreen screen) {
        var ui = ui(screen); var s = STATES.get(ui);
        if (s == null || s.closed || s.loading || WorldsmithWorldCreationBridge.creationInProgress(screen)) return;
        long now = System.nanoTime(); String selected = WorldsmithWorldCreationBridge.selectedPackId(screen);
        boolean needsDetails = selected != null && !s.details.containsKey(selected) && !Objects.equals(s.failedDetail, selected);
        if (!needsDetails && now < s.nextRead) return;
        s.loading = true; s.nextRead = now + 2_000_000_000L;
        CompletableFuture.supplyAsync(() -> {
            var exchange = new ResourcePackExchange(WorldsmithMcpService.packDirectory());
            var choices = exchange.listPacks(ResourcePackExchange.MAX_LISTED).stream()
                .sorted(Comparator.comparing(ResourcePackSummary::getDisplayName).thenComparing(ResourcePackSummary::getBundleId)).toList();
            ResourcePackDetails detail = null;
            if (needsDetails) try { detail = exchange.describePack(selected); } catch (RuntimeException ignored) { }
            return new Catalog(choices, detail);
        }, IO).whenCompleteAsync((catalog, error) -> {
            if (s.closed || STATES.get(ui) != s) return;
            s.loading = false;
            if (error != null) {
                if (!s.warned) Worldsmith.LOGGER.warn("World type catalog could not be read", error);
                s.warned = true; return;
            }
            s.warned = false;
            boolean changed = !s.packs.equals(catalog.packs()); s.packs = catalog.packs();
            if (catalog.detail() != null) s.details.put(catalog.detail().getSummary().getBundleId(), catalog.detail());
            else if (needsDetails) s.failedDetail = selected;
            if (changed) {
                var live = new HashSet<>(s.packs.stream().map(ResourcePackSummary::getBundleId).toList());
                if (selected != null) live.add(selected);
                s.entries.keySet().retainAll(live); s.details.keySet().retainAll(live); s.failedDetail = null;
            }
            if (WorldsmithWorldCreationBridge.creationInProgress(screen)) return;
            WorldsmithWorldCreationBridge.catalogAvailable(screen, s.packs);
            showSelected(screen); ui.onChanged();
        }, Minecraft.getInstance());
    }

    /** The native CycleButton keeps its normal/extended vanilla choices and adds every managed bundle. */
    public static List<WorldCreationUiState.WorldTypeEntry> choices(WorldCreationUiState ui, List<WorldCreationUiState.WorldTypeEntry> original) {
        var result = new ArrayList<WorldCreationUiState.WorldTypeEntry>();
        for (var entry : original) if (!isManagedPreset(entry.preset()) && !isLegacyPreset(entry.preset())) result.add(entry);
        if (result.isEmpty()) ui.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
            .get(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL).ifPresent(p -> result.add(new WorldCreationUiState.WorldTypeEntry(p)));
        var s = STATES.get(ui); var screen = s == null ? null : s.owner.get();
        if (screen == null || s.closed) return result;
        for (var pack : s.packs) result.add(entry(ui, s, pack.getBundleId(), pack.getDisplayName()));
        String selected = WorldsmithWorldCreationBridge.selectedPackId(screen);
        if (selected != null && s.packs.stream().noneMatch(p -> p.getBundleId().equals(selected))) {
            result.add(entry(ui, s, selected, WorldsmithWorldCreationBridge.selectedPackTitle(screen)));
        }
        return result;
    }
    private static WorldCreationUiState.WorldTypeEntry entry(WorldCreationUiState ui, State s, String id, String title) {
        // A genuinely prepared preset remains usable by native confirmation; menu stand-ins never reach serialization.
        if (id.equals(managedId(ui.getWorldType().preset()))) return ui.getWorldType();
        var existing = s.entries.get(id);
        if (existing != null && existing.preset().value() instanceof DeferredPreset p && p.title.equals(title)) return existing;
        var created = deferredChoice(id, title);
        s.entries.put(id, created); return created;
    }
    static WorldCreationUiState.WorldTypeEntry deferredChoice(String id,String title) {
        if(id==null || !id.matches("[a-f0-9]{64}") || title==null || title.isBlank()) throw new IllegalArgumentException("Invalid menu choice");
        return new WorldCreationUiState.WorldTypeEntry(Holder.direct(new DeferredPreset(id,title)));
    }
    public static void showSelected(CreateWorldScreen screen) {
        var ui = ui(screen); var s = STATES.get(ui); String id = WorldsmithWorldCreationBridge.selectedPackId(screen);
        if (s == null || id == null || WorldsmithWorldCreationBridge.creationInProgress(screen)) return;
        ((WorldCreationUiStateAccessor)ui).worldsmith$setWorldType(entry(ui, s, id, WorldsmithWorldCreationBridge.selectedPackTitle(screen)));
    }
    public static void restoreAfterPresetRefresh(WorldCreationUiState ui) {
        var s = STATES.get(ui); var screen = s == null ? null : s.owner.get();
        if (screen != null && !s.closed) showSelected(screen);
    }
    /** True cancels native setWorldType: this value is only a future creation choice, not native dimensions. */
    public static boolean select(WorldCreationUiState ui, WorldCreationUiState.WorldTypeEntry entry, boolean refreshing) {
        var s = STATES.get(ui); var screen = s == null ? null : s.owner.get();
        if (screen == null || s.closed || refreshing || WorldsmithWorldCreationBridge.changingTypeInternally(screen)) return false;
        if (WorldsmithWorldCreationBridge.creationInProgress(screen)) { ui.onChanged(); return true; }
        if (entry.preset() != null && entry.preset().value() instanceof DeferredPreset pack) {
            WorldsmithWorldCreationBridge.selectPack(screen, pack.id, pack.title, true); return true;
        }
        String id = managedId(entry.preset());
        if (id != null) {
            Component title = WorldsmithWorldCreationBridge.displayName(entry.preset());
            WorldsmithWorldCreationBridge.selectPack(screen, id, title == null ? id : title.getString(), true); return true;
        }
        WorldsmithWorldCreationBridge.selectNativeWorld(screen);
        return false;
    }
    public static Component name(Holder<WorldPreset> preset) {
        return preset != null && preset.value() instanceof DeferredPreset pack ? Component.literal(pack.title) : null;
    }
    public static String managedId(Holder<WorldPreset> preset) {
        if (preset == null) return null;
        var key = preset.unwrapKey().orElse(null);
        if (key == null || !key.identifier().getNamespace().equals("worldsmith")) return null;
        String path = key.identifier().getPath();
        return path.matches("generated/[a-f0-9]{64}/wasteland") ? path.substring(10, 74) : null;
    }
    public static boolean isManagedPreset(Holder<WorldPreset> preset) { return managedId(preset) != null; }
    public static boolean isLegacyPreset(Holder<WorldPreset> preset) {
        return preset != null && preset.unwrapKey().map(key -> key.identifier().toString().equals("worldsmith:wasteland")).orElse(false);
    }
    private static WorldCreationUiState ui(CreateWorldScreen screen) { return ((CreateWorldScreenAccessor)screen).worldsmith$getUiState(); }
    private static final class State {
        final WeakReference<CreateWorldScreen> owner;
        List<ResourcePackSummary> packs = List.of();
        final Map<String, WorldCreationUiState.WorldTypeEntry> entries = new HashMap<>();
        final Map<String, ResourcePackDetails> details = new HashMap<>();
        boolean loading, closed, warned; long nextRead; String failedDetail;
        State(CreateWorldScreen screen) { owner = new WeakReference<>(screen); }
    }
    private record Catalog(List<ResourcePackSummary> packs, ResourcePackDetails detail) {}
    /** Unique identity per menu item; deliberately has no world-generation data and fails closed if misused. */
    private static final class DeferredPreset extends WorldPreset {
        final String id, title;
        DeferredPreset(String id, String title) { super(Map.of()); this.id = id; this.title = title; }
        @Override public WorldDimensions createWorldDimensions() { throw new IllegalStateException("A menu-only world choice must be prepared before creating dimensions"); }
    }
}
