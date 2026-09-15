package com.wjz.worldsmith.content.quest.server;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.content.Quest;
import com.wjz.worldsmith.core.content.QuestObjective;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestMechanicFactsTest {
    private static final String SCOPE = "a".repeat(64);

    private static Quest activation(String id, String prerequisite, int count) {
        return new Quest(id, id, "Observe a committed device activation", prerequisite == null ? List.of() : List.of(prerequisite),
            List.of(new QuestObjective.ActivateMechanic("altar", count)), List.of(), null);
    }

    @Test void activationBeforeUnlockIsRetainedAndProjectedImmediatelyAfterPredecessorClaim() {
        Quest later = activation("later", "first", 1);
        QuestPlayerState state = QuestPlayerState.empty(SCOPE).withMechanicActivation("altar");
        assertEquals(1, state.mechanicActivationCount("altar"));
        assertTrue(state.quests().isEmpty(), "A locked quest does not receive synthetic persisted objective progress");
        assertEquals(List.of(0), state.progressFor(later).counts());
        assertFalse(state.questUnlocked(later));
        QuestPlayerState unlocked = state.withProgress("first", new QuestPlayerState.Progress(List.of(1), true));
        assertTrue(unlocked.questUnlocked(later));
        assertEquals(List.of(1), unlocked.progressFor(later).counts());
        assertFalse(unlocked.quests().containsKey("later"), "The retrospective projection itself is nonmutating");
        assertEquals(2, unlocked.revision());
        assertEquals(1, state.revision());
    }

    @Test void activeCountTracksOnlyCommittedFactsAndTheSameLifetimeFactMayBeObservedLater() {
        Quest first = activation("first", null, 2);
        Quest later = activation("later", "first", 1);
        QuestPlayerState empty = QuestPlayerState.empty(SCOPE);
        assertEquals(List.of(0), empty.progressFor(first).counts());
        QuestPlayerState once = empty.withMechanicActivation("altar");
        assertEquals(List.of(1), once.progressFor(first).counts());
        QuestPlayerState twice = once.withMechanicActivation("altar");
        assertEquals(List.of(2), twice.progressFor(first).counts());
        QuestPlayerState claimed = twice.withProgress("first", new QuestPlayerState.Progress(twice.progressFor(first).counts(), true));
        assertEquals(List.of(1), claimed.progressFor(later).counts());
        assertEquals(2, claimed.mechanicActivationCount("altar"));
    }

    @Test void countCapStopsUnboundedStorageAndSerializationPreservesFacts() {
        QuestPlayerState state = QuestPlayerState.empty(SCOPE);
        for (int i = 0; i < 1024; i++) state = state.withMechanicActivation("altar");
        assertEquals(1024, state.mechanicActivationCount("altar"));
        assertSame(state, state.withMechanicActivation("altar"));
        var encoded = QuestPlayerState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        assertEquals(2, encoded.getAsJsonObject().get("schemaVersion").getAsInt());
        assertEquals(state, QuestPlayerState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertEquals(List.of(1), state.progressFor(activation("one", null, 1)).counts());
    }

    @Test void factsAreDeeplySnapshottedAndRejectInvalidOrOversizedMaps() {
        var facts = new LinkedHashMap<String, Integer>(); facts.put("altar", 1);
        var state = new QuestPlayerState(2, SCOPE, 0, Map.of(), facts);
        facts.clear();
        assertEquals(1, state.mechanicActivationCount("altar"));
        assertThrows(UnsupportedOperationException.class, () -> state.mechanicActivations().put("altar", 2));
        assertThrows(IllegalArgumentException.class, () -> new QuestPlayerState(2, SCOPE, 0, Map.of(), Map.of("../altar", 1)));
        assertThrows(IllegalArgumentException.class, () -> new QuestPlayerState(2, SCOPE, 0, Map.of(), Map.of("altar", 0)));
        assertThrows(IllegalArgumentException.class, () -> new QuestPlayerState(2, SCOPE, 0, Map.of(), Map.of("altar", 1025)));
        for (int i = 0; i < 65; i++) facts.put("m" + i, 1);
        assertThrows(IllegalArgumentException.class, () -> new QuestPlayerState(2, SCOPE, 0, Map.of(), facts));
        assertThrows(IllegalArgumentException.class, () -> new QuestPlayerState(1, SCOPE, 0, Map.of(), Map.of()));
    }

    @Test void ordinaryDeliveryAndKillCountsAreNotReplacedByMechanicFacts() {
        var quest = new Quest("mixed", "Mixed", "Independent observed and delivered counts", List.of(),
            List.of(new QuestObjective.DeliverItem("minecraft:diamond", 3), new QuestObjective.KillCreature("guardian", 2), new QuestObjective.ActivateMechanic("altar", 2)), List.of(), null);
        var state = QuestPlayerState.empty(SCOPE).withProgress("mixed", new QuestPlayerState.Progress(List.of(2, 1, 0), false)).withMechanicActivation("altar");
        assertEquals(List.of(2, 1, 1), state.progressFor(quest).counts());
        assertEquals(List.of(2, 1, 0), state.quests().get("mixed").counts());
    }
}
