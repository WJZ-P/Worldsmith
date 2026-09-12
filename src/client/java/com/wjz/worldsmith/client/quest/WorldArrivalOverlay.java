package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.quest.WorldArrivalPresentation;
import com.wjz.worldsmith.content.quest.WorldArrivalSession;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Cinematic HUD, not a Screen: movement, combat, camera and the simulation remain untouched. */
public final class WorldArrivalOverlay {
    private static final WorldArrivalSession SESSION = new WorldArrivalSession();
    private static WorldArrivalPresentation presentation;
    private static boolean initialized;
    private WorldArrivalOverlay() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { SESSION.connect(handler); presentation = null; });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { SESSION.connect(null); presentation = null; });
        ClientTickEvents.END_CLIENT_TICK.register(WorldArrivalOverlay::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, Worldsmith.id("world_arrival"), (graphics, delta) -> draw(graphics));
    }

    private static void tick(Minecraft client) {
        if (!WorldsmithConfig.get().getClient().getShowWorldArrival()) {
            if (SESSION.waiting(client.getConnection())) SESSION.start(client.getConnection(), System.nanoTime());
            SESSION.dismiss(); return;
        }
        if (!SESSION.waiting(client.getConnection()) || client.player == null || !client.player.isAlive() || client.level == null
            || !client.isLocalServer() || client.gui.screen() != null || client.gui.overlay() != null || client.gui.hud.isHidden()) return;
        String scope = WorldContentClientRuntime.activeScope();
        if (scope == null || QuestJournalClient.snapshot(scope) == null) return;
        var prepared = WorldContentClientRuntime.presentation(scope);
        if (prepared == null) return;
        if (SESSION.start(client.getConnection(), System.nanoTime())) presentation = prepared;
    }

    /** Consumes only the first Escape that dismisses a currently visible arrival. */
    public static boolean dismiss() {
        Minecraft client = Minecraft.getInstance();
        if (!visible(client, System.nanoTime())) return false;
        SESSION.dismiss(); return true;
    }

    private static boolean visible(Minecraft client, long now) {
        return presentation != null && SESSION.active(now) && client.player != null && client.player.isAlive() && client.level != null
            && client.gui.screen() == null && client.gui.overlay() == null && client.isLocalServer()
            && !client.gui.hud.isHidden()
            && presentation.scope().equals(WorldContentClientRuntime.activeScope())
            && WorldsmithConfig.get().getClient().getShowWorldArrival();
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance(); long now = System.nanoTime();
        if (!visible(client, now)) return;
        var state = QuestJournalClient.snapshot(presentation.scope());
        if (state == null) return;
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        if (width < 180 || height < 120) return;
        double elapsed = SESSION.elapsed(now) / 1_000_000_000.0;
        float fade = (float)Math.min(1.0, Math.min(elapsed / .4, (8.0 - elapsed) / .7));
        if (fade <= .02f) return;
        int panelWidth = Math.min(460, width - 32), textWidth = panelWidth - 28;
        int x = (width - panelWidth) / 2, top = Math.max(12, height / 7);
        List<Line> lines = new ArrayList<>();
        add(lines, Component.literal(state.worldTitle()), textWidth, 1, 0xF1E6C6, true);
        var quest = WorldArrivalPresentation.currentQuest(state.quests());
        if (quest != null) {
            add(lines, Component.translatable("worldsmith.arrival.current", quest.title()), textWidth, 2, 0xF0D68F, false);
            for (var objective : quest.objectives().stream().limit(2).toList()) {
                add(lines, Component.translatable("worldsmith.quests.objective." + objective.kind(), objective.targetLabel(), objective.progress(), objective.required()),
                    textWidth, 1, 0xCCE0D4, false);
            }
        } else add(lines, Component.translatable(WorldArrivalPresentation.complete(state.quests()) ? "worldsmith.arrival.complete" : "worldsmith.arrival.explore"), textWidth, 2, 0xF0D68F, false);
        add(lines, Component.literal(presentation.background(quest)), textWidth, height < 240 ? 2 : 3, 0xD4DCD9, false);
        Component tip = Component.translatable(quest != null && quest.status() == QuestProtocol.Status.READY ? "worldsmith.arrival.claim" : "worldsmith.arrival.journal", QuestJournalClient.openKeyLabel());
        add(lines, tip, textWidth, 1, 0xAEC8B3, false);
        // The caption always fits above the hotbar, even at unusually large GUI scales.
        int maxLines = Math.max(3, (height - top - 54) / 12);
        if (lines.size() > maxLines) lines = new ArrayList<>(lines.subList(0, maxLines));
        int panelHeight = 26 + lines.size() * 12;
        graphics.fill(0, 0, width, height, color(0x14201B, .16f * fade));
        graphics.fill(x, top, x + panelWidth, top + panelHeight, color(0x111D1B, .76f * fade));
        graphics.fill(x, top, x + 2, top + panelHeight, color(0xB7C99B, .9f * fade));
        int y = top + 10;
        for (var line : lines) {
            int textX = line.centered ? width / 2 - client.font.width(line.text) / 2 : x + 14;
            graphics.text(client.font, line.text, textX, y, color(line.rgb, fade)); y += 12;
        }
        graphics.text(client.font, Component.translatable("worldsmith.arrival.skip"), x + 14, top + panelHeight - 10, color(0x97AA9F, fade));
    }

    private record Line(FormattedCharSequence text, int rgb, boolean centered) {}
    private static void add(List<Line> lines, Component text, int width, int limit, int rgb, boolean centered) {
        var font = Minecraft.getInstance().font;
        for (var line : font.split(text, width).stream().limit(limit).toList()) lines.add(new Line(line, rgb, centered));
    }
    private static int color(int rgb, float alpha) { return (Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24) | rgb; }
}
