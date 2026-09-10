package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.content.quest.QuestProtocol;
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
    private Button refresh;

    QuestJournalScreen(String scope, ClientPacketListener connection) {
        super(Component.translatable("worldsmith.quests.title")); worldScope = scope; worldConnection = connection;
    }

    @Override protected void init() {
        tooSmall = width < 320 || height < 180;
        if (tooSmall) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
            return;
        }
        boolean compact = height < 270;
        bodyTop = compact ? 40 : 50; bodyBottom = height - (compact ? 75 : 94);
        listWidth = Math.max(110, Math.min(216, width / 3)); detailX = 12 + listWidth + 12; detailWidth = width - detailX - 12;
        pageSize = Math.max(1, (bodyBottom - bodyTop - 27) / 26);
        var state = QuestJournalClient.snapshot(worldScope);
        displayed = state == null ? List.of() : state.quests();
        if (selectedId == null || displayed.stream().noneMatch(q -> q.id().equals(selectedId))) {
            selectedId = displayed.stream().filter(q -> q.status() == QuestProtocol.Status.ACTIVE || q.status() == QuestProtocol.Status.READY)
                .findFirst().or(() -> displayed.stream().findFirst()).map(QuestProtocol.Entry::id).orElse(null);
            detailScroll = 0;
            for (int i = 0; i < displayed.size(); i++) if (displayed.get(i).id().equals(selectedId)) { page = i / pageSize; break; }
        }
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
            if (selectedId != null) QuestJournalClient.deliver(selectedId);
        }).bounds(detailX, height - 26, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.delivery_hint"))).build());
        claim = addRenderableWidget(Button.builder(Component.translatable("worldsmith.quests.claim"), button -> {
            if (selectedId != null) QuestJournalClient.claim(selectedId);
        }).bounds(detailX + buttonWidth + 6, height - 26, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable("worldsmith.quests.claim_hint"))).build());
        int leftWidth = (listWidth - 6) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(12, height - 26, leftWidth, 20).build());
        refresh = addRenderableWidget(Button.builder(Component.translatable("worldsmith.quests.refresh"), button -> QuestJournalClient.sync())
            .bounds(12 + leftWidth + 6, height - 26, leftWidth, 20).build());
        updateButtons();
    }

    void serverStateChanged() {
        if (width == 0) return;
        var state = QuestJournalClient.snapshot(worldScope); var quests = state == null ? List.<QuestProtocol.Entry>of() : state.quests();
        if (!quests.equals(displayed)) rebuildWidgets(); else updateButtons();
    }

    private void select(String id) { selectedId = id; detailScroll = 0; rebuildWidgets(); }
    private QuestProtocol.Entry selected() { return displayed.stream().filter(q -> q.id().equals(selectedId)).findFirst().orElse(null); }
    private void updateButtons() {
        if (tooSmall || deliver == null || claim == null || refresh == null) return;
        boolean connected = QuestJournalClient.current(worldScope, worldConnection), pending = QuestJournalClient.pending();
        var quest = selected(); boolean usable = connected && !pending && quest != null;
        deliver.active = usable && quest.status() == QuestProtocol.Status.ACTIVE && quest.objectives().stream().anyMatch(o -> o.kind().equals("deliver_item"));
        claim.active = usable && quest.status() == QuestProtocol.Status.READY;
        refresh.active = connected && !pending;
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
        int hintY = bodyBottom + 7;
        drawLimited(graphics, Component.translatable("worldsmith.quests.delivery_hint"), 12, hintY, width - 24, height < 270 ? 1 : 2, 0xFFAEBBCD);
        int feedbackY = height - (height < 270 ? 51 : 56);
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
        add(lines, Component.literal(quest.description()), 0xFFD9DFE8, textWidth); blank(lines);
        if (quest.status() == QuestProtocol.Status.LOCKED) {
            add(lines, Component.translatable("worldsmith.quests.locked_hint"), 0xFFFFCC7A, textWidth); blank(lines);
        }
        add(lines, Component.translatable("worldsmith.quests.objectives"), 0xFFB9D8F6, textWidth);
        for (var objective : quest.objectives()) {
            add(lines, Component.translatable("worldsmith.quests.objective." + objective.kind(), objective.targetLabel(), objective.progress(), objective.required()),
                objective.progress() == objective.required() ? 0xFF9CDDAD : 0xFFD9DFE8, textWidth);
        }
        blank(lines); add(lines, Component.translatable("worldsmith.quests.rewards"), 0xFFB9D8F6, textWidth);
        if (quest.rewards().isEmpty()) add(lines, Component.translatable("worldsmith.quests.no_rewards"), 0xFFB6C0CE, textWidth);
        else for (var reward : quest.rewards()) add(lines, Component.translatable("worldsmith.quests.reward", reward.label(), reward.count()), 0xFFD9DFE8, textWidth);
        int viewport = bodyBottom - bodyTop - 16;
        maximumDetailScroll = Math.max(0, lines.size() * 12 - viewport); detailScroll = Math.max(0, Math.min(detailScroll, maximumDetailScroll));
        graphics.enableScissor(detailX + 3, bodyTop + 3, detailX + detailWidth - 3, bodyBottom - 3);
        int y = bodyTop + 8 - detailScroll;
        for (var line : lines) { if (y + 10 >= bodyTop && y < bodyBottom) graphics.text(font, line.text, detailX + 10, y, line.color); y += 12; }
        graphics.disableScissor();
        if (maximumDetailScroll > 0) {
            int track = bodyBottom - bodyTop - 12, thumb = Math.max(12, track * viewport / (lines.size() * 12));
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
        return switch (status) { case LOCKED -> 0xFF9CA8B7; case ACTIVE -> 0xFF9BC9F7; case READY -> 0xFFF0D68F; case CLAIMED -> 0xFF9CDDAD; };
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
