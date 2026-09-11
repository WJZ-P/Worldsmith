package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.mcp.GenerationCategoryProgress;
import com.wjz.worldsmith.core.mcp.GenerationDrawingProgress;
import com.wjz.worldsmith.core.mcp.GenerationProgressSnapshot;
import com.wjz.worldsmith.core.mcp.GenerationProgressView;
import com.wjz.worldsmith.core.mcp.GenerationSessionSummary;
import com.wjz.worldsmith.core.mcp.GenerationTargetProgress;
import com.wjz.worldsmith.core.mcp.PublicationStatus;
import com.wjz.worldsmith.mixin.client.CreateWorldScreenAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** A genuine Create World tab. It reads bounded cached DTOs; it neither starts authoring nor changes worlds. */
public final class WorldsmithGenerationProgressTab extends GridLayoutTab {
    private static final int TARGET_PAGE_SIZE = 8;
    private static final List<String> MAIN_KINDS = List.of("biome", "structure", "creature", "block", "item", "quest");
    private final CreateWorldScreen screen;
    private final Dashboard dashboard;
    private final Button auto;
    private final Button sessionPrevious;
    private final Button sessionPicker;
    private final Button sessionNext;
    private final Button pause;
    private final Button category;
    private final Button previous;
    private final Button next;
    private final StringWidget pageLabel;
    private WorldsmithGenerationProgressPoller poller;
    private WorldsmithGenerationProgressPoller.Snapshot cached;
    private List<GenerationSessionSummary> sessions = List.of();
    private boolean following = true;
    private String preferredSession;
    private boolean paused;
    private String filter = "all";
    private int page;
    private String restorePageForSession;

    /** Only user choices cross native reinitialization; widgets and publication receipts never do. */
    public record UiState(boolean following, String preferredSession, boolean paused, String filter, int page, String visibleSession) {}

    public UiState uiState() {
        String visibleSession = selectedId(cached);
        return new UiState(following, preferredSession, paused, filter, page,
            visibleSession == null ? restorePageForSession : visibleSession);
    }

    public WorldsmithGenerationProgressTab(CreateWorldScreen screen) {
        this(screen, null);
    }

    public WorldsmithGenerationProgressTab(CreateWorldScreen screen, UiState previousState) {
        super(tr("tab")); this.screen = screen;
        if (previousState != null) {
            following = previousState.following(); preferredSession = previousState.preferredSession();
            paused = previousState.paused(); filter = previousState.filter(); page = previousState.page();
            restorePageForSession = previousState.visibleSession();
        }
        dashboard = layout.addChild(new Dashboard(), 0, 0);
        auto = layout.addChild(button("auto", () -> { following = true; preferredSession = null; ensurePoller(); poller.followActiveSession(); cached = poller.snapshot(); paused = false; resetView(); }), 1, 0);
        sessionPrevious = layout.addChild(Button.builder(Component.literal("<"), ignored -> chooseSession(-1)).tooltip(Tooltip.create(tr("session_previous"))).build(), 2, 0);
        sessionPicker = layout.addChild(Button.builder(tr("choose_session"), ignored -> chooseSession(1)).build(), 3, 0);
        sessionNext = layout.addChild(Button.builder(Component.literal(">"), ignored -> chooseSession(1)).tooltip(Tooltip.create(tr("session_next"))).build(), 4, 0);
        pause = layout.addChild(button("pause", () -> { paused = !paused; updateControls(); updateDashboard(); }), 5, 0);
        category = layout.addChild(button("category", this::nextCategory), 6, 0);
        previous = layout.addChild(Button.builder(Component.literal("<"), ignored -> { page--; updateControls(); updateDashboard(); dashboard.setScrollAmount(0); })
            .tooltip(Tooltip.create(tr("targets_previous"))).build(), 7, 0);
        next = layout.addChild(Button.builder(Component.literal(">"), ignored -> { page++; updateControls(); updateDashboard(); dashboard.setScrollAmount(0); })
            .tooltip(Tooltip.create(tr("targets_next"))).build(), 8, 0);
        pageLabel = layout.addChild(new StringWidget(Component.empty(), Minecraft.getInstance().font), 9, 0);
        updateControls();
    }

    public static void attachTo(CreateWorldScreen screen) {
        var tab = ((WorldsmithProgressTabAccess)screen).worldsmith$getGenerationProgressTab();
        if (tab == null) return;
        tab.ensurePoller();
        // Fabric resets these per-screen events during both initialization and resize.
        ScreenEvents.afterTick(screen).register(ignored -> tab.tick());
        ScreenEvents.remove(screen).register(ignored -> tab.removed());
    }

