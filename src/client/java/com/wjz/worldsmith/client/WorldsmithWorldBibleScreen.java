package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.mcp.GenerationProgressView;
import com.wjz.worldsmith.core.mcp.WorldAuthoringView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** A read-only snapshot of the selected session. No bridge calls, resource loading or activation. */
public final class WorldsmithWorldBibleScreen extends Screen {
    private final Screen parent;
    private final String sessionId;
    private final long revision;
    private final WorldAuthoringView authoring;
    private final boolean currentDraft;
    private BibleText body;

    public WorldsmithWorldBibleScreen(Screen parent, GenerationProgressView selected) {
        this(parent, selected, false);
    }

    public WorldsmithWorldBibleScreen(Screen parent, GenerationProgressView selected, boolean currentDraft) {
        super(tr(currentDraft ? "draft.title" : "title"));
        this.parent = parent;
        this.sessionId = selected.getSessionId();
        this.revision = selected.getRevision();
        this.authoring = Objects.requireNonNull(selected.getAuthoring());
        this.currentDraft = currentDraft;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("worldsmith.progress.bible." + key, args);
    }

    @Override protected void init() {
        double scroll = body == null ? 0 : body.scrollAmount();
        int contentWidth = Math.max(24, Math.min(920, width - 24));
        body = addRenderableWidget(new BibleText((width - contentWidth) / 2, 34, contentWidth, Math.max(20, height - 74)));
        body.setScrollAmount(scroll);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), ignored -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 14, 0xFFF0EBDD);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public boolean keyPressed(KeyEvent event) {
        if (body != null && (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP
                || event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END)) return body.keyPressed(event);
        return super.keyPressed(event);
    }

    private final class BibleText extends AbstractScrollArea {
        private static final int INK = 0xFFF0EBDD, MUTED = 0xFFAEB8C2, GOLD = 0xFFE0C18C;
        private final List<Line> lines = new ArrayList<>();
        private int textHeight = 12;

        BibleText(int x, int y, int width, int height) {
            super(x, y, width, height, tr("title"), AbstractScrollArea.defaultSettings(24));
            append(tr("snapshot", sessionId, revision, authoring.getBibleRevision()), MUTED);
            if (currentDraft) append(tr("draft.scope"), GOLD);
            append(Component.translatable("worldsmith.progress.stage." + authoring.getStage()), GOLD);
            append(tr("summary", authoring.getBibleRevision(),
                    tr(authoring.getAiReviewed() ? "ai_reviewed" : "needs_review"),
                    authoring.getReviewedBriefs(), authoring.getBriefCount()), MUTED);
            append(tr("review_boundary"), MUTED);
            append(tr("read_only"), MUTED);
            if (authoring.getMarkdownTruncated()) append(tr("truncated"), GOLD);
            textHeight += 10;
            // Headings are styled; the remaining deterministic Markdown is inert wrapped text.
            for (String paragraph : authoring.getMarkdown().split("\\R", -1)) {
                if (paragraph.isBlank()) { textHeight += 7; continue; }
                if (paragraph.matches("^#{1,6} .*")) {
                    textHeight += 5;
                    append(Component.literal(paragraph.replaceFirst("^#{1,6} ", "")).withStyle(ChatFormatting.BOLD), GOLD);
                } else append(Component.literal(paragraph), INK);
            }
            if (authoring.getMarkdownTruncated()) { textHeight += 8; append(tr("truncated"), GOLD); }
            textHeight += 12;
        }

        private void append(Component text, int color) {
            for (var line : font.split(text, Math.max(8, width - 32))) {
                lines.add(new Line(textHeight, line, color)); textHeight += 12;
            }
        }

        @Override protected int contentHeight() { return textHeight; }

        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xE51B2126);
            graphics.outline(getX(), getY(), width, height, 0xFF414D55);
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 7, getBottom() - 1);
            int offset = getY() - (int)scrollAmount();
            for (var line : lines) if (offset + line.y + 12 >= getY() && offset + line.y < getBottom())
                graphics.text(font, line.text, getX() + 12, offset + line.y, line.color);
            graphics.disableScissor();
            extractScrollbar(graphics, mouseX, mouseY);
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                setScrollAmount(scrollAmount() + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, height - 20));
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
                setScrollAmount(event.key() == GLFW.GLFW_KEY_HOME ? 0 : textHeight); return true;
            }
            return super.keyPressed(event);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, tr(currentDraft ? "draft.title" : "title"));
            output.add(NarratedElementType.USAGE, tr("scroll_hint"));
        }

        private record Line(int y, FormattedCharSequence text, int color) {}
    }
}
