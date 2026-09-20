package com.wjz.worldsmith.core.ability.visual;

import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.content.CreatureDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable additive bone tracks. Model translations are pixels, rotations are degrees, scale is a factor. */
public record AbilityClip(int durationTicks, int blendTicks, List<AbilityClip.Track> tracks) {
    public static final int MAX_TRACKS = 64, MAX_FRAMES = 256, MAX_DURATION = 1200;
    public record Vector(float x, float y, float z) {
        public Vector { finite(x); finite(y); finite(z); }
        public double length() { return Math.sqrt(x * x + y * y + z * z); }
    }
    public record Transform(Vector translation, Vector rotation, Vector scale) {
        public Transform {
            java.util.Objects.requireNonNull(translation); java.util.Objects.requireNonNull(rotation); java.util.Objects.requireNonNull(scale);
            range(translation, -32, 32, "translation"); range(rotation, -720, 720, "rotation"); range(scale, .1F, 4, "scale");
        }
    }
    public record Keyframe(int tick, Transform transform) {
        public Keyframe { if (tick < 0 || tick > MAX_DURATION) throw new IllegalArgumentException("Clip frame time is outside 0..1200"); java.util.Objects.requireNonNull(transform); }
    }
    public record Track(String bone, List<Keyframe> frames) {
        public Track {
            if (bone == null || bone.isBlank() || bone.length() > 96 || bone.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("A clip track needs a bounded bone id");
            frames = List.copyOf(frames);
            if (frames.isEmpty() || frames.size() > 64 || frames.getFirst().tick() != 0) throw new IllegalArgumentException("Each track needs 1..64 frames, beginning at tick zero");
            int previous = -1;
            for (var frame : frames) {
                if (frame.tick() <= previous) throw new IllegalArgumentException("Clip frame times must strictly increase");
                previous = frame.tick();
            }
        }
    }
    public AbilityClip {
        if (durationTicks < 1 || durationTicks > MAX_DURATION || blendTicks < 0 || blendTicks > Math.min(40, durationTicks / 2))
            throw new IllegalArgumentException("Clip duration is 1..1200; blend is 0..min(40,duration/2)");
        tracks = List.copyOf(tracks);
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) throw new IllegalArgumentException("A clip needs 1..64 bone tracks");
        int frames = 0; var names = new HashSet<String>();
        for (var track : tracks) {
            if (!names.add(track.bone())) throw new IllegalArgumentException("Duplicate clip bone: " + track.bone());
            frames += track.frames().size();
            if (track.frames().getLast().tick() > durationTicks) throw new IllegalArgumentException("A frame lies after the clip duration");
        }
        if (frames > MAX_FRAMES) throw new IllegalArgumentException("A clip supports at most 256 total keyframes");
    }

    /** Source data: [[bone, [[tick, translation, rotationDegrees, scale], ...]], ...]. */
    public static AbilityClip parse(AbilityValue value, int duration, int blend) {
        var tracks = new ArrayList<Track>();
        for (var entry : list(value, MAX_TRACKS, "clip tracks")) {
            var fields = exact(entry, 2, "track");
            if (!(fields.getFirst() instanceof AbilityValue.TextValue text)) throw new IllegalArgumentException("Track bone must be text");
            var frames = new ArrayList<Keyframe>();
            for (var frame : list(fields.get(1), 64, "track frames")) {
                var parts = exact(frame, 4, "keyframe");
                frames.add(new Keyframe(integer(parts.get(0), 0, MAX_DURATION, "frame tick"),
                    new Transform(vector(parts.get(1)), vector(parts.get(2)), vector(parts.get(3)))));
            }
            tracks.add(new Track(text.getValue(), frames));
        }
        return new AbilityClip(duration, blend, tracks);
    }

