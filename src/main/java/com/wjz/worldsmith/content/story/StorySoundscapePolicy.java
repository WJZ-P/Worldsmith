package com.wjz.worldsmith.content.story;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded deterministic presentation policy; facts and eligibility were already decided by the server. */
public final class StorySoundscapePolicy {
    private StorySoundscapePolicy() {}
    public static List<StoryProtocol.SoundCue> select(List<StoryProtocol.SoundCue> input) {
        var sorted = input.stream().filter(c -> c.volume() > 0)
            .sorted(Comparator.comparingInt(StoryProtocol.SoundCue::priority).reversed().thenComparing(StoryProtocol.SoundCue::key)).toList();
        var result = new ArrayList<StoryProtocol.SoundCue>(); boolean music = false; int ambience = 0;
        for (var cue : sorted) {
            if (cue.music()) { if (music) continue; music = true; }
            else { if (ambience >= 4) continue; ambience++; }
            result.add(cue);
        }
        return List.copyOf(result);
    }
    public static float ambienceScale(List<StoryProtocol.SoundCue> selected) {
        double total = selected.stream().filter(c -> !c.music()).mapToDouble(StoryProtocol.SoundCue::volume).sum();
        return total > .8 ? (float)(.8 / total) : 1;
    }
    /** Repeated snapshots holding the same target never reset the fade progress. */
    public static final class Envelope {
        private float value, start, target;
        private int elapsed, duration;
        public void target(float next, int ticks) {
            if (!Float.isFinite(next) || next < 0 || next > 1 || ticks < 0) throw new IllegalArgumentException("Invalid sound envelope");
            if (Float.compare(target, next) == 0) return;
            start = value; target = next; duration = ticks; elapsed = 0;
            if (duration == 0) value = target;
        }
        public float tick() {
            if (elapsed < duration) { elapsed++; value = elapsed == duration ? target : start + (target - start) * elapsed / duration; }
            return value;
        }
        public float value() { return value; }
        public boolean silent() { return value <= .0001f && target <= .0001f; }
    }
}
