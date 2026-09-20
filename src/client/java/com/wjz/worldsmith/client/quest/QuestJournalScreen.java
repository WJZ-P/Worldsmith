package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.quest.QuestJournalLayout;
import com.wjz.worldsmith.client.story.StoryJournalClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Small, non-pausing journal. All displayed completion/status values come from the server snapshot. */
public final class QuestJournalScreen extends Screen {
    private final String worldScope;
    private final ClientPacketListener worldConnection;
    private List<QuestProtocol.Entry> displayed = List.of();
    private String selectedId;
    private int page;
    private int pageSize;
    private int detailScroll;
    private int maximumDetailScroll;
    private int listWidth;
    private int detailX;
    private int detailWidth;
    private int bodyTop;
    private int bodyBottom;
    private boolean tooSmall;
    private Button deliver;
    private Button claim;
    private Button guideButton;
    private Button trackButton;
    private String confirmBranchId;

    QuestJournalScreen(String scope, ClientPacketListener connection) {
        super(Component.translatable("worldsmith.quests.title")); worldScope = scope; worldConnection = connection;
    }

    @Override protected void init() {
        tooSmall = width < 320 || height < 180;
        if (tooSmall) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
            return;
        }
        int previousPageSize = pageSize;
        var layout = QuestJournalLayout.of(width, height);
        bodyTop = layout.bodyTop(); bodyBottom = layout.bodyBottom();
        listWidth = layout.listWidth(); detailX = layout.detailX(); detailWidth = layout.detailWidth();
        pageSize = layout.pageSize();
        var state = QuestJournalClient.snapshot(worldScope);
        displayed = state == null ? List.of() : state.quests();
        if (selectedId == null || displayed.stream().noneMatch(q -> q.id().equals(selectedId))) {
            selectedId = displayed.stream().filter(q -> q.status() == QuestProtocol.Status.ACTIVE || q.status() == QuestProtocol.Status.READY || q.status() == QuestProtocol.Status.AVAILABLE)
                .findFirst().or(() -> displayed.stream().findFirst()).map(QuestProtocol.Entry::id).orElse(null);
            detailScroll = 0;
            for (int i = 0; i < displayed.size(); i++) if (displayed.get(i).id().equals(selectedId)) { page = i / pageSize; break; }
        }
        if (previousPageSize > 0 && previousPageSize != pageSize)
            for (int i = 0; i < displayed.size(); i++) if (displayed.get(i).id().equals(selectedId)) { page = i / pageSize; break; }
        int pages = Math.max(1, (displayed.size() + pageSize - 1) / pageSize); page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < pageSize; row++) {
            int index = page * pageSize + row; if (index >= displayed.size()) break;
            var quest = displayed.get(index); boolean selected = quest.id().equals(selectedId);
            Component label = Component.literal(selected ? "> " : "").append(status(quest.status())).append(" | ")
                .append(Component.literal(quest.title())).withStyle(selected ? ChatFormatting.GOLD : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, button -> select(quest.id()))
                .bounds(16, bodyTop + 4 + row * 26, listWidth - 8, 22)
                .tooltip(Tooltip.create(Component.literal(quest.title()).append(" — ").append(status(quest.status())))).build());
        }
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
            .bounds(16, bodyBottom - 21, 24, 18).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.previous"))).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
            .bounds(12 + listWidth - 28, bodyBottom - 21, 24, 18).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.next"))).build());
        previous.active = page > 0; next.active = page + 1 < pages;
        int buttonWidth = (detailWidth - 6) / 2;
        deliver = addRenderableWidget(Button.builder(Component.translatable("worldsmith.quests.deliver"), button -> {
            var quest = selected(); if (quest == null) return;
            if (offer(quest)) {
                if (quest.branchChoice() && !quest.id().equals(confirmBranchId)) { confirmBranchId = quest.id(); updateButtons(); }
                else { confirmBranchId = null; QuestJournalClient.accept(quest.id()); }
            } else if (optionalDelivery(quest)) QuestJournalClient.deliverOptional(quest.id());
            else QuestJournalClient.deliver(quest.id());
        }).bounds(detailX, height - 26, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.delivery_hint"))).build());
        claim = addRenderableWidget(Button.builder(Component.translatable("worldsmith.quests.claim"), button -> {
            var quest = selected(); if (quest == null) return;
            confirmBranchId = null;
            if (offer(quest)) QuestJournalClient.decline(quest.id()); else QuestJournalClient.claim(quest.id());
        }).bounds(detailX + buttonWidth + 6, height - 26, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.claim_hint"))).build());
        int navigationWidth = (listWidth - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(12, height - 26, navigationWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("worldsmith.story.open"), button -> StoryJournalClient.open())
            .bounds(16 + navigationWidth, height - 26, listWidth - navigationWidth - 4, 20)
            .tooltip(Tooltip.create(Component.translatable("worldsmith.story.open_hint"))).build());
        var guideReferences = MechanicGuideClient.references(selected());
        guideButton = null;
        if (!guideReferences.isEmpty()) {
            guideButton = addRenderableWidget(Button.builder(Component.translatable("worldsmith.mechanics.guide.open", guideReferences.size()), button -> {
                if (selectedId != null && QuestJournalClient.current(worldScope, worldConnection))
                    minecraft.gui.setScreen(new MechanicGuideScreen(this, worldScope, worldConnection, selectedId, guideReferences));
            }).bounds(detailX + 6, bodyBottom - 23, (detailWidth - 18) / 2, 20)
                .tooltip(Tooltip.create(Component.translatable("worldsmith.mechanics.guide.open_hint"))).build());
        }
        int trackX = guideButton == null ? detailX + 6 : detailX + 12 + (detailWidth - 18) / 2;
        trackButton = addRenderableWidget(Button.builder(Component.translatable("worldsmith.quests.track"), button -> {
            var current = QuestJournalClient.snapshot(worldScope);
            if (selectedId == null || current == null) return;
            if (selectedId.equals(current.trackedQuest())) QuestJournalClient.untrack(); else QuestJournalClient.track(selectedId);
        }).bounds(trackX, bodyBottom - 23, detailX + detailWidth - 6 - trackX, 20)
            .tooltip(Tooltip.create(Component.translatable("worldsmith.quests.track_hint"))).build());
        updateButtons();
    }

    void serverStateChanged() {
        if (width == 0) return;
        var state = QuestJournalClient.snapshot(worldScope); var quests = state == null ? List.<QuestProtocol.Entry>of() : state.quests();
        if (!quests.equals(displayed)) rebuildWidgets(); else updateButtons();
    }

    private void select(String id) { selectedId = id; confirmBranchId = null; detailScroll = 0; rebuildWidgets(); }
    private static boolean offer(QuestProtocol.Entry quest) { return quest.status() == QuestProtocol.Status.AVAILABLE || quest.status() == QuestProtocol.Status.DECLINED; }
    private static boolean optionalDelivery(QuestProtocol.Entry quest) {
        return quest != null && quest.objectives().stream().noneMatch(o -> o.kind().equals("deliver_item") && !o.optional() && o.progress() < o.required())
            && quest.objectives().stream().anyMatch(o -> o.kind().equals("deliver_item") && o.optional() && o.progress() < o.required());
    }
    private QuestProtocol.Entry selected() { return displayed.stream().filter(q -> q.id().equals(selectedId)).findFirst().orElse(null); }
    private void updateButtons() {
        if (tooSmall || deliver == null || claim == null) return;
        boolean connected = QuestJournalClient.current(worldScope, worldConnection), pending = QuestJournalClient.pending();
        var quest = selected(); boolean usable = connected && !pending && quest != null;
        boolean offered = quest != null && offer(quest);
        boolean confirmation = offered && quest.branchChoice() && quest.id().equals(confirmBranchId);
        deliver.setMessage(Component.translatable(offered ? confirmation ? "worldsmith.quests.branch_confirm" : "worldsmith.quests.accept"
            : optionalDelivery(quest) ? "worldsmith.quests.deliver_optional" : "worldsmith.quests.deliver"));
        deliver.setTooltip(Tooltip.create(Component.translatable(offered ? confirmation ? "worldsmith.quests.branch_confirm_hint" : "worldsmith.quests.accept_hint"
            : optionalDelivery(quest) ? "worldsmith.quests.deliver_optional_hint" : "worldsmith.quests.delivery_hint")));
        claim.setMessage(Component.translatable(offered ? "worldsmith.quests.decline" : "worldsmith.quests.claim"));
        claim.setTooltip(Tooltip.create(Component.translatable(offered ? "worldsmith.quests.decline_hint" : "worldsmith.quests.claim_hint")));
        deliver.active = usable && (offered || (quest.status() == QuestProtocol.Status.ACTIVE || quest.status() == QuestProtocol.Status.READY)
            && quest.objectives().stream().anyMatch(o -> o.kind().equals("deliver_item") && o.progress() < o.required()));
        claim.active = usable && (quest.status() == QuestProtocol.Status.READY || offered && quest.status() != QuestProtocol.Status.DECLINED);
        if (guideButton != null) guideButton.active = connected && MechanicGuideClient.references(quest).stream().allMatch(id -> MechanicGuideClient.available(worldScope, id));
        if (trackButton != null) {
            var current = QuestJournalClient.snapshot(worldScope);
            trackButton.active = usable && quest.status() != QuestProtocol.Status.EXCLUDED;
            trackButton.setMessage(Component.translatable(current != null && selectedId != null && selectedId.equals(current.trackedQuest()) ? "worldsmith.quests.untrack" : "worldsmith.quests.track"));
        }
    }

    @Override public void tick() {
        if (!QuestJournalClient.current(worldScope, worldConnection)) { onClose(); return; }
        updateButtons();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 10, 0xFFF1E6C6);
        if (tooSmall) {
            graphics.textWithWordWrap(font, Component.translatable("worldsmith.quests.small_window"), 16, 45, Math.max(30, width - 32), 0xFFFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick); return;
        }
        var state = QuestJournalClient.snapshot(worldScope);
        String worldTitle = state == null ? "Worldsmith" : state.worldTitle();
        graphics.centeredText(font, font.plainSubstrByWidth(worldTitle, width - 36), width / 2, height < 270 ? 25 : 30, 0xFFBFCADB);
        graphics.fill(12, bodyTop, 12 + listWidth, bodyBottom, 0xB8202733);
        graphics.fill(detailX, bodyTop, detailX + detailWidth, bodyBottom, 0xCE17212F);
        graphics.outline(detailX, bodyTop, detailWidth, bodyBottom - bodyTop, 0xFF4A586D);
        int pages = Math.max(1, (displayed.size() + pageSize - 1) / pageSize);
        graphics.centeredText(font, (page + 1) + " / " + pages, 12 + listWidth / 2, bodyBottom - 16, 0xFFCFD5DF);
        if (displayed.isEmpty()) {
            graphics.textWithWordWrap(font, Component.translatable(QuestJournalClient.pending() ? "worldsmith.quests.loading" : "worldsmith.quests.empty"),
                detailX + 10, bodyTop + 13, detailWidth - 20, 0xFFCBD3DF);
        } else drawDetails(graphics);
        int feedbackY = height - 48;
        Component feedback = QuestJournalClient.notice();
        drawLimited(graphics, feedback, 12, feedbackY, width - 24, 2, QuestJournalClient.noticeColor());
        if (mouseY >= feedbackY && mouseY < height - 29 && mouseX >= 12 && mouseX < width - 12)
            graphics.setTooltipForNextFrame(feedback, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawDetails(GuiGraphicsExtractor graphics) {
        var quest = selected(); if (quest == null) return;
        List<TextLine> lines = new ArrayList<>(); int textWidth = Math.max(30, detailWidth - 24);
        add(lines, Component.literal(quest.title()).withStyle(ChatFormatting.BOLD), 0xFFF3E4B7, textWidth);
        add(lines, status(quest.status()), statusColor(quest.status()), textWidth); blank(lines);
        if (quest.optional()) add(lines, Component.translatable("worldsmith.quests.optional"), 0xFFB6C0CE, textWidth);
        if (quest.branchChoice() && offer(quest)) add(lines, Component.translatable("worldsmith.quests.branch_warning"), 0xFFFFCC7A, textWidth);
        add(lines, Component.literal(quest.description()), 0xFFD9DFE8, textWidth); blank(lines);
        if (quest.status() == QuestProtocol.Status.LOCKED) {
            add(lines, Component.translatable("worldsmith.quests.locked_hint"), 0xFFFFCC7A, textWidth); blank(lines);
        }
        add(lines, Component.translatable("worldsmith.quests.objectives"), 0xFFB9D8F6, textWidth);
        for (var objective : quest.objectives()) {
            if (objective.optional()) add(lines, Component.translatable("worldsmith.quests.optional_objective"), 0xFFB6C0CE, textWidth);
            add(lines, Component.translatable("worldsmith.quests.objective." + objective.kind(), objective.targetLabel(), objective.progress(), objective.required()),
                objective.progress() == objective.required() ? 0xFF9CDDAD : 0xFFD9DFE8, textWidth);
        }
        blank(lines); add(lines, Component.translatable("worldsmith.quests.rewards"), 0xFFB9D8F6, textWidth);
        if (quest.rewards().isEmpty()) add(lines, Component.translatable("worldsmith.quests.no_rewards"), 0xFFB6C0CE, textWidth);
        else for (var reward : quest.rewards()) add(lines, Component.translatable("worldsmith.quests.reward", reward.label(), reward.count()), 0xFFD9DFE8, textWidth);
        int detailBottom = bodyBottom - 26;
        int viewport = detailBottom - bodyTop - 16;
        maximumDetailScroll = Math.max(0, lines.size() * 12 - viewport); detailScroll = Math.max(0, Math.min(detailScroll, maximumDetailScroll));
        graphics.enableScissor(detailX + 3, bodyTop + 3, detailX + detailWidth - 3, detailBottom - 3);
        int y = bodyTop + 8 - detailScroll;
        for (var line : lines) { if (y + 10 >= bodyTop && y < detailBottom) graphics.text(font, line.text, detailX + 10, y, line.color); y += 12; }
        graphics.disableScissor();
        if (maximumDetailScroll > 0) {
            int track = detailBottom - bodyTop - 12, thumb = Math.max(12, track * viewport / (lines.size() * 12));
            int top = bodyTop + 6 + (track - thumb) * detailScroll / maximumDetailScroll;
            graphics.fill(detailX + detailWidth - 6, top, detailX + detailWidth - 3, top + thumb, 0xFF7C8CA3);
        }
    }

    private void add(List<TextLine> lines, Component component, int color, int width) { for (var line : font.split(component, width)) lines.add(new TextLine(line, color)); }
    private static void blank(List<TextLine> lines) { lines.add(new TextLine(FormattedCharSequence.EMPTY, 0)); }
    private void drawLimited(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int maximumLines, int color) {
        var lines = font.split(text, width); for (int i = 0; i < Math.min(lines.size(), maximumLines); i++) graphics.text(font, lines.get(i), x, y + i * 10, color);
    }
    private record TextLine(FormattedCharSequence text, int color) {}
    private static Component status(QuestProtocol.Status status) { return Component.translatable("worldsmith.quests.status." + status.name().toLowerCase(Locale.ROOT)); }
    private static int statusColor(QuestProtocol.Status status) {
        return switch (status) { case LOCKED, DECLINED, EXCLUDED -> 0xFF9CA8B7; case ACTIVE, AVAILABLE -> 0xFF9BC9F7; case READY -> 0xFFF0D68F; case CLAIMED -> 0xFF9CDDAD; };
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!tooSmall && mouseY >= bodyTop && mouseY < bodyBottom) {
            if (mouseX >= detailX && mouseX < detailX + detailWidth) { detailScroll = Math.max(0, Math.min(maximumDetailScroll, detailScroll - (int)Math.round(scrollY * 24))); return true; }
            if (mouseX >= 12 && mouseX < 12 + listWidth && scrollY != 0) {
                int pages = Math.max(1, (displayed.size() + pageSize - 1) / pageSize), next = Math.max(0, Math.min(pages - 1, page + (scrollY > 0 ? -1 : 1)));
                if (next != page) { page = next; rebuildWidgets(); } return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (QuestJournalClient.matchesOpenKey(event)) { onClose(); return true; }
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            detailScroll = Math.max(0, Math.min(maximumDetailScroll, detailScroll + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, bodyBottom - bodyTop - 16)));
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
