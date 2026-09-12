package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.mcp.GenerationCategoryProgress;
import com.wjz.worldsmith.core.mcp.GenerationDrawingProgress;
import com.wjz.worldsmith.core.mcp.GenerationProgressSnapshot;
import com.wjz.worldsmith.core.mcp.GenerationProgressView;
import com.wjz.worldsmith.core.mcp.GenerationSessionSummary;
import com.wjz.worldsmith.core.mcp.GenerationTargetProgress;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import java.util.*;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** The native Worldsmith tab: fixed-layout category cards, fed only by actual cached authoring state. */
public final class WorldsmithGenerationProgressTab extends GridLayoutTab {
    private static final List<String> MAIN_KINDS = List.of("biome", "structure", "creature", "block", "item", "quest");
    private final CreateWorldScreen screen;
    private final Dashboard dashboard;
    private final Button sessionPrevious, sessionPicker, sessionNext;
    private WorldsmithGenerationProgressPoller poller;
    private WorldsmithGenerationProgressPoller.Snapshot cached;
    private List<GenerationSessionSummary> sessions = List.of();
    private boolean following = true;
    private String preferredSession, restoreSession;
    private double restoreScroll;
    private int rowLeft, rowTop, rowWidth;

    public record UiState(boolean following, String preferredSession, double scroll, String visibleSession) {}
    public UiState uiState() {
        String id = selectedId(cached);
        return new UiState(following, preferredSession, dashboard.scrollAmount(), id == null ? restoreSession : id);
    }
    public WorldsmithGenerationProgressTab(CreateWorldScreen screen) { this(screen, null); }
    public WorldsmithGenerationProgressTab(CreateWorldScreen screen, UiState previousState) {
        super(tr("tab")); this.screen = screen;
        if (previousState != null) {
            following = previousState.following(); preferredSession = previousState.preferredSession();
            restoreSession = previousState.visibleSession(); restoreScroll = previousState.scroll();
        }
        sessionPrevious = layout.addChild(Button.builder(Component.literal("<"), ignored -> chooseSession(-1))
                .tooltip(Tooltip.create(tr("world_previous"))).build(), 0, 0);
        sessionPicker = layout.addChild(Button.builder(tr("world_waiting"), ignored -> chooseSession(1)).build(), 1, 0);
        sessionNext = layout.addChild(Button.builder(Component.literal(">"), ignored -> chooseSession(1))
                .tooltip(Tooltip.create(tr("world_next"))).build(), 2, 0);
        dashboard = layout.addChild(new Dashboard(), 3, 0);
        updateControls();
    }

