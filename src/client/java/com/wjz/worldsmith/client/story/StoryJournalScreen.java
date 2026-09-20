package com.wjz.worldsmith.client.story;

import com.wjz.worldsmith.content.story.StoryProtocol;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Contains only the knowledge and marker instances disclosed by the server. */
public final class StoryJournalScreen extends Screen {
    private final String scope;
    private boolean places, tooSmall;
    private int selected, page, pageSize = 4;
    private StoryTextPane body;
    private String bodyIdentity;
    private List<StoryProtocol.Knowledge> knowledge = List.of();
    private List<StoryProtocol.Place> locations = List.of();
    StoryJournalScreen(String scope) { super(tr("journal")); this.scope = scope; }
    void update() {
        var state = StoryJournalClient.snapshot();
        if (state == null || width == 0 || state.knowledge().equals(knowledge) && state.places().equals(locations)) return;
        String selectedKey = selectedIdentity();
        knowledge = state.knowledge(); locations = state.places();
        int size = places ? locations.size() : knowledge.size();
        for (int i = 0; i < size; i++) {
            String key = places ? "place:" + locations.get(i).instance() : "knowledge:" + knowledge.get(i).id();
            if (key.equals(selectedKey)) { selected = i; break; }
        }
        rebuildWidgets();
    }
    private String selectedIdentity() {
        return places ? selected >= 0 && selected < locations.size() ? "place:" + locations.get(selected).instance() : "empty:places"
            : selected >= 0 && selected < knowledge.size() ? "knowledge:" + knowledge.get(selected).id() : "empty:knowledge";
    }
    @Override protected void init() {
        tooSmall = width < 300 || height < 180;
        if (tooSmall) { addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build()); return; }
        var state = StoryJournalClient.snapshot(); knowledge = state == null ? List.of() : state.knowledge(); locations = state == null ? List.of() : state.places();
        int size = places ? locations.size() : knowledge.size(); selected = Math.max(0, Math.min(selected, size - 1));
        int left = 12, listWidth = Math.max(112, Math.min(210, width / 3)), detail = left + listWidth + 10, dw = width - detail - 12;
        addRenderableWidget(Button.builder(tr("knowledge_tab", knowledge.size()), ignored -> { places = false; page = 0; selected = 0; rebuildWidgets(); }).bounds(left, 31, listWidth, 20).build());
        addRenderableWidget(Button.builder(tr("places_tab", locations.size()), ignored -> { places = true; page = 0; selected = 0; rebuildWidgets(); }).bounds(detail, 31, dw, 20).build());
        pageSize = Math.max(1, (height - 116) / 25); int pages = Math.max(1, (size + pageSize - 1) / pageSize); page = Math.min(page, pages - 1);
        for (int row = 0; row < pageSize; row++) {
            int index = page * pageSize + row; if (index >= size) break;
            Component title = Component.literal(places ? locations.get(index).name() : knowledge.get(index).title());
            Component label = index == selected ? Component.literal("> ").append(title) : title;
            addRenderableWidget(Button.builder(label, ignored -> { selected = index; rebuildWidgets(); }).bounds(left, 58 + row * 25, listWidth, 22).tooltip(Tooltip.create(title)).build());
        }
        Component content;
        if (size == 0) content = tr(places ? "places_empty" : "knowledge_empty");
        else if (places) {
            var place = locations.get(selected);
            content = Component.literal(place.name()).append("\n\n").append(place.description());
            if (!place.clue().isBlank()) content = content.copy().append("\n\n").append(tr("clue", place.clue()));
            content = content.copy().append("\n\n").append(tr("actual_place", place.x(), place.y(), place.z()));
        } else {
            var record = knowledge.get(selected);
            content = Component.literal(record.title()).append("\n").append(tr("truth." + record.truth().toLowerCase(java.util.Locale.ROOT))).append("\n\n").append(record.text());
        }
        String nextIdentity = selectedIdentity();
        double scroll = body != null && nextIdentity.equals(bodyIdentity) ? body.scrollAmount() : 0;
        body = addRenderableWidget(new StoryTextPane(detail, 58, dw, Math.max(34, height - 116), content));
        body.setScrollAmount(scroll); bodyIdentity = nextIdentity;
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { page--; rebuildWidgets(); }).bounds(left, height - 53, 24, 20).tooltip(Tooltip.create(tr("previous"))).build());
        var next = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { page++; rebuildWidgets(); }).bounds(left + listWidth - 24, height - 53, 24, 20).tooltip(Tooltip.create(tr("next"))).build());
        previous.active = page > 0; next.active = page + 1 < pages;
        if (places && size > 0) addRenderableWidget(Button.builder(tr("track"), ignored -> { StoryJournalClient.track(locations.get(selected)); onClose(); }).bounds(detail, height - 53, Math.max(40, (dw - 6) / 2), 20).build());
        addRenderableWidget(Button.builder(tr("clear_tracking"), ignored -> { StoryJournalClient.clearTracking(); rebuildWidgets(); }).bounds(detail + (dw + 6) / 2, height - 53, Math.max(40, (dw - 6) / 2), 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose()).bounds(width - 112, height - 27, 100, 20).build());
    }
    @Override public void tick() { if (!StoryJournalClient.current(scope)) minecraft.gui.setScreen(null); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 12, 0xFFF1DBA7);
        if (tooSmall) graphics.textWithWordWrap(font, tr("small_window"), 12, 45, Math.max(20, width - 24), 0xFFE4E8DB);
        else {
            graphics.text(font, Component.literal((page + 1) + " / " + Math.max(1, ((places ? locations.size() : knowledge.size()) + pageSize - 1) / pageSize)), 45, height - 47, 0xFFB5C7BA);
            graphics.text(font, font.plainSubstrByWidth(StoryJournalClient.notice().getString(), width - 138), 12, height - 20, 0xFFE2C38B);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (body != null && (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP || event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END)) return body.keyPressed(event);
        return super.keyPressed(event);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.story." + key, args); }
}
