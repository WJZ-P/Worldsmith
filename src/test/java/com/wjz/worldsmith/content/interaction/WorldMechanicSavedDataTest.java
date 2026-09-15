package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMechanicSavedDataTest {
    private static final String HASH = "a".repeat(64);
    private static WorldMechanicDefinition definition() {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldMechanicLibrary.Companion.serializer(), """
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"open","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"minecraft:stone"}}],
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:air"}}]}]}]}
            """).getMechanics().getFirst();
    }
    private static WorldMechanicSavedData bound(WorldMechanicDefinition definition) {
        var data = new WorldMechanicSavedData(); data.bind(HASH, Map.of(definition.getId(), definition)); return data;
    }

    @Test void successCommitsOnceAndRoundTripsInNativeNbt() {
        var definition = definition(); var rule = definition.getRules().getFirst(); var data = bound(definition);
        String key = WorldMechanicSavedData.key("gate", 123L);
        var update = data.plan(key, definition, rule, 100L);
        assertNull(data.progress(key)); assertEquals(0, data.state().revision());
        update.commit();
        assertEquals("active", data.progress(key).state()); assertEquals(120L, data.progress(key).readyAt());
        assertEquals(1, data.progress(key).activations()); assertEquals(1, data.state().revision()); assertTrue(data.isDirty());
        assertFalse(data.eligible(key, definition, rule, 9999L));
        assertThrows(IllegalStateException.class, update::commit);
        var tag = WorldMechanicSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        var restored = WorldMechanicSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        restored.bind(HASH, Map.of("gate", definition));
        assertEquals(data.state(), restored.state()); assertFalse(restored.eligible(key, definition, rule, 9999L));
    }

    @Test void abandonedPlanHasNoDurableEffectAndStalePlanIsRejected() {
        var definition = definition(); var rule = definition.getRules().getFirst(); var data = bound(definition);
        var old = data.plan("gate@1", definition, rule, 0L);
        assertTrue(data.state().instances().isEmpty());
        data.plan("gate@2", definition, rule, 0L).commit();
        assertThrows(IllegalStateException.class, old::commit);
        assertNull(data.progress("gate@1")); assertNotNull(data.progress("gate@2"));
    }

    @Test void repeatableConversionsHonorCooldownPerAnchor() {
        var original = definition();
        var rule = new WorldMechanicRule("exchange", WorldMechanicEvent.USE_BLOCK,
            original.getRules().getFirst().getPattern(), java.util.List.of(new MechanicAction.GiveItem("minecraft:diamond")),
            "idle", "idle", true, new MechanicItemCost("minecraft:emerald"), 20, java.util.List.of());
        var definition = new WorldMechanicDefinition("gate", "Gate", "idle", java.util.List.of("idle"), java.util.List.of(rule), "");
        var data = bound(definition);
        data.plan("gate@1", definition, rule, 5L).commit();
        assertFalse(data.eligible("gate@1", definition, rule, 24L));
        assertTrue(data.eligible("gate@1", definition, rule, 25L));
        assertTrue(data.eligible("gate@2", definition, rule, 6L));
        data.plan("gate@1", definition, rule, 25L).commit();
        assertEquals(2L, data.progress("gate@1").activations());
    }

    @Test void foreignBundleAndMissingDefinitionNeverResetTheLedger() {
        var definition = definition(); var data = bound(definition);
        data.plan("gate@1", definition, definition.getRules().getFirst(), 0L).commit();
        var prior = data.state();
        assertThrows(IllegalStateException.class, () -> data.bind("b".repeat(64), Map.of("gate", definition)));
        assertThrows(IllegalStateException.class, () -> data.bind(HASH, Map.of()));
        assertSame(prior, data.state());
    }

    @Test void fullLedgerRejectsNewAnchorsWithoutErasingOldEntries() {
        Map<String, WorldMechanicSavedData.Progress> entries = new LinkedHashMap<>();
        for (int i = 0; i < WorldMechanicSavedData.MAX_INSTANCES; i++) entries.put("gate@" + i, new WorldMechanicSavedData.Progress("active", 20, 1));
        var data = new WorldMechanicSavedData(new WorldMechanicSavedData.State(1, HASH, 1, entries));
        entries.clear();
        var definition = definition(); data.bind(HASH, Map.of("gate", definition));
        assertEquals(WorldMechanicSavedData.MAX_INSTANCES, data.state().instances().size());
        assertFalse(data.eligible("gate@99999", definition, definition.getRules().getFirst(), 20L));
        assertThrows(UnsupportedOperationException.class, () -> data.state().instances().clear());
    }

    @Test void invalidCountersKeysAndOverflowFailBeforeCommit() {
        assertThrows(IllegalArgumentException.class, () -> new WorldMechanicSavedData.Progress("idle", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldMechanicSavedData.Progress("idle", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldMechanicSavedData.State(1, HASH, 0,
            Map.of("gate@00", new WorldMechanicSavedData.Progress("idle", 0, 1))));
        var definition = definition(); var data = bound(definition);
        assertThrows(ArithmeticException.class, () -> data.plan("gate@1", definition, definition.getRules().getFirst(), Long.MAX_VALUE));
        assertTrue(data.state().instances().isEmpty());
    }

    @Test void incompleteCompensationQuarantinesTheLedgerAndInvalidatesPendingPlans() {
        var definition = definition(); var rule = definition.getRules().getFirst(); var data = bound(definition);
        var pending = data.plan("gate@1", definition, rule, 0);
        data.quarantine();
        assertTrue(data.state().quarantined()); assertFalse(data.eligible("gate@1", definition, rule, 100));
        assertThrows(IllegalStateException.class, pending::commit);
        var tag = WorldMechanicSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        var restored = WorldMechanicSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertTrue(restored.state().quarantined());
        assertThrows(IllegalStateException.class, () -> restored.bind(HASH, Map.of("gate", definition)));
    }
}
