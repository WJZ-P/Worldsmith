package com.wjz.worldsmith.client.story;

import com.wjz.worldsmith.content.story.StoryProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A conversation view; every choice remains an untrusted intent until the server sends the next token. */
public final class StoryDialogueScreen extends Screen {
    private final String scope;
    private StoryProtocol.Dialogue dialogue;
    private StoryTextPane body;
    private final List<Button> choices = new ArrayList<>();
    private int page, pageSize = 3;
    private boolean tooSmall;
    StoryDialogueScreen(String scope, StoryProtocol.Dialogue dialogue) {
        super(Component.translatable("worldsmith.story.dialogue")); this.scope = scope; this.dialogue = dialogue;
    }
    public UUID actor() { return dialogue.actor(); }
    public UUID token() { return dialogue.token(); }
    void update(StoryProtocol.Dialogue next) {
        boolean changed = !dialogue.token().equals(next.token()); dialogue = next;
        if (changed) { page = 0; rebuildWidgets(); triggerImmediateNarration(false); }
        updatePending();
    }
    @Override protected void init() {
        choices.clear(); tooSmall = width < 240 || height < 180;
        if (tooSmall) {
            addRenderableWidget(Button.builder(tr("close"), ignored -> onClose()).bounds(Math.max(4, width / 2 - 60), Math.max(30, height - 26), Math.min(120, width - 8), 20).build()); return;
        }
        int left = Math.max(12, (width - 640) / 2), w = width - left * 2;
        pageSize = Math.max(1, Math.min(4, (height - 150) / 26));
        int pages = Math.max(1, (dialogue.choices().size() + pageSize - 1) / pageSize); page = Math.min(page, pages - 1);
        int choiceTop = height - 52 - pageSize * 26;
        var bodyText = Component.literal(dialogue.text());
        // Long canonical trade names can exceed the whole window. Keep the complete terms in the scrollable/narrated
        // body as well as the native hover/focus tooltip, rather than trusting a clipped one-line button or tooltip.
        for (var option : dialogue.choices().stream().skip((long)page * pageSize).limit(pageSize).toList())
            if (!option.details().isBlank()) bodyText.append("\n\n").append(tr("choice_details", option.label())).append("\n").append(option.details());
        body = addRenderableWidget(new StoryTextPane(left, 38, w, Math.max(42, choiceTop - 44), bodyText));
        for (int row = 0; row < pageSize; row++) {
            int index = page * pageSize + row; if (index >= dialogue.choices().size()) break;
            var option = dialogue.choices().get(index);
            var detail = Component.literal(option.label());
            if (!option.details().isBlank()) detail.append("\n\n").append(option.details());
            var button = addRenderableWidget(Button.builder(Component.literal((row + 1) + ". " + option.label()), ignored -> StoryJournalClient.choose(dialogue.token(), option.id()))
                .bounds(left, choiceTop + row * 26, w, 22).tooltip(Tooltip.create(detail))
                .createNarration(defaultNarration -> {
                    var narration = defaultNarration.get();
                    return option.details().isBlank() ? narration : narration.append("\n").append(option.details());
                }).build());
            choices.add(button);
        }
        if (pages > 1) {
            var previous = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { page--; rebuildWidgets(); }).bounds(left, height - 27, 24, 20).tooltip(Tooltip.create(tr("previous"))).build());
            var next = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { page++; rebuildWidgets(); }).bounds(left + 28, height - 27, 24, 20).tooltip(Tooltip.create(tr("next"))).build());
            previous.active = page > 0; next.active = page + 1 < pages;
        }
        addRenderableWidget(Button.builder(tr("close"), ignored -> onClose()).bounds(left + w - 110, height - 27, 110, 20).build());
        updatePending(); setInitialFocus(body);
    }
    private void updatePending() { choices.forEach(button -> button.active = !StoryJournalClient.pending()); }
    @Override public void tick() { if (!StoryJournalClient.current(scope)) { minecraft.gui.setScreen(null); return; } updatePending(); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, font.plainSubstrByWidth(dialogue.speaker(), Math.max(20, width - 24)), width / 2, 14, 0xFFF1DBA7);
        if (tooSmall) graphics.textWithWordWrap(font, tr("small_window"), 10, 40, Math.max(20, width - 20), 0xFFE4E8DB);
        else {
            Component notice = StoryJournalClient.pending() ? tr("waiting") : StoryJournalClient.notice();
            graphics.centeredText(font, font.plainSubstrByWidth(notice.getString(), width - 32), width / 2, height - 42, 0xFFE2C38B);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    @Override public Component getNarrationMessage() { return Component.literal(dialogue.speaker()).append(". ").append(dialogue.text()); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (body != null && (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP || event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END)) return body.keyPressed(event);
        if (!StoryJournalClient.pending() && event.key() >= GLFW.GLFW_KEY_1 && event.key() < GLFW.GLFW_KEY_1 + choices.size()) {
            int index = page * pageSize + event.key() - GLFW.GLFW_KEY_1; StoryJournalClient.choose(dialogue.token(), dialogue.choices().get(index).id()); return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { StoryJournalClient.closeDialogue(dialogue.actor()); minecraft.gui.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.story." + key, args); }
}
