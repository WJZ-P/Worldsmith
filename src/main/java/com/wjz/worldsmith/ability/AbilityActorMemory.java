package com.wjz.worldsmith.ability;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Only program-owned state and restart cooldown survive saving. No instruction pointer or live handle is resumed. */
public record AbilityActorMemory(String scope, Map<String, Entry> programs) {
    private static final Pattern SCOPE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROGRAM = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    /** Immutable validated payload is shared by cooldown-only updates, never reparsed each tick. */
    public static final class Entry {
        private final long readyAt;
        private final Payload payload;
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Negative ability cooldown"))
                .fieldOf("readyAt").forGetter(Entry::readyAt),
            Codec.STRING.fieldOf("state").forGetter(Entry::stateJson)
        ).apply(instance, Entry::new));
        public Entry(long readyAt, String stateJson) {
            this(readyAt, Payload.decode(stateJson));
        }
        private Entry(long readyAt, Payload payload) {
            if (readyAt < 0) throw new IllegalArgumentException("Invalid ability memory cooldown");
            this.readyAt = readyAt; this.payload = payload;
        }
        public long readyAt() { return readyAt; }
        public String stateJson() { return payload.json; }
        public Map<String, AbilityValue> state() { return payload.values; }
        public Entry withReadyAt(long value) { return value == readyAt ? this : new Entry(value, payload); }
        static Entry fromState(long readyAt, Map<String, AbilityValue> values) {
            var frozen = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            String json = AbilityValues.encodeState(frozen);
            return new Entry(readyAt, new Payload(json, frozen, json.getBytes(StandardCharsets.UTF_8).length));
        }
        @Override public boolean equals(Object other) {
            return other instanceof Entry entry && readyAt == entry.readyAt && payload.json.equals(entry.payload.json);
        }
        @Override public int hashCode() { return Objects.hash(readyAt, payload.json); }
        @Override public String toString() { return "Entry[readyAt=" + readyAt + ", stateJson=" + payload.json + "]"; }
        private record Payload(String json, Map<String, AbilityValue> values, int bytes) {
            static Payload decode(String json) {
                if (json == null) throw new IllegalArgumentException("Invalid ability memory state");
                var values = AbilityValues.decodeState(json);
                return new Payload(json, values, json.getBytes(StandardCharsets.UTF_8).length);
            }
        }
    }

    private static final Entry EMPTY_ENTRY = new Entry(0, "{}");

    private static final Codec<AbilityActorMemory> DELEGATE = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("scope").forGetter(AbilityActorMemory::scope),
        Codec.unboundedMap(Codec.STRING, Entry.CODEC).fieldOf("programs").forGetter(AbilityActorMemory::programs)
    ).apply(instance, AbilityActorMemory::new));
    public static final Codec<AbilityActorMemory> CODEC = new Codec<>() {
        @Override public <T> DataResult<Pair<AbilityActorMemory, T>> decode(DynamicOps<T> ops, T input) {
            try {
                var value = DELEGATE.decode(ops, input);
                return value.error().isPresent() ? DataResult.error(() -> value.error().orElseThrow().message()) : value;
            } catch (IllegalArgumentException invalid) { return DataResult.error(() -> "Invalid ability memory: " + invalid.getMessage()); }
        }
        @Override public <T> DataResult<T> encode(AbilityActorMemory value, DynamicOps<T> ops, T prefix) { return DELEGATE.encode(value, ops, prefix); }
    };

    public AbilityActorMemory {
        if (scope == null || !SCOPE.matcher(scope).matches() || programs == null || programs.size() > 64)
            throw new IllegalArgumentException("Invalid ability memory scope/count");
        int bytes = 0;
        for (var entry : programs.entrySet()) {
            if (entry.getKey() == null || !PROGRAM.matcher(entry.getKey()).matches() || entry.getValue() == null)
                throw new IllegalArgumentException("Invalid ability memory program id");
            bytes = Math.addExact(bytes, entry.getValue().payload.bytes);
        }
        if (bytes > 65536) throw new IllegalArgumentException("Total actor ability state exceeds 64 KiB");
        programs = Collections.unmodifiableMap(new LinkedHashMap<>(programs));
    }
    public static AbilityActorMemory empty(String scope) { return new AbilityActorMemory(scope, Map.of()); }
    public Entry entry(String program) { return programs.getOrDefault(program, EMPTY_ENTRY); }
    public AbilityActorMemory with(String program, long readyAt, Map<String, AbilityValue> state) {
        return withEntry(program, Entry.fromState(readyAt, state));
    }
    /** Reuses the already validated immutable state while preserving aggregate actor limits. */
    public AbilityActorMemory withEntry(String program, Entry entry) {
        Objects.requireNonNull(entry);
        if (programs.get(program) == entry) return this;
        Map<String, Entry> next = new LinkedHashMap<>(programs);
        next.put(program, entry);
        return new AbilityActorMemory(scope, next);
    }
}
