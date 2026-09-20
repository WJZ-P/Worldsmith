package com.wjz.worldsmith.ability;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityActorMemoryTest {
    private static final String SCOPE = "a".repeat(64);

    @Test void persistentStateAndCooldownRoundTripWithoutContinuation() {
        Map<String, com.wjz.worldsmith.core.ability.AbilityValue> state = Map.of("phase", AbilityValues.number(2), "marks", AbilityValues.list(java.util.List.of(AbilityValues.vector(1, 2, 3))));
        var memory = AbilityActorMemory.empty(SCOPE).with("echo", 240, state);
        var json = AbilityActorMemory.CODEC.encodeStart(JsonOps.INSTANCE, memory).getOrThrow();
        var restored = AbilityActorMemory.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(memory, restored);
        assertEquals(state, restored.entry("echo").state());
        assertEquals(240, restored.entry("echo").readyAt());
        assertFalse(json.toString().contains("programCounter"));
        assertFalse(json.toString().contains("target"));
    }

    @Test void invalidEntryProducesNoPartialRestoredMemory() {
        var valid = AbilityActorMemory.empty(SCOPE).with("echo", 10, Map.of("phase", AbilityValues.number(1)));
        var json = AbilityActorMemory.CODEC.encodeStart(JsonOps.INSTANCE, valid).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("programs").add("broken", JsonParser.parseString("{\"readyAt\":-1,\"state\":\"{}\"}"));
        var result = AbilityActorMemory.CODEC.parse(JsonOps.INSTANCE, json);
        assertTrue(result.error().isPresent());
        assertTrue(result.resultOrPartial(message -> {}).isEmpty());
    }

    @Test void actorAggregateBudgetIsCheckedBeforePublication() {
        Map<String, com.wjz.worldsmith.core.ability.AbilityValue> state = Map.of("text", AbilityValues.text("x".repeat(4000)));
        var memory = AbilityActorMemory.empty(SCOPE);
        for (int i = 0; i < 15; i++) memory = memory.with("program_" + i, i, state);
        var full = memory;
        assertThrows(IllegalArgumentException.class, () -> full.with("overflow", 30,
            Map.of("first", AbilityValues.text("x".repeat(4000)), "second", AbilityValues.text("y".repeat(4000)))));
        assertEquals(15, memory.programs().size());
    }

    @Test void entriesAreDetachedAndImmutable() {
        var mutable = new LinkedHashMap<String, AbilityActorMemory.Entry>();
        mutable.put("echo", new AbilityActorMemory.Entry(4, "{}"));
        var memory = new AbilityActorMemory(SCOPE, mutable);
        mutable.clear();
        assertEquals(1, memory.programs().size());
        assertThrows(UnsupportedOperationException.class, () -> memory.programs().clear());
        assertEquals(0, memory.entry("unused").readyAt());
        assertTrue(memory.entry("unused").state().isEmpty());
    }

    @Test void cooldownRefreshReusesTheValidatedPayloadWithoutParsingOrCopyingIt() {
        var entry = new AbilityActorMemory.Entry(5, AbilityValues.encodeState(Map.of("text", AbilityValues.text("x".repeat(4000)))));
        var memory = AbilityActorMemory.empty(SCOPE).withEntry("echo", entry);
        for (int tick = 6; tick < 1006; tick++) memory = memory.withEntry("echo", memory.entry("echo").withReadyAt(tick));
        assertEquals(1005, memory.entry("echo").readyAt());
        assertSame(entry.state(), memory.entry("echo").state());
        assertSame(entry.stateJson(), memory.entry("echo").stateJson());
        assertThrows(UnsupportedOperationException.class, () -> memoryEntryState(entry).clear());
        var restored = AbilityActorMemory.CODEC.parse(JsonOps.INSTANCE,
            AbilityActorMemory.CODEC.encodeStart(JsonOps.INSTANCE, memory).getOrThrow()).getOrThrow();
        assertEquals(memory, restored);
    }

    private static Map<String, com.wjz.worldsmith.core.ability.AbilityValue> memoryEntryState(AbilityActorMemory.Entry entry) { return entry.state(); }

    @Test void newStateIsDetachedBeforeEncodingAndOldPayloadStaysUnchanged() {
        var mutable = new LinkedHashMap<String, com.wjz.worldsmith.core.ability.AbilityValue>();
        mutable.put("phase", AbilityValues.number(1));
        var first = AbilityActorMemory.empty(SCOPE).with("echo", 5, mutable);
        mutable.put("phase", AbilityValues.number(2));
        var second = first.with("echo", 6, mutable);
        mutable.clear();
        assertEquals(AbilityValues.number(1), first.entry("echo").state().get("phase"));
        assertEquals(AbilityValues.number(2), second.entry("echo").state().get("phase"));
        assertSame(first.entry("echo").state(), first.entry("echo").state());
        assertNotSame(first.entry("echo").state(), second.entry("echo").state());
    }

    @Test void cachedPayloadsStillCountTowardsTheWholeActorsByteLimit() {
        var entry = AbilityActorMemory.Entry.fromState(4, Map.of("text", AbilityValues.text("x".repeat(4000))));
        var memory = AbilityActorMemory.empty(SCOPE);
        for (int i = 0; i < 16; i++) memory = memory.withEntry("p" + i, entry);
        var full = memory;
        assertThrows(IllegalArgumentException.class, () -> full.withEntry("overflow", entry.withReadyAt(99)));
        assertEquals(16, full.programs().size());
        assertEquals(4, full.entry("p0").readyAt());
    }

    @Test void refreshingOneProgramPreservesOtherProgramsAndCodecShape() {
        var first = AbilityActorMemory.empty(SCOPE).with("one", 10, Map.of("phase", AbilityValues.number(1)))
            .with("two", 20, Map.of("phase", AbilityValues.number(2)));
        var second = first.withEntry("one", first.entry("one").withReadyAt(40));
        assertSame(first.entry("two"), second.entry("two"));
        assertEquals(20, second.entry("two").readyAt());
        var json = AbilityActorMemory.CODEC.encodeStart(JsonOps.INSTANCE, second).getOrThrow().getAsJsonObject();
        assertEquals(java.util.Set.of("scope", "programs"), json.keySet());
        assertEquals(java.util.Set.of("readyAt", "state"), json.getAsJsonObject("programs").getAsJsonObject("one").keySet());
        assertTrue(json.getAsJsonObject("programs").getAsJsonObject("one").get("state").getAsJsonPrimitive().isString());
    }

    @Test void unchangedCooldownAndMissingEntriesDoNotAllocateNewPayloads() {
        var memory = AbilityActorMemory.empty(SCOPE);
        assertSame(memory.entry("unused"), memory.entry("another"));
        var entry = new AbilityActorMemory.Entry(4, "{}");
        assertSame(entry, entry.withReadyAt(4));
        memory = memory.withEntry("echo", entry);
        assertSame(memory, memory.withEntry("echo", entry));
        assertThrows(IllegalArgumentException.class, () -> entry.withReadyAt(-1));
        var existing = memory;
        assertThrows(NullPointerException.class, () -> existing.withEntry("missing", null));
    }
}
