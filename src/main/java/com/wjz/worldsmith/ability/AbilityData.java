package com.wjz.worldsmith.ability;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.wjz.worldsmith.core.ability.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, immutable values and all-or-nothing codecs shared by gameplay persistence. */
final class AbilityData {
    private AbilityData() {}
    static String id(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid gameplay key");
        return value;
    }
    static String scope(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid ability scope");
        return value;
    }
    static double bounded(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Gameplay value outside " + min + ".." + max);
        return value;
    }
    static <T> Codec<T> strict(Codec<T> delegate) {
        return new Codec<>() {
            public <R> DataResult<Pair<T,R>> decode(DynamicOps<R> ops, R input) {
                try {
                    var decoded = delegate.decode(ops, input);
                    return decoded.error().isPresent() ? DataResult.error(() -> decoded.error().orElseThrow().message()) : decoded;
                } catch (RuntimeException invalid) { return DataResult.error(() -> "Invalid gameplay data: " + invalid.getMessage()); }
            }
            public <R> DataResult<R> encode(T value, DynamicOps<R> ops, R prefix) { return delegate.encode(value, ops, prefix); }
        };
    }
    static final class Values {
        static final Values EMPTY = new Values(Map.of());
        static final Codec<Values> CODEC = strict(Codec.STRING.xmap(json -> new Values(AbilityValues.decodeState(json)), Values::json));
        private final Map<String,AbilityValue> values;
        private final String json;
        private final int bytes;
        Values(Map<String,AbilityValue> input) {
            if (input.size() > 64) throw new IllegalArgumentException("Shared value count exceeds 64");
            input.forEach((key,value) -> { id(key); persistent(value); });
            values = Collections.unmodifiableMap(new LinkedHashMap<>(input));
            json = AbilityValues.encodeState(values); bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 16384) throw new IllegalArgumentException("Shared state exceeds 16 KiB");
        }
        String json() { return json; }
        int bytes() { return bytes; }
        boolean empty() { return values.isEmpty(); }
        AbilityValue get(String key) { return values.getOrDefault(id(key), AbilityValues.none()); }
        Values with(String key, AbilityValue value) {
            id(key); persistent(value);
            var next = new LinkedHashMap<>(values);
            if (value instanceof AbilityValue.NullValue) next.remove(key); else next.put(key, value);
            return next.equals(values) ? this : new Values(next);
        }
    }
    private static void persistent(AbilityValue value) {
        AbilityValues.validate(value);
        if (value instanceof AbilityValue.EntityValue) throw new IllegalArgumentException("Transient entity handles cannot be persisted in shared state");
        if (value instanceof AbilityValue.MapValue map) map.getValues().values().forEach(AbilityData::persistent);
        if (value instanceof AbilityValue.ListValue list) list.getValues().forEach(AbilityData::persistent);
    }
}
