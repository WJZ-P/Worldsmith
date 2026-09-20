package com.wjz.worldsmith.client.story;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Actual focusable, scrollable and narrated content, shared by both player story screens. */
final class StoryTextPane extends AbstractScrollArea {
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final Component contents;
    StoryTextPane(int x, int y, int width, int height, Component contents) {
        super(x, y, width, height, contents, AbstractScrollArea.defaultSettings(24));
        this.contents = contents;
        lines.addAll(Minecraft.getInstance().font.split(contents, Math.max(8, width - 30)));
    }
    @Override protected int contentHeight() { return Math.max(20, lines.size() * 13 + 20); }
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), 0xDD152320);
        graphics.outline(getX(), getY(), width, height, isFocused() ? 0xFFD0C28D : 0xFF516860);
        graphics.enableScissor(getX() + 2, getY() + 2, getRight() - 8, getBottom() - 2);
        int y = getY() + 10 - (int)scrollAmount();
        for (var line : lines) { if (y + 12 >= getY() && y < getBottom()) graphics.text(Minecraft.getInstance().font, line, getX() + 12, y, 0xFFE4E8DB); y += 13; }
        graphics.disableScissor(); extractScrollbar(graphics, mouseX, mouseY);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            setScrollAmount(scrollAmount() + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, height - 20)); return true;
        }
        if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
            setScrollAmount(event.key() == GLFW.GLFW_KEY_HOME ? 0 : contentHeight()); return true;
        }
        return super.keyPressed(event);
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, contents);
        output.add(NarratedElementType.USAGE, Component.translatable("worldsmith.story.scroll"));
    }
}