    private void ensurePoller() {
        if (poller != null) return;
        poller = new WorldsmithGenerationProgressPoller();
        if (following) poller.followActiveSession(); else if (preferredSession != null) poller.selectSession(preferredSession);
    }

    public void removed() {
        String visibleSession = selectedId(cached);
        if (visibleSession != null) restorePageForSession = visibleSession;
        if (poller != null) { poller.close(); poller = null; }
        cached = null; updateDashboard();
    }

    private void tick() {
        if (((CreateWorldScreenAccessor)screen).worldsmith$getTabManager().getCurrentTab() != this) return;
        ensurePoller();
        if (!paused) {
            poller.tick(screen);
            var snapshot = poller.snapshot();
            String before = selectedId(cached), after = selectedId(snapshot);
            cached = snapshot;
            GenerationProgressSnapshot progress = cached == null ? null : cached.progress();
            sessions = progress == null ? List.of() : List.copyOf(progress.getSessions());
            if (!Objects.equals(before, after) && after != null) {
                if (!after.equals(restorePageForSession)) page = 0;
                restorePageForSession = null; dashboard.setScrollAmount(0);
            }
            updateControls();
        }
        updateDashboard();
    }

    private static String selectedId(WorldsmithGenerationProgressPoller.Snapshot snapshot) {
        return snapshot == null || snapshot.progress() == null ? null : snapshot.progress().getSelectedSessionId();
    }
    private GenerationProgressView view() { return cached == null || cached.progress() == null ? null : cached.progress().getView(); }
    private void resetView() { page = 0; restorePageForSession = null; dashboard.setScrollAmount(0); updateControls(); updateDashboard(); }