    /** Reject unknown bones and compounded scaling that would escape the renderer's 32-block bound. */
    public void validateFor(CreatureDefinition creature) {
        if (creature == null) throw new IllegalArgumentException("Animation requires a resolved creature model");
        var byBone = new HashMap<String, Track>(); tracks.forEach(track -> byBone.put(track.bone(), track));
        var known = new HashSet<String>(); creature.getModel().getBones().forEach(bone -> known.add(bone.getId()));
        for (String id : byBone.keySet()) if (!known.contains(id)) throw new IllegalArgumentException("Unknown animation bone: " + id);
        record Bound(double offset, double scale) {}
        var bounds = new HashMap<String, Bound>();
        var pending = new ArrayList<>(creature.getModel().getBones());
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var bone = iterator.next();
                var parent = bone.getParent() == null ? new Bound(0, 1) : bounds.get(bone.getParent());
                if (parent == null) continue;
                double shift = 0, scale = 1;
                var track = byBone.get(bone.getId());
                if (track != null) for (var frame : track.frames()) {
                    shift = Math.max(shift, frame.transform().translation().length());
                    var s = frame.transform().scale(); scale = Math.max(scale, Math.max(s.x(), Math.max(s.y(), s.z())));
                }
                var pivot = bone.getPivot();
                double offset = parent.offset() + parent.scale() * (Math.sqrt(pivot.getX() * pivot.getX() + pivot.getY() * pivot.getY() + pivot.getZ() * pivot.getZ()) + shift);
                double worldScale = parent.scale() * scale;
                double radius = 0;
                for (var cube : bone.getCubes()) {
                    var o = cube.getOrigin(); var s = cube.getSize();
                    double x = Math.max(Math.abs(o.getX()), Math.abs(o.getX() + s.getX()));
                    double y = Math.max(Math.abs(o.getY()), Math.abs(o.getY() + s.getY()));
                    double z = Math.max(Math.abs(o.getZ()), Math.abs(o.getZ() + s.getZ()));
                    radius = Math.max(radius, Math.sqrt(x * x + y * y + z * z));
                }
                if (!Double.isFinite(offset + worldScale * radius) || offset + worldScale * radius > 512 || worldScale > 64)
                    throw new IllegalArgumentException("Animation exceeds the 32-block rendered skeleton bound at " + bone.getId());
                bounds.put(bone.getId(), new Bound(offset, worldScale)); iterator.remove();
            }
            if (before == pending.size()) throw new IllegalArgumentException("Invalid creature bone hierarchy");
        }
    }

    /** Shared client/offline sampler, with a symmetric smoothstep blend to the existing procedural pose. */
    public Map<String, Transform> sample(double elapsedTicks) {
        if (!Double.isFinite(elapsedTicks)) throw new IllegalArgumentException("Clip time must be finite");
        if (elapsedTicks < 0 || elapsedTicks >= durationTicks) return Map.of();
        float weight = 1;
        if (blendTicks > 0) {
            double t = Math.min(1, Math.min(elapsedTicks / blendTicks, (durationTicks - elapsedTicks) / blendTicks));
            weight = (float)(t * t * (3 - 2 * t));
        }
        var result = new LinkedHashMap<String, Transform>();
        for (var track : tracks) {
            var frames = track.frames();
            int left = 0, right = frames.size() - 1;
            while (left < right) {
                int middle = (left + right + 1) >>> 1;
                if (frames.get(middle).tick() <= elapsedTicks) left = middle; else right = middle - 1;
            }
            var a = frames.get(left);
            var b = frames.get(Math.min(left + 1, frames.size() - 1));
            float t = a == b ? 0 : (float)Math.min(1, (elapsedTicks - a.tick()) / (b.tick() - a.tick()));
            var translation = lerp(a.transform().translation(), b.transform().translation(), t);
            var rotation = lerp(a.transform().rotation(), b.transform().rotation(), t);
            var scale = lerp(a.transform().scale(), b.transform().scale(), t);
            result.put(track.bone(), new Transform(multiply(translation, weight), multiply(rotation, weight),
                new Vector(1 + (scale.x() - 1) * weight, 1 + (scale.y() - 1) * weight, 1 + (scale.z() - 1) * weight)));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Vector lerp(Vector a, Vector b, float t) { return new Vector(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t); }
    private static Vector multiply(Vector value, float scale) { return new Vector(value.x() * scale, value.y() * scale, value.z() * scale); }
    private static List<AbilityValue> list(AbilityValue value, int max, String label) {
        if (!(value instanceof AbilityValue.ListValue list) || list.getValues().isEmpty() || list.getValues().size() > max)
            throw new IllegalArgumentException(label + " must be a nonempty bounded list");
        return list.getValues();
    }
    private static List<AbilityValue> exact(AbilityValue value, int size, String label) {
        var values = list(value, size, label);
        if (values.size() != size) throw new IllegalArgumentException(label + " needs exactly " + size + " fields");
        return values;
    }
    private static Vector vector(AbilityValue value) {
        if (!(value instanceof AbilityValue.VectorValue v)) throw new IllegalArgumentException("Clip transform values must be vectors");
        return new Vector((float)v.getX(), (float)v.getY(), (float)v.getZ());
    }
    private static int integer(AbilityValue value, int min, int max, String label) {
        if (!(value instanceof AbilityValue.NumberValue n) || n.getValue() != Math.rint(n.getValue()) || n.getValue() < min || n.getValue() > max)
            throw new IllegalArgumentException("Invalid " + label);
        return (int)n.getValue();
    }
    private static void finite(float value) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Clip vectors must be finite"); }
    private static void range(Vector v, float min, float max, String label) {
        if (v.x() < min || v.y() < min || v.z() < min || v.x() > max || v.y() > max || v.z() > max)
            throw new IllegalArgumentException("Clip " + label + " is outside " + min + ".." + max);
    }
}
