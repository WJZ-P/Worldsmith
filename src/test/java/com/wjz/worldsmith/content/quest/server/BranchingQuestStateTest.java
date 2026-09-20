package com.wjz.worldsmith.content.quest.server;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.story.StoryCondition;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BranchingQuestStateTest {
    private static final String SCOPE = "a".repeat(64);
    private static Quest quest(String id, List<String> prerequisites, boolean optional, boolean manual, String group, QuestPrerequisiteMode mode) {
        return new Quest(id, id, "A discovered story chapter", prerequisites, List.of(new QuestObjective.DeliverItem("minecraft:stick")), List.of(), null,
            optional, manual, group, StoryCondition.Always.INSTANCE, StoryCondition.Always.INSTANCE, List.of(), List.of(), null, mode);
    }
    private static Quest root(String id) { return quest(id, List.of(), false, false, null, QuestPrerequisiteMode.ALL); }
    private static QuestPlayerState finish(QuestPlayerState state, Quest quest) {
        return state.discover(quest).accept(quest).withProgress(quest.getId(), new QuestPlayerState.Progress(List.of(1), true, false, true));
    }
    @Test void discoveryIsDurableButDoesNotAcceptAnOfferOrSelectABranch() {
        var offer = quest("offer", List.of(), false, true, "route", QuestPrerequisiteMode.ALL);
        var initial = QuestPlayerState.empty(SCOPE);
        assertFalse(initial.discovered(offer)); assertTrue(initial.questUnlocked(offer));
        var seen = initial.discover(offer);
        assertTrue(seen.discovered(offer)); assertFalse(seen.progressFor(offer).accepted()); assertTrue(seen.selectedBranches().isEmpty());
        assertSame(seen, seen.discover(offer));
        var deferred = seen.decline(offer);
        assertTrue(deferred.progressFor(offer).declined()); assertTrue(deferred.selectedBranches().isEmpty());
        var accepted = deferred.accept(offer);
        assertTrue(accepted.progressFor(offer).accepted()); assertFalse(accepted.progressFor(offer).declined());
        assertEquals("offer", accepted.selectedBranches().get("route"));
        assertThrows(IllegalStateException.class, () -> accepted.accept(offer));
        assertThrows(IllegalStateException.class, () -> accepted.decline(offer));
    }
    @Test void exclusiveDescendantsAreExcludedButAnyMergeRemainsRequiredAndCompletes() {
        var start = root("start");
        var left = quest("left", List.of("start"), false, true, "route", QuestPrerequisiteMode.ALL);
        var right = quest("right", List.of("start"), false, true, "route", QuestPrerequisiteMode.ALL);
        var rightLater = quest("right_later", List.of("right"), false, false, null, QuestPrerequisiteMode.ALL);
        var merge = quest("merge", List.of("left", "right_later"), false, false, null, QuestPrerequisiteMode.ANY);
        var side = quest("side", List.of(), true, true, null, QuestPrerequisiteMode.ALL);
        Map<String, Quest> definitions = new LinkedHashMap<>();
        for (var q : List.of(merge, rightLater, right, left, start, side)) definitions.put(q.getId(), q);
        var begun = finish(QuestPlayerState.empty(SCOPE), start).discover(left).discover(right);
        assertFalse(begun.campaignComplete(definitions));
        var chosen = begun.accept(left);
        assertEquals(Set.of("right", "right_later"), chosen.excluded(definitions));
        assertThrows(IllegalStateException.class, () -> chosen.accept(right));
        assertFalse(chosen.questUnlocked(merge));
        var claimed = chosen.withProgress("left", new QuestPlayerState.Progress(List.of(1), true));
        assertTrue(claimed.questUnlocked(merge));
        assertFalse(claimed.campaignComplete(definitions), "A hidden future merge must still count");
        var complete = finish(claimed, merge);
        assertTrue(complete.campaignComplete(definitions), "The rejected route and optional side quest must not deadlock the ending");
    }
    @Test void multipleRootsAndAllPrerequisitesNeverUnlockByAccidentalArrayOrder() {
        var first = root("first"); var second = root("second");
        var joined = quest("joined", List.of("first", "second"), false, false, null, QuestPrerequisiteMode.ALL);
        var one = finish(QuestPlayerState.empty(SCOPE), first);
        assertFalse(one.questUnlocked(joined)); assertTrue(one.questUnlocked(second));
        assertTrue(finish(one, second).questUnlocked(joined));
    }
    @Test void scopedTrackingBranchAndDiscoveryRoundTripWithoutAnyFactCounter() {
        var branch = quest("remembered", List.of(), false, true, "choice", QuestPrerequisiteMode.ALL);
        var state = QuestPlayerState.empty(SCOPE).discover(branch).accept(branch).track(branch.getId());
        var encoded = QuestPlayerState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        var restored = QuestPlayerState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(state, restored); assertEquals(3, restored.schemaVersion());
        assertEquals(List.of(0), restored.quests().get(branch.getId()).counts());
        assertEquals("remembered", restored.trackedQuest());
        assertThrows(IllegalArgumentException.class, () -> state.track("unknown_future"));
        assertThrows(UnsupportedOperationException.class, () -> restored.selectedBranches().clear());
        encoded.getAsJsonObject().addProperty("schemaVersion", 2);
        assertTrue(QuestPlayerState.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent());
    }
    @Test void emptyOrOptionalOnlyKnownContentDoesNotManufactureCampaignCompletion() {
        assertFalse(QuestPlayerState.empty(SCOPE).campaignComplete(Map.of()));
        var optional = quest("side", List.of(), true, false, null, QuestPrerequisiteMode.ALL);
        assertFalse(finish(QuestPlayerState.empty(SCOPE), optional).campaignComplete(Map.of("side", optional)));
        var future = quest("future", List.of("side"), false, false, null, QuestPrerequisiteMode.ALL);
        assertFalse(finish(QuestPlayerState.empty(SCOPE), optional).campaignComplete(Map.of("side", optional, "future", future)));
    }
}
