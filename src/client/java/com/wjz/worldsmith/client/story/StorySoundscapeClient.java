package com.wjz.worldsmith.client.story;

import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.content.story.StoryProtocol;
import com.wjz.worldsmith.content.story.StorySoundscapePolicy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Native audio playback with stable keys, per-layer cooldowns, reversible fades and bounded voice count. */
public final class StorySoundscapeClient {
    private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();
    private static long clock, captionUntil;
    private static Component caption = Component.empty();
    private StorySoundscapeClient() {}
    private static final class Layer {
        StoryProtocol.SoundCue cue;
        FadingSound sound;
        long nextPlay, seen;
        Layer(StoryProtocol.SoundCue cue) { this.cue = cue; }
    }
    static void tick(Minecraft client, List<StoryProtocol.SoundCue> incoming) {
        if (client.level == null) { clear(client); return; }
        if (client.isPaused()) return;
        clock++;
        var selected = StorySoundscapePolicy.select(incoming);
        boolean audible = WorldsmithConfig.get().getClient().getStoryAudio();
        float scale = StorySoundscapePolicy.ambienceScale(selected);
        var wanted = new HashSet<String>();
        boolean ownsMusic = false;
        boolean captionThisTick = false;
        for (var cue : selected) {
            wanted.add(cue.key()); Layer layer = LAYERS.computeIfAbsent(cue.key(), ignored -> new Layer(cue)); layer.seen = clock;
            if (!layer.cue.sound().equals(cue.sound()) || layer.cue.pitch() != cue.pitch() || layer.cue.music() != cue.music()) {
                if (layer.sound != null) client.getSoundManager().stop(layer.sound);
                layer.sound = null; layer.nextPlay = clock;
            }
            layer.cue = cue;
            if (layer.sound != null && !client.getSoundManager().isActive(layer.sound)) layer.sound = null;
            float target = audible ? cue.volume() * (cue.music() ? .65f : scale) : 0;
            if (layer.sound == null && clock >= layer.nextPlay) {
                // Captions remain meaningful even when the story-audio toggle is off.
                if (!captionThisTick && !cue.subtitle().isBlank()) { caption = Component.literal(cue.subtitle()); captionUntil = clock + 100; captionThisTick = true; }
                layer.nextPlay = clock + cue.periodTicks();
                if (target > 0) {
                    var event = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(cue.sound())).orElse(null);
                    if (event != null) {
                        layer.sound = new FadingSound(event, cue.music()); layer.sound.pitch(cue.pitch());
                        layer.sound.target(target, cue.fadeTicks()); client.getSoundManager().play(layer.sound);
                        if (cue.music()) client.getMusicManager().stopPlaying();
                    }
                }
            }
            if (layer.sound != null) { layer.sound.target(target, cue.fadeTicks()); ownsMusic |= cue.music() && audible; }
        }
        for (var entry : LAYERS.entrySet()) {
            Layer layer = entry.getValue();
            if (!wanted.contains(entry.getKey()) && layer.sound != null) {
                layer.sound.target(0, layer.cue.fadeTicks());
                if (!client.getSoundManager().isActive(layer.sound)) layer.sound = null;
            }
        }
        // MusicManager owns only its vanilla instance, not these story-owned native sounds.
        // Keep vanilla scheduling dormant during a story track, restoring it automatically on exit.
        if (ownsMusic && clock % 20 == 0) client.getMusicManager().stopPlaying();
        // Rapid border crossings may leave outgoing fades alive. Cap overlapping native voices as well as selected layers.
        while (activeLayers() > 10) {
            var retiring = LAYERS.entrySet().stream().filter(e -> !wanted.contains(e.getKey()) && e.getValue().sound != null)
                .min(java.util.Comparator.comparingLong(e -> e.getValue().seen));
            if (retiring.isEmpty()) break;
            client.getSoundManager().stop(retiring.get().getValue().sound); retiring.get().getValue().sound = null;
        }
        LAYERS.entrySet().removeIf(entry -> entry.getValue().sound == null && !wanted.contains(entry.getKey())
            && clock - entry.getValue().seen > Math.max(1200, entry.getValue().cue.periodTicks()));
        while (LAYERS.size() > 128) {
            var oldest = LAYERS.entrySet().stream().filter(e -> !wanted.contains(e.getKey()) && e.getValue().sound == null)
                .min(java.util.Comparator.comparingLong(e -> e.getValue().seen));
            if (oldest.isEmpty()) break; LAYERS.remove(oldest.get().getKey());
        }
    }
    public static int activeLayers() { return (int)LAYERS.values().stream().filter(l -> l.sound != null).count(); }
    static void renderCaption(GuiGraphicsExtractor graphics, Minecraft client) {
        if (!client.options.showSubtitles().get() || clock >= captionUntil || caption.getString().isBlank()) return;
        int maxWidth = Math.min(380, graphics.guiWidth() - 32); var lines = client.font.split(caption, maxWidth);
        int y = graphics.guiHeight() - 68 - Math.min(2, lines.size()) * 12;
        for (var line : lines.stream().limit(2).toList()) {
            int w = client.font.width(line), x = (graphics.guiWidth() - w) / 2;
            graphics.fill(x - 5, y - 2, x + w + 5, y + 10, 0xC0121D1B); graphics.text(client.font, line, x, y, 0xFFE9E9D9); y += 12;
        }
    }
    static void clear(Minecraft client) {
        LAYERS.values().forEach(layer -> { if (layer.sound != null) client.getSoundManager().stop(layer.sound); });
        LAYERS.clear(); clock = 0; captionUntil = 0; caption = Component.empty();
    }
    private static final class FadingSound extends AbstractTickableSoundInstance {
        private final StorySoundscapePolicy.Envelope envelope = new StorySoundscapePolicy.Envelope();
        private boolean fadingOut;
        FadingSound(SoundEvent event, boolean music) {
            super(event, music ? SoundSource.MUSIC : SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            relative = true; attenuation = SoundInstance.Attenuation.NONE; looping = false; volume = 0;
        }
        void pitch(float value) { pitch = value; }
        void target(float value, int ticks) { fadingOut = value == 0; envelope.target(value, ticks); }
        @Override public void tick() { volume = envelope.tick(); if (fadingOut && envelope.silent()) stop(); }
        @Override public boolean canStartSilent() { return true; }
    }
}
