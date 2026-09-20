package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.ability.extension.AbilityExtensionApproval;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

/** Independent trusted-code confirmation with scrollable, complete hashes and provider metadata. */
public final class WorldsmithExtensionApprovalScreen extends Screen {
    private final AbilityExtensionApproval approval;
    private final Consumer<Boolean> decision;
    private ReviewText body;
    private boolean resolved;

    public WorldsmithExtensionApprovalScreen(AbilityExtensionApproval approval, Consumer<Boolean> decision) {
        super(tr("title"));
        this.approval = java.util.Objects.requireNonNull(approval);
        this.decision = java.util.Objects.requireNonNull(decision);
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.extension.approval." + key, args); }

    /** Inert review model, shared with native/client verification. No hash is shortened or elided. */
    public static List<Component> reviewLines(AbilityExtensionApproval approval) {
        var lines = new ArrayList<Component>();
        lines.add(tr("identity", approval.getName(), approval.getId()));
        lines.add(tr("warning"));
        lines.add(tr("static_only"));
        lines.add(tr("independent"));
        lines.add(tr("source_hash"));
        hashLines(lines, approval.getSourceHash());
        lines.add(tr("artifact_hash"));
        hashLines(lines, approval.getArtifactHash());
        if (approval.getExistingArtifactHash() != null) {
            lines.add(tr("replacement_hash")); hashLines(lines, approval.getExistingArtifactHash());
        } else lines.add(tr("new_install"));
        lines.add(tr("providers"));
        for (String entry : approval.getManifest().getEntryClasses()) {
            var spec = approval.getManifest().getDeclaredSpecs().get(entry);
            lines.add(Component.literal(entry));
            lines.add(Component.literal(spec.getName() + " v" + spec.getVersion() + " ("
                + spec.getArguments().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", "))
                + ") -> " + spec.getResult().name() + "; effect=" + spec.getEffect()));
            lines.add(Component.literal(spec.getDescription()));
        }
        lines.add(tr("source_path", approval.getSourcePath()));
        lines.add(tr("artifact_path", approval.getArtifactPath()));
        lines.add(tr("restart"));
        return List.copyOf(lines);
    }
    private static void hashLines(List<Component> lines, String hash) {
        for (int start = 0; start < hash.length(); start += 32) lines.add(Component.literal(hash.substring(start, Math.min(hash.length(), start + 32))));
    }

    @Override protected void init() {
        double scroll = body == null ? 0 : body.scrollAmount();
        int contentWidth = Math.max(24, Math.min(800, width - 24));
        body = addRenderableWidget(new ReviewText((width - contentWidth) / 2, 34, contentWidth, Math.max(20, height - 74)));
        body.setScrollAmount(scroll);
        int buttonWidth = Math.max(40, Math.min(150, (width - 36) / 2));
        var cancel = addRenderableWidget(Button.builder(tr("deny"), ignored -> decide(false)).bounds(width / 2 - buttonWidth - 6, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(tr("install"), ignored -> decide(true)).bounds(width / 2 + 6, height - 28, buttonWidth, 20).build());
        setInitialFocus(cancel); // Enter never implicitly approves a newly opened dialog.
    }
    private void decide(boolean accepted) { if (!resolved) { resolved = true; decision.accept(accepted); } }
    @Override public void onClose() { decide(false); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 14, 0xFFF0EBDD);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (body != null && (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP
            || event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END)) return body.keyPressed(event);
        return super.keyPressed(event);
    }

    private final class ReviewText extends AbstractScrollArea {
        private final List<Line> lines = new ArrayList<>();
        private int textHeight = 12;
        ReviewText(int x, int y, int width, int height) {
            super(x, y, width, height, tr("title"), AbstractScrollArea.defaultSettings(24));
            for (var paragraph : reviewLines(approval)) {
                for (var line : font.split(paragraph, Math.max(8, width - 32))) { lines.add(new Line(textHeight, line)); textHeight += 12; }
                textHeight += 5;
            }
            textHeight += 12;
        }
        @Override protected int contentHeight() { return textHeight; }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xE51B2126);
            graphics.outline(getX(), getY(), width, height, 0xFF705542);
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 7, getBottom() - 1);
            int offset = getY() - (int)scrollAmount();
            for (var line : lines) if (offset + line.y + 12 >= getY() && offset + line.y < getBottom()) graphics.text(font, line.text, getX() + 12, offset + line.y, 0xFFF0EBDD);
            graphics.disableScissor(); extractScrollbar(graphics, mouseX, mouseY);
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                setScrollAmount(scrollAmount() + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, height - 20)); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) { setScrollAmount(event.key() == GLFW.GLFW_KEY_HOME ? 0 : textHeight); return true; }
            return super.keyPressed(event);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, tr("title")); output.add(NarratedElementType.USAGE, tr("scroll"));
        }
        private record Line(int y, FormattedCharSequence text) {}
    }
}