    private void chooseSession(int step) {
        if (sessions.isEmpty()) return;
        String selected = following ? selectedId(cached) : preferredSession;
        int index = -1;
        for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).getSessionId().equals(selected)) { index = i; break; }
        if (index < 0) index = step > 0 ? 0 : sessions.size() - 1;
        else index = Math.floorMod(index + step, sessions.size());
        following = false; preferredSession = sessions.get(index).getSessionId();
        ensurePoller(); poller.selectSession(preferredSession); cached = poller.snapshot(); paused = false; resetView();
    }

    private List<String> filters() {
        List<String> result = new ArrayList<>(); result.add("all"); result.addAll(MAIN_KINDS);
        var view = view(); if (view != null) for (var entry : view.getCategories()) if (!result.contains(entry.getKind())) result.add(entry.getKind());
        return result;
    }
    private void nextCategory() {
        var filters = filters(); filter = filters.get(Math.floorMod(filters.indexOf(filter) + 1, filters.size()));
        resetView(); updateDashboard();
    }
    private int targetCount() {
        var view = view(); if (view == null) return 0;
        return (int)view.getTargets().stream().filter(target -> filter.equals("all") || filter.equals(target.getKind())).count();
    }
    private int pages() { return Math.max(1, (targetCount() + TARGET_PAGE_SIZE - 1) / TARGET_PAGE_SIZE); }

    private void updateControls() {
        auto.active = !following;
        sessionPrevious.active = !sessions.isEmpty(); sessionPicker.active = !sessions.isEmpty(); sessionNext.active = !sessions.isEmpty();
        String selected = following ? selectedId(cached) : preferredSession;
        var entry = sessions.stream().filter(session -> session.getSessionId().equals(selected)).findFirst().orElse(null);
        Component label = entry == null ? tr(preferredSession == null ? "choose_session" : "session_unavailable")
            : Component.literal(bound(entry.getTitle(), 160) + " · " + shortId(entry.getSessionId()));
        sessionPicker.setMessage(label);
        Component tip = tr("session_hint").copy().append("\n").append(following ? tr("following_auto") : tr("following_manual"));
        for (var session : sessions.stream().limit(8).toList()) tip = tip.copy().append("\n" + shortId(session.getSessionId()) + " · " + bound(session.getTitle(), 100));
        sessionPicker.setTooltip(Tooltip.create(tip));
        pause.setMessage(tr(paused ? "resume" : "pause")); pause.setTooltip(Tooltip.create(tr(paused ? "resume.hint" : "pause.hint")));
        category.setMessage(tr("category_value", kind(filter)));
        boolean hasView = view() != null;
        if (hasView) page = Math.max(0, Math.min(page, pages() - 1));
        previous.active = hasView && page > 0; next.active = hasView && page + 1 < pages();
        pageLabel.setMessage(Component.literal(hasView ? (page + 1) + " / " + pages() : "\u2014"));
    }

    private void updateDashboard() { dashboard.update(cached, paused, following, filter, page); }

    @Override public void doLayout(ScreenRectangle area) {
        int margin = area.width() < 360 ? 8 : 12;
        int width = Math.max(1, Math.min(900, area.width() - margin * 2));
        int left = area.left() + (area.width() - width) / 2, top = area.top() + 7;
        boolean narrow = width < 230;
        int autoWidth = narrow ? 36 : 44, pauseWidth = narrow ? 44 : 64;
        auto.setRectangle(autoWidth, 20, left, top);
        sessionPrevious.visible = sessionNext.visible = !narrow;
        int pickerX = left + autoWidth + 4;
        if (!narrow) { sessionPrevious.setRectangle(20, 20, pickerX, top); pickerX += 24; }
        int pickerWidth = Math.max(1, width - (pickerX - left) - pauseWidth - (narrow ? 4 : 28));
        sessionPicker.setRectangle(pickerWidth, 20, pickerX, top);
        if (!narrow) sessionNext.setRectangle(20, 20, pickerX + pickerWidth + 4, top);
        pause.setRectangle(pauseWidth, 20, left + width - pauseWidth, top);
        int footerY = Math.max(top + 27, area.bottom() - 25);
        int categoryWidth = Math.max(1, Math.min(200, width - 106));
        category.setRectangle(categoryWidth, 20, left, footerY);
        previous.setRectangle(22, 20, left + width - 94, footerY);
        pageLabel.setRectangle(44, 20, left + width - 70, footerY);
        next.setRectangle(22, 20, left + width - 22, footerY);
        dashboard.setRectangle(width, Math.max(1, footerY - top - 33), left, top + 27);
        dashboard.invalidate(); updateDashboard();
    }

    private static Button button(String key, Runnable action) {
        return Button.builder(tr(key), ignored -> action.run()).tooltip(Tooltip.create(tr(key + ".hint"))).build();
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.progress." + key, args); }
    private static String bound(String text, int limit) { if (text == null) return ""; return text.length() <= limit ? text : text.substring(0, limit) + "\u2026"; }
    private static String shortId(String id) { return id == null ? "" : id.substring(0, Math.min(8, id.length())); }
    private static Component kind(String value) {
        return switch (value) {
            case "all", "biome", "structure", "creature", "block", "item", "quest", "feature", "theme", "terrain", "narrative_beat" -> tr("kind." + value);
            default -> Component.literal(bound(value, 64));
        };
    }
    private static Component stage(String value) {
        if (value == null || value.isBlank()) return tr("stage.UNKNOWN");
        return switch (value) {
            case "ARCHIVED", "WAITING_USER", "DESIGN_PLAN", "FROZEN_REPAIR", "AUTHORING", "STANDALONE_ARTIFACT", "NATIVE_COMPLETE", "CORE_SAVED", "READY_FOR_FROZEN_CHECK",
                "RUNNING_TOOL", "HISTORICAL_NATIVE_RECEIPT", "DRAFT", "WAITING_APPROVAL", "QUEUED", "COMPILING", "DRAWING", "VALIDATING", "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED",
                "PUBLISHED", "WAITING_NATIVE_CONTEXT", "NATIVE_CHECK", "RELOADING", "CLIENT_RESOURCES", "NOT_SELECTED" -> tr("stage." + value);
            default -> Component.literal(bound(value, 64));
        };
    }

    /** Layout is prepared on cache/width changes. Render only draws these bounded primitives. */
    private static final class Dashboard extends AbstractScrollArea {
        private static final int INK = 0xFFE4EDF7, MUTED = 0xFF9BAFC5, CYAN = 0xFF77D5DC, GOLD = 0xFFF0CE87, RED = 0xFFFFA8A0, GREEN = 0xFF9DD8B1;
        private final Font font = Minecraft.getInstance().font;
        private final List<Fill> fills = new ArrayList<>();
        private final List<Text> texts = new ArrayList<>();
        private final List<Hint> hints = new ArrayList<>();
        private WorldsmithGenerationProgressPoller.Snapshot oldSnapshot;
        private boolean oldPaused, oldFollowing;
        private String oldFilter;
        private int oldPage = -1;
        private int contentHeight;
        private int textWidth;
        private int cursor;

        Dashboard() { super(0, 0, 100, 100, tr("tab"), AbstractScrollArea.defaultSettings(22)); }
        void invalidate() { oldFilter = null; }
        void update(WorldsmithGenerationProgressPoller.Snapshot snapshot, boolean paused, boolean following, String filter, int page) {
            if (sameDisplay(snapshot, oldSnapshot) && paused == oldPaused && following == oldFollowing && filter.equals(oldFilter) && page == oldPage) {
                // Keep the latest inner DTO references, so unchanged full views are not deep-compared every tick.
                oldSnapshot = snapshot; return;
            }
            oldSnapshot = snapshot; oldPaused = paused; oldFollowing = following; oldFilter = filter; oldPage = page;
            fills.clear(); texts.clear(); hints.clear(); cursor = 12; textWidth = Math.max(24, width - 32);
            var progress = snapshot == null ? null : snapshot.progress();
            var view = progress == null ? null : progress.getView();
            if (paused) banner(tr("paused"), GOLD);
            else if (snapshot == null || !snapshot.connected()) banner(tr("disconnected"), RED);
            else if (snapshot.refreshing()) banner(tr("refreshing"), CYAN);
            else banner(tr(following ? "following_auto" : "following_manual"), MUTED);
            if (snapshot != null && snapshot.error() != null && !snapshot.error().isBlank()) paragraph(Component.literal(bound(snapshot.error(), 512)), RED, 3);
            if (view == null) {
                paragraph(tr("empty_title").copy().withStyle(ChatFormatting.BOLD), INK, 2);
                paragraph(tr(following ? "empty_auto" : "empty_manual"), MUTED, 5);
                paragraph(tr("not_an_ai_runner"), GOLD, 4);
                finish(); return;
            }
            paragraph(Component.literal(bound(view.getTitle(), 160)).withStyle(ChatFormatting.BOLD), INK, 2);
            paragraph(tr("session_line", shortId(view.getSessionId()), view.getRevision(), stage(view.getStage())), CYAN, 2);
            if (progress.getToolRunning()) paragraph(tr("running_tool", bound(progress.getLastTool(), 96)), GOLD, 2);
            else if (progress.getLastToolError() != null && !progress.getLastToolError().isBlank()) paragraph(tr("tool_failed", bound(progress.getLastToolError(), 384)), RED, 3);
            if (!view.getPrompt().isBlank()) paragraph(Component.literal(bound(view.getPrompt(), 256)), MUTED, 2);
            int total = view.getTotalPlannedTargets() == null ? 0 : view.getTotalPlannedTargets();
            Component overall = view.getPlanPresent() && view.getTotalPlannedTargets() != null
                ? tr("draft_progress", view.getDeclaredTargets(), total) : tr("no_plan_progress");
            paragraph(overall, INK, 2);
            bar(12, cursor, textWidth, 7, total > 0 ? (double)view.getDeclaredTargets() / total : 0.0, total > 0 ? CYAN : 0xFF40536A);
            cursor += 13;
            paragraph(tr(total > 0 ? "progress_boundary" : "no_plan_boundary"), MUTED, 2);
            PublicationStatus nativeStatus = snapshot.nativeStatus();
            String nativeStage = nativeStatus == null ? "WAITING_NATIVE_CONTEXT" : nativeStatus.getStage();
            paragraph(tr("native_line", stage(nativeStage)), nativeStage.equals("PUBLISHED") ? GREEN : GOLD, 2);
            if (nativeStatus != null && !nativeStatus.getMessage().isBlank()) paragraph(Component.literal(bound(nativeStatus.getMessage(), 256)), MUTED, 2);
            else paragraph(tr("native_boundary"), MUTED, 2);
            cards(view);
            section(tr("targets_heading", kind(filter)));
            List<GenerationTargetProgress> targets = view.getTargets().stream().filter(target -> filter.equals("all") || filter.equals(target.getKind())).toList();
            int from = Math.min(targets.size(), page * TARGET_PAGE_SIZE), to = Math.min(targets.size(), from + TARGET_PAGE_SIZE);
            if (targets.isEmpty()) paragraph(tr(view.getPlanPresent() ? "no_targets" : "targets_need_plan"), MUTED, 3);
            for (int i = from; i < to; i++) target(targets.get(i));
            if (view.getTargetsTruncated()) paragraph(tr("targets_truncated"), GOLD, 2);
            section(tr("jobs_heading", view.getJobs().size()));
            List<GenerationDrawingProgress> jobs = view.getJobs().stream().sorted(Comparator.comparingInt(job -> job.getStage().equals("SUCCEEDED") ? 1 : 0)).toList();
            if (jobs.isEmpty()) paragraph(tr("no_jobs"), MUTED, 2);
            for (var job : jobs.stream().limit(6).toList()) {
                int color = job.getNeedsApproval() ? GOLD : job.getStage().equals("FAILED") || job.getStage().equals("INTERRUPTED") ? RED : job.getStage().equals("SUCCEEDED") ? GREEN : CYAN;
                paragraph(Component.literal(bound(job.getName(), 100)).append(" · ").append(stage(job.getStage())), color, 2);
                if (job.getNeedsApproval()) paragraph(tr("approval_hint"), GOLD, 2);
                else if (job.getDetail() != null && !job.getDetail().isBlank()) paragraph(Component.literal(bound(job.getDetail(), 256)), MUTED, 2);
            }
            if (jobs.size() > 6 || view.getJobsTruncated()) paragraph(tr("jobs_more"), MUTED, 2);
            section(tr("next_heading"));
            paragraph(Component.literal(bound(view.getNextInstruction(), 768)), view.getRequiresUserAction() ? GOLD : INK, 4);
            paragraph(Component.literal(bound(view.getNextTool(), 128)), CYAN, 2);
            for (var issue : view.getIssues().stream().limit(4).toList()) {
                paragraph(Component.literal(bound(issue.getCode(), 80)).append(" · ").append(Component.literal(bound(issue.getMessage(), 512))),
                    issue.getPriority() < 0 ? RED : issue.getRequiresUserAction() ? GOLD : MUTED, 3);
            }
            if (view.getIssues().size() > 4 || view.getIssuesTruncated()) paragraph(tr("issues_more"), MUTED, 2);
            paragraph(tr("verification_boundary"), MUTED, 3);
            finish();
        }

        private static boolean sameDisplay(WorldsmithGenerationProgressPoller.Snapshot left, WorldsmithGenerationProgressPoller.Snapshot right) {
            if (left == right) return true; if (left == null || right == null) return false;
            return left.epoch() == right.epoch() && left.connected() == right.connected() && left.refreshing() == right.refreshing()
                && Objects.equals(left.error(), right.error()) && Objects.equals(left.progress(), right.progress()) && Objects.equals(left.nativeStatus(), right.nativeStatus());
        }
        private void finish() { contentHeight = cursor + 12; refreshScrollAmount(); }
        private void section(Component label) { cursor += 8; fill(12, cursor, textWidth, 1, 0xFF34465C); cursor += 10; paragraph(label.copy().withStyle(ChatFormatting.BOLD), INK, 2); }
        private void banner(Component label, int color) { paragraph(label, color, 2); cursor += 2; }
        private void paragraph(Component component, int color, int maxLines) {
            int start = cursor; var lines = font.split(component, textWidth); int count = Math.min(lines.size(), maxLines);
            for (int i = 0; i < count; i++) { texts.add(new Text(12, cursor, lines.get(i), color)); cursor += 12; }
            if (lines.size() > maxLines) { hints.add(new Hint(12, start, textWidth, Math.max(12, cursor - start), component)); texts.add(new Text(width - 29, cursor - 12, Component.literal("\u2026").getVisualOrderText(), color)); }
            cursor += 4;
        }
        private void cards(GenerationProgressView view) {
            int columns = textWidth >= 450 ? 3 : 2, gap = 6, cardWidth = (textWidth - gap * (columns - 1)) / columns;
            int start = cursor + 4, cardHeight = 49;
            for (int i = 0; i < MAIN_KINDS.size(); i++) {
                String kind = MAIN_KINDS.get(i);
                GenerationCategoryProgress value = view.getCategories().stream().filter(entry -> entry.getKind().equals(kind)).findFirst().orElse(null);
                int x = 12 + (i % columns) * (cardWidth + gap), y = start + (i / columns) * (cardHeight + gap);
                fill(x, y, cardWidth, cardHeight, 0xFF233348); fill(x, y, 2, cardHeight, CYAN);
                text(x + 8, y + 7, kind(kind), MUTED, cardWidth - 16);
                int declared = value == null ? 0 : value.getMatchedDeclared(); Integer planned = value == null ? null : value.getPlanned();
                Component label = planned == null ? tr("card_declared", value == null ? 0 : value.getDeclaredTotal()) : tr("card_progress", declared, planned);
                text(x + 8, y + 22, label, INK, cardWidth - 16);
                bar(x + 8, y + 39, cardWidth - 16, 3, planned == null || planned <= 0 ? 0.0 : (double)declared / planned, CYAN);
                Component hint = kind(kind).copy().append("\n").append(label).append("\n").append(tr("card_hint", value == null ? 0 : value.getExtraDefinitions()));
                hints.add(new Hint(x, y, cardWidth, cardHeight, hint));
            }
            cursor = start + ((MAIN_KINDS.size() + columns - 1) / columns) * (cardHeight + gap);
        }
        private void target(GenerationTargetProgress target) {
            int y = cursor; String state = target.getState().name();
            int color = switch (state) { case "FROZEN" -> GREEN; case "DECLARED" -> CYAN; case "REPAIR" -> RED; case "NEEDS_ASSET" -> GOLD; default -> MUTED; };
            fill(12, y, textWidth, 35, 0xFF1E2C3D);
            icon(20, y + 10, state, color);
            int statusWidth = Math.min(76, textWidth / 3);
            text(35, y + 6, Component.literal(bound(target.getName(), 160)), INK, textWidth - statusWidth - 35);
            text(35, y + 20, Component.literal(bound(target.getId(), 96)), MUTED, textWidth - statusWidth - 35);
            text(12 + textWidth - statusWidth, y + 12, tr("target." + state.toLowerCase(Locale.ROOT)), color, statusWidth - 6);
            Component detail = kind(target.getKind()).copy().append(" · ").append(Component.literal(bound(target.getName(), 160)))
                .append("\n").append(Component.literal(bound(target.getPurpose(), 768)));
            if (target.getDetail() != null) detail = detail.copy().append("\n").append(Component.literal(bound(target.getDetail(), 384)));
            if (!target.getDiagnosticCodes().isEmpty()) detail = detail.copy().append("\n").append(Component.literal(bound(String.join(", ", target.getDiagnosticCodes()), 256)));
            hints.add(new Hint(12, y, textWidth, 35, detail)); cursor += 40;
        }
        private void icon(int x, int y, String state, int color) {
            if (state.equals("FROZEN")) { fill(x, y + 4, 3, 3, color); fill(x + 3, y + 2, 3, 3, color); fill(x + 6, y, 2, 3, color); }
            else if (state.equals("REPAIR")) { fill(x + 2, y, 3, 5, color); fill(x + 2, y + 7, 3, 2, color); }
            else if (state.equals("DECLARED")) fill(x, y, 8, 8, color);
            else { fill(x, y, 8, 1, color); fill(x, y + 7, 8, 1, color); fill(x, y, 1, 8, color); fill(x + 7, y, 1, 8, color); }
        }
        private void text(int x, int y, Component component, int color, int availableWidth) {
            String value = font.plainSubstrByWidth(component.getString(), Math.max(8, availableWidth));
            texts.add(new Text(x, y, Component.literal(value).getVisualOrderText(), color));
        }
        private void bar(int x, int y, int width, int height, double ratio, int color) {
            fill(x, y, width, height, 0xFF394B61); int done = (int)Math.round(Math.max(0.0, Math.min(1.0, ratio)) * width);
            if (done > 0) fill(x, y, done, height, color);
        }
        private void fill(int x, int y, int width, int height, int color) { fills.add(new Fill(x, y, width, height, color)); }
        @Override protected int contentHeight() { return contentHeight; }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xF1162130);
            graphics.outline(getX(), getY(), width, height, 0xFF344B64);
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 7, getBottom() - 1);
            int offset = getY() - (int)scrollAmount();
            for (var rect : fills) if (offset + rect.y + rect.height >= getY() && offset + rect.y < getBottom())
                graphics.fill(getX() + rect.x, offset + rect.y, getX() + rect.x + rect.width, offset + rect.y + rect.height, rect.color);
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
            return super.keyPressed(event);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, tr("tab"));
            output.add(NarratedElementType.USAGE, tr("scroll_hint"));
        }
        private record Fill(int x, int y, int width, int height, int color) {}
        private record Text(int x, int y, FormattedCharSequence value, int color) {}
        private record Hint(int x, int y, int width, int height, Component value) {}
    }
}