    public static void attachTo(CreateWorldScreen screen) {
        var tab = ((WorldsmithProgressTabAccess)screen).worldsmith$getGenerationProgressTab();
        if (tab == null) return;
        tab.ensurePoller();
        ScreenEvents.afterTick(screen).register(ignored -> tab.tick());
        ScreenEvents.remove(screen).register(ignored -> tab.removed());
    }
    private void ensurePoller() {
        if (poller != null) return;
        poller = new WorldsmithGenerationProgressPoller();
        if (following) poller.followActiveSession(); else if (preferredSession != null) poller.selectSession(preferredSession);
    }
    public void removed() {
        String id = selectedId(cached);
        if (id != null) { restoreSession = id; restoreScroll = dashboard.scrollAmount(); }
        if (poller != null) { poller.close(); poller = null; }
        cached = null;
    }
    private void tick() {
        if (((CreateWorldScreenAccessor)screen).worldsmith$getTabManager().getCurrentTab() != this) return;
        ensurePoller(); poller.tick(screen);
        var snapshot = poller.snapshot();
        String before = selectedId(cached), after = selectedId(snapshot);
        cached = snapshot;
        var progress = snapshot == null ? null : snapshot.progress();
        sessions = progress == null ? List.of() : List.copyOf(progress.getSessions());
        updateControls(); dashboard.update(cached);
        if (after != null && !Objects.equals(before, after)) {
            dashboard.setScrollAmount(after.equals(restoreSession) ? restoreScroll : 0);
            restoreSession = null; restoreScroll = 0;
        }
    }
    private static String selectedId(WorldsmithGenerationProgressPoller.Snapshot snapshot) {
        return snapshot == null || snapshot.progress() == null ? null : snapshot.progress().getSelectedSessionId();
    }
    private void chooseSession(int step) {
        if (sessions.isEmpty()) return;
        String selected = following ? selectedId(cached) : preferredSession;
        int index = -1;
        for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).getSessionId().equals(selected)) { index = i; break; }
        index = index < 0 ? (step > 0 ? 0 : sessions.size() - 1) : Math.floorMod(index + step, sessions.size());
        String id = sessions.get(index).getSessionId();
        String active = cached == null || cached.progress() == null ? null : cached.progress().getDefaultSessionId();
        following = id.equals(active); preferredSession = following ? null : id;
        ensurePoller();
        if (following) poller.followActiveSession(); else poller.selectSession(id);
        cached = poller.snapshot(); restoreSession = null; restoreScroll = 0;
        dashboard.setScrollAmount(0); updateControls(); dashboard.update(cached);
    }
    private void updateControls() {
        String selected = following ? selectedId(cached) : preferredSession;
        var entry = sessions.stream().filter(session -> session.getSessionId().equals(selected)).findFirst().orElse(null);
        sessionPicker.setMessage(entry == null ? tr("world_waiting") : Component.literal(bound(entry.getTitle(), 160)));
        sessionPicker.active = !sessions.isEmpty();
        Component tooltip = entry == null ? tr("world_picker_hint") : Component.literal(bound(entry.getPrompt(), 384));
        sessionPicker.setTooltip(Tooltip.create(tooltip));
        layoutSessionControls();
    }
    private void layoutSessionControls() {
        boolean multiple = sessions.size() > 1 && rowWidth >= 230;
        sessionPrevious.visible = sessionNext.visible = multiple;
        sessionPrevious.active = sessionNext.active = multiple;
        sessionPrevious.setRectangle(20, 20, rowLeft, rowTop);
        sessionNext.setRectangle(20, 20, rowLeft + Math.max(0, rowWidth - 20), rowTop);
        sessionPicker.setRectangle(Math.max(1, rowWidth - (multiple ? 48 : 0)), 20, rowLeft + (multiple ? 24 : 0), rowTop);
    }
    @Override public void doLayout(ScreenRectangle area) {
        int margin = area.width() < 360 ? 8 : 12;
        rowWidth = Math.max(1, Math.min(960, area.width() - margin * 2));
        rowLeft = area.left() + (area.width() - rowWidth) / 2; rowTop = area.top() + 7;
        layoutSessionControls();
        dashboard.setRectangle(rowWidth, Math.max(1, area.bottom() - rowTop - 33), rowLeft, rowTop + 27);
        dashboard.invalidate(); dashboard.update(cached);
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.progress." + key, args); }
    private static String bound(String text, int limit) { if (text == null) return ""; return text.length() <= limit ? text : text.substring(0, limit) + "…"; }
    private static Component kind(String key) {
        return switch (key) {
            case "biome", "structure", "creature", "block", "item", "quest", "feature", "theme", "terrain", "narrative_beat", "anchor", "blueprint" -> tr("kind." + key);
            default -> Component.literal(bound(key, 48));
        };
    }

    private static final class Dashboard extends AbstractScrollArea {
        private static final int INK=0xFFF0EBDD, MUTED=0xFFAEB8C2, DIM=0xFF81909A, GREEN=0xFFA4C49B, GOLD=0xFFE0C18C, RED=0xFFEBA89B;
        private static final int CARD_HEIGHT=152, GAP=8;
        private static final Set<String> LIVE_JOBS=Set.of("WAITING_APPROVAL", "QUEUED", "COMPILING", "DRAWING", "VALIDATING");
        private final Font font = Minecraft.getInstance().font;
        private final List<Fill> fills = new ArrayList<>();
        private final List<Text> texts = new ArrayList<>();
        private final List<Hint> hints = new ArrayList<>();
        private final List<Icon> icons = new ArrayList<>();
        private final Map<String, String> targetStates = new HashMap<>(), recentTarget = new HashMap<>();
        private WorldsmithGenerationProgressPoller.Snapshot oldSnapshot;
        private String contentSession;
        private boolean invalid = true;
        private int contentHeight;

        Dashboard() { super(0, 0, 100, 100, tr("tab"), AbstractScrollArea.defaultSettings(24)); }
        void invalidate() { invalid = true; }
        void update(WorldsmithGenerationProgressPoller.Snapshot snapshot) {
            if (!invalid && sameDisplay(snapshot, oldSnapshot)) { oldSnapshot = snapshot; return; }
            oldSnapshot = snapshot; invalid = false;
            fills.clear(); texts.clear(); hints.clear(); icons.clear();
            var progress = snapshot == null ? null : snapshot.progress();
            var view = progress == null ? null : progress.getView();
            updateRecentTargets(view);
            int inner = Math.max(24, width - 28);
            Component overall = view == null || view.getTotalPlannedTargets() == null ? tr("overview_waiting")
                    : tr("overview_count", view.getDeclaredTargets(), view.getTotalPlannedTargets());
            text(12, 12, overall, INK, Math.max(30, inner - 102));
            text(Math.max(12, width - 108), 12, phase(snapshot, view), MUTED, 92);
            int columns = inner >= 570 ? 3 : inner >= 330 ? 2 : 1;
            int cardWidth = Math.max(28, (inner - GAP * (columns - 1)) / columns);
            var kinds = new ArrayList<>(MAIN_KINDS);
            if (view != null) for (var category : view.getCategories()) {
                if (!kinds.contains(category.getKind()) && ((category.getPlanned() != null && category.getPlanned() > 0) || category.getDeclaredTotal() > 0)) kinds.add(category.getKind());
            }
            for (int i = 0; i < kinds.size(); i++) card(view, kinds.get(i), 12 + (i % columns) * (cardWidth + GAP), 34 + (i / columns) * (CARD_HEIGHT + GAP), cardWidth);
            int bottom = 34 + ((kinds.size() + columns - 1) / columns) * (CARD_HEIGHT + GAP);
            if (snapshot != null && snapshot.error() != null && !snapshot.error().isBlank()) {
                wrapped(12, bottom + 2, tr("feed_delayed"), RED, inner, 2);
                hints.add(new Hint(12, bottom, inner, 28, Component.literal(bound(snapshot.error(), 256)))); bottom += 32;
            } else if (progress != null && progress.getLastToolError() != null && !progress.getLastToolError().isBlank()) {
                wrapped(12, bottom + 2, tr("attention_needed"), RED, inner, 2);
                hints.add(new Hint(12, bottom, inner, 28, Component.literal(bound(progress.getLastToolError(), 256)))); bottom += 32;
            }
            contentHeight = bottom + 4; refreshScrollAmount();
        }
        private void updateRecentTargets(GenerationProgressView view) {
            String id = view == null ? null : view.getSessionId();
            if (!Objects.equals(id, contentSession)) { targetStates.clear(); recentTarget.clear(); contentSession = id; }
            if (view == null) return;
            var live = new HashSet<String>();
            for (var target : view.getTargets()) {
                String key = target.getKind() + "/" + target.getId(), state = target.getState().name(); live.add(key);
                String before = targetStates.put(key, state);
                if (!state.equals(before) && (before != null || !state.equals("MISSING"))) recentTarget.put(target.getKind(), target.getId());
            }
            targetStates.keySet().retainAll(live);
        }
        private static Component phase(WorldsmithGenerationProgressPoller.Snapshot snapshot, GenerationProgressView view) {
            if (view == null) return tr("phase.waiting");
            if (snapshot.nativeStatus() != null && snapshot.nativeStatus().getStage().equals("PUBLISHED")) return tr("phase.ready");
            if (snapshot.nativeStatus() != null) {
                String nativeStage = snapshot.nativeStatus().getStage();
                if (nativeStage.equals("WAITING_ACTIVATION") || nativeStage.equals("NOT_SELECTED")) return tr("phase.select_world");
                if (nativeStage.equals("NATIVE_DATA_RELOAD") || nativeStage.equals("CLIENT_RESOURCES") || nativeStage.equals("RELOADING")) return tr("phase.loading_world");
            }
            if (view.getJobs().stream().anyMatch(GenerationDrawingProgress::getNeedsApproval)) return tr("phase.confirm");
            return switch (view.getStage()) {
                case "DESIGN_PLAN" -> tr("phase.planning");
                case "FROZEN_REPAIR" -> tr("phase.refining");
                case "CORE_SAVED", "NATIVE_COMPLETE" -> tr("phase.saved");
                case "READY_FOR_FROZEN_CHECK" -> tr("phase.finishing");
                default -> tr("phase.creating");
            };
        }
        private void card(GenerationProgressView view, String category, int x, int y, int w) {
            GenerationCategoryProgress counts = view == null ? null : view.getCategories().stream().filter(c -> c.getKind().equals(category)).findFirst().orElse(null);
            var targets = view == null ? List.<GenerationTargetProgress>of() : view.getTargets().stream().filter(t -> t.getKind().equals(category)).toList();
            var job = liveJob(view, category);
            GenerationTargetProgress focus = null;
            if (job != null) focus = targets.stream().filter(t -> t.getJobIds().contains(job.getJobId()) || job.getStructureIds().contains(t.getId())
                    || t.getId().equals(job.getName()) || t.getName().equals(job.getName())).findFirst().orElse(null);
            else {
                focus = targets.stream().filter(t -> t.getState().name().equals("REPAIR")).findFirst().orElse(null);
                if (focus == null) focus = targets.stream().filter(t -> t.getState().name().equals("NEEDS_ASSET")).findFirst().orElse(null);
                if (focus == null) focus = targets.stream().filter(t -> t.getId().equals(recentTarget.get(category))).findFirst().orElse(null);
                if (focus == null) focus = targets.stream().filter(t -> t.getState().name().equals("NEEDS_ASSET") || t.getState().name().equals("MISSING")).findFirst().orElse(targets.isEmpty() ? null : targets.getLast());
            }
            Integer planned = counts == null ? null : counts.getPlanned();
            int done = counts == null ? 0 : planned == null ? counts.getDeclaredTotal() : counts.getMatchedDeclared();
            String count = counts == null ? "— / —" : done + " / " + (planned == null ? "—" : planned);
            int color = job != null ? GOLD : focus != null && focus.getState().name().equals("REPAIR") ? RED
                    : focus != null && focus.getState().name().equals("NEEDS_ASSET") ? GOLD : planned != null && planned > 0 && done >= planned ? GREEN : 0xFF8EB9B5;
            fill(x, y, w, CARD_HEIGHT, 0xE9283036); outline(x, y, w, CARD_HEIGHT, 0xFF46535B); fill(x + 1, y + 1, 2, CARD_HEIGHT - 2, color);
            icons.add(new Icon(x + 10, y + 10, categoryIcon(category)));
            int countWidth = font.width(count);
            text(x + 32, y + 13, kind(category), INK, Math.max(18, w - countWidth - 51));
            text(x + w - countWidth - 10, y + 13, Component.literal(count), color, countWidth + 1);
            fill(x + 10, y + 34, Math.max(1, w - 20), 4, 0xFF1D2429);
            if (planned != null && planned > 0 && done > 0) fill(x + 10, y + 34, (int)Math.round((w - 20) * Math.min(1.0, (double)done / planned)), 4, color);
            Component activity = activity(job, focus, counts);
            wrapped(x + 10, y + 47, activity, color, w - 20, 2);
            String prompt = focus == null ? "" : bound(focus.getPurpose(), 2048);
            boolean worldPrompt = prompt.isBlank() && view != null && !view.getPrompt().isBlank();
            if (worldPrompt) prompt = bound(view.getPrompt(), 2048);
            text(x + 10, y + 77, tr(worldPrompt ? "card.world_prompt" : "card.prompt"), DIM, w - 20);
            Component brief = prompt.isBlank() ? tr("card.no_prompt") : Component.literal(prompt);
            wrapped(x + 10, y + 91, brief, MUTED, w - 20, 3);
            Component remaining = planned == null ? tr("card.waiting_plan") : planned == 0 ? tr("card.not_planned")
                    : done >= planned ? tr("card.written", done) : tr("card.remaining", Math.max(0, planned - done));
            text(x + 10, y + CARD_HEIGHT - 14, remaining, DIM, w - 20);
            Component hint = kind(category).copy().append("  " + count).append("\n").append(activity);
            if (!prompt.isBlank()) hint = hint.copy().append("\n\n").append(tr(worldPrompt ? "card.world_prompt" : "card.prompt")).append("\n").append(bound(prompt, 256));
            hints.add(new Hint(x, y, w, CARD_HEIGHT, hint));
        }
        private static GenerationDrawingProgress liveJob(GenerationProgressView view, String category) {
            if (view == null || !(category.equals("structure") || category.equals("blueprint"))) return null;
            return view.getJobs().stream().filter(job -> LIVE_JOBS.contains(job.getStage())).findFirst().orElse(null);
        }
        private static Component activity(GenerationDrawingProgress job, GenerationTargetProgress focus, GenerationCategoryProgress counts) {
            if (job != null) {
                String name = bound(focus == null ? job.getName() : focus.getName(), 160);
                return tr(job.getNeedsApproval() ? "card.confirm" : job.getStage().equals("QUEUED") ? "card.queued" : "card.generating", name);
            }
            if (focus != null) return tr(switch (focus.getState().name()) {
                case "REPAIR" -> "card.repair";
                case "NEEDS_ASSET" -> "card.needs_asset";
                case "FROZEN" -> "card.saved";
                case "DECLARED" -> "card.generated";
                default -> "card.pending";
            }, bound(focus.getName(), 160));
            if (counts != null && counts.getDeclaredTotal() > 0) return tr("card.existing", counts.getDeclaredTotal());
            return tr(counts != null && counts.getPlanned() != null && counts.getPlanned() == 0 ? "card.not_planned" : "card.waiting");
        }
        private static IconArt categoryIcon(String category) {
            // UI pictograms use flat vanilla textures, not the off-screen 3D item-atlas renderer.
            String texture = switch (category) {
                case "biome" -> "block/grass_block_side";
                case "structure" -> "block/bricks";
                case "creature" -> "item/sheep_spawn_egg";
                case "block" -> "block/oak_log";
                case "item" -> "item/amethyst_shard";
                case "quest", "narrative_beat" -> "item/writable_book";
                case "feature" -> "block/oak_sapling";
                case "terrain" -> "block/stone";
                case "anchor" -> "item/compass_00";
                default -> "item/map";
            };
            return new IconArt(Identifier.withDefaultNamespace("textures/" + texture + ".png"),
                    category.equals("biome") ? Identifier.withDefaultNamespace("textures/block/grass_block_side_overlay.png") : null);
        }
        /** Refresh-in-flight is deliberately not presentation state. It must not replace headers or move cards. */
        private static boolean sameDisplay(WorldsmithGenerationProgressPoller.Snapshot a, WorldsmithGenerationProgressPoller.Snapshot b) {
            if (a == b) return true; if (a == null || b == null) return false;
            var av = a.progress() == null ? null : a.progress().getView();
            var bv = b.progress() == null ? null : b.progress().getView();
            var ae = a.progress() == null ? null : a.progress().getLastToolError();
            var be = b.progress() == null ? null : b.progress().getLastToolError();
            return a.epoch() == b.epoch() && a.connected() == b.connected() && Objects.equals(av, bv)
                    && Objects.equals(a.error(), b.error()) && Objects.equals(ae, be) && Objects.equals(a.nativeStatus(), b.nativeStatus());
        }
        private void text(int x, int y, Component value, int color, int available) {
            texts.add(new Text(x, y, Component.literal(font.plainSubstrByWidth(value.getString(), Math.max(8, available))).getVisualOrderText(), color));
        }
        private void wrapped(int x, int y, Component value, int color, int available, int maximumLines) {
            var lines = font.split(value, Math.max(20, available));
            for (int i = 0; i < Math.min(maximumLines, lines.size()); i++) texts.add(new Text(x, y + i * 11, lines.get(i), color));
            if (lines.size() > maximumLines) text(x + Math.max(0, available - 9), y + (maximumLines - 1) * 11, Component.literal("…"), color, 9);
        }
        private void fill(int x, int y, int w, int h, int color) { fills.add(new Fill(x, y, w, h, color)); }
        private void outline(int x, int y, int w, int h, int color) {
            fill(x, y, w, 1, color); fill(x, y + h - 1, w, 1, color); fill(x, y, 1, h, color); fill(x + w - 1, y, 1, h, color);
        }
        @Override protected int contentHeight() { return contentHeight; }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xC51B2126);
            graphics.outline(getX(), getY(), width, height, 0xFF414D55);
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 7, getBottom() - 1);
            int offset = getY() - (int)scrollAmount();
            for (var rect : fills) if (offset + rect.y + rect.height >= getY() && offset + rect.y < getBottom())
                graphics.fill(getX() + rect.x, offset + rect.y, getX() + rect.x + rect.width, offset + rect.y + rect.height, rect.color);
            // Explicit strata keep the icon quads above card fills, with text and scroll chrome above them.
            graphics.nextStratum();
            for (var icon : icons) if (offset + icon.y + 16 >= getY() && offset + icon.y < getBottom()) {
                int x = getX() + icon.x, y = offset + icon.y;
                graphics.blit(icon.art.texture, x, y, x + 16, y + 16, 0, 1, 0, 1);
                if (icon.art.overlay != null) graphics.blit(RenderPipelines.GUI_TEXTURED, icon.art.overlay,
                        x, y, 0, 0, 16, 16, 16, 16, 0xFF8BBE62);
            }
            graphics.nextStratum();
            for (var text : texts) if (offset + text.y + 11 >= getY() && offset + text.y < getBottom()) graphics.text(font, text.value, getX() + text.x, offset + text.y, text.color);
            graphics.disableScissor(); extractScrollbar(graphics, mouseX, mouseY);
            if (mouseX >= getX() && mouseX < getRight() - 7 && mouseY >= getY() && mouseY < getBottom()) {
                for (var hint : hints) if (mouseX >= getX() + hint.x && mouseX < getX() + hint.x + hint.width && mouseY >= offset + hint.y && mouseY < offset + hint.y + hint.height) {
                    graphics.setTooltipForNextFrame(hint.value, mouseX, mouseY); break;
                }
            }
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                setScrollAmount(scrollAmount() + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, height - 20)); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
                setScrollAmount(event.key() == GLFW.GLFW_KEY_HOME ? 0 : contentHeight); return true;
            }
            return super.keyPressed(event);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, tr("tab")); output.add(NarratedElementType.USAGE, tr("scroll_hint"));
        }
        private record Fill(int x, int y, int width, int height, int color) {}
        private record Text(int x, int y, FormattedCharSequence value, int color) {}
        private record Hint(int x, int y, int width, int height, Component value) {}
        private record IconArt(Identifier texture, Identifier overlay) {}
        private record Icon(int x, int y, IconArt art) {}
    }
}
