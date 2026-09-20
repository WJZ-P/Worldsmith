package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in microbenchmark, not a timing-dependent regression test or a server TPS claim. */
public final class AbilityPersistenceBenchmark {
    private static final String SCOPE = "a".repeat(64);
    private static volatile Object sink;
    private record Measurement(long nanoseconds, long allocatedBytes) {}
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    // Retains the previous hot path: VM map copy, whole state encode, Entry validation
    // decode, actor aggregate UTF-8 recount, and detached actor map on every tick.
    private record PreviousEntry(long readyAt, String stateJson) {
        PreviousEntry { AbilityValues.decodeState(stateJson); }
    }
    private record PreviousMemory(Map<String, PreviousEntry> programs) {
        PreviousMemory {
            if (!SCOPE.matches("[0-9a-f]{64}") || programs.size() > 64) throw new IllegalArgumentException();
            int bytes = 0;
            for (var entry : programs.entrySet()) {
                if (!entry.getKey().matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException();
                bytes += entry.getValue().stateJson.getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > 65536) throw new IllegalArgumentException();
            programs = Collections.unmodifiableMap(new LinkedHashMap<>(programs));
        }
        PreviousMemory with(long tick, Map<String, AbilityValue> snapshot) {
            var next = new LinkedHashMap<>(programs);
            next.put("echo", new PreviousEntry(tick, AbilityValues.encodeState(snapshot)));
            return new PreviousMemory(next);
        }
    }

    private static Measurement measure(Runnable operation) {
        long thread = Thread.currentThread().threadId();
        long bytes = THREADS.getThreadAllocatedBytes(thread), start = System.nanoTime();
        operation.run();
        return new Measurement(System.nanoTime() - start, THREADS.getThreadAllocatedBytes(thread) - bytes);
    }

    public static void main(String[] args) throws Exception {
        if (!THREADS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter is unavailable");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        Map<String, AbilityValue> state = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) state.put("text" + i, AbilityValues.text("x".repeat(4096)));
        state.put("phase", AbilityValues.number(3));
        state = Collections.unmodifiableMap(state);
        final var snapshot = state;
        final var initial = AbilityActorMemory.empty(SCOPE).with("echo", 0, state);
        final int ticks = 512, samples = 7;
        Runnable before = () -> {
            var memory = new PreviousMemory(Map.of());
            for (int tick = 1; tick <= ticks; tick++) memory = memory.with(tick, Collections.unmodifiableMap(new LinkedHashMap<>(snapshot)));
            sink = memory;
        };
        Runnable after = () -> {
            var memory = initial;
            for (int tick = 1; tick <= ticks; tick++) memory = memory.withEntry("echo", memory.entry("echo").withReadyAt(tick));
            if (memory.entry("echo").readyAt() != ticks || !memory.entry("echo").state().equals(snapshot)) throw new AssertionError("Different benchmark result");
            sink = memory;
        };
        for (int i = 0; i < 3; i++) { before.run(); after.run(); }
        var old = new Measurement[samples]; var optimized = new Measurement[samples];
        for (int i = 0; i < samples; i++) { old[i] = measure(before); optimized[i] = measure(after); }
        long oldNanos = median(old, true), newNanos = median(optimized, true);
        long oldBytes = median(old, false), newBytes = median(optimized, false);
        String report = String.format(java.util.Locale.ROOT, """
            {"scenario":"512 unchanged-state cooldown refreshes", "stateUtf8Bytes":%d, "samples":%d,
             "beforeMedianNanoseconds":%d, "afterMedianNanoseconds":%d,
             "beforeMedianAllocatedBytes":%d, "afterMedianAllocatedBytes":%d,
             "allocationReductionPercent":%.4f, "serializationBefore":512, "serializationAfter":0,
             "note":"Warm isolated hot-path microbenchmark; baseline retains the previous algorithm, not a whole-server TPS estimate."}
            """, AbilityValues.encodeState(snapshot).getBytes(StandardCharsets.UTF_8).length, samples,
                oldNanos, newNanos, oldBytes, newBytes, 100.0 * (oldBytes - newBytes) / oldBytes);
        System.out.println(report);
        if (args.length == 1) { Path output = Path.of(args[0]); Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, report); }
    }
    private static long median(Measurement[] samples, boolean time) {
        return java.util.Arrays.stream(samples).mapToLong(sample -> time ? sample.nanoseconds : sample.allocatedBytes).sorted().toArray()[samples.length / 2];
    }
}
