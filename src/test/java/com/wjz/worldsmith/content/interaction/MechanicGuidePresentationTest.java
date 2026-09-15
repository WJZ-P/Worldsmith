package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.content.quest.QuestProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MechanicGuidePresentationTest {
    @Test void smallestSupportedWindowHasReadableFullWidthSeventeenColumnDiagram() {
        var layout = MechanicGuideLayout.of(320, 180, 3, 17);
        assertFalse(layout.cellList());
        assertTrue(layout.cellSize() >= 14, "Two-character palette symbols stay readable");
        assertTrue(layout.contentTop() + 36 <= layout.contentBottom());
        assertTrue(layout.left() + 30 + layout.cellSize() * 17 <= 320 - layout.left());
        assertTrue(layout.contentBottom() <= 180 - 30, "Diagram clears the action footer");
    }

    @Test void narrowWindowsUseCoordinateListRatherThanClippedGrid() {
        for (int width : List.of(180, 200, 240, 260)) {
            var layout = MechanicGuideLayout.of(width, 180, 3, 17);
            assertTrue(layout.cellList());
            assertTrue(layout.left() + layout.contentWidth() <= width);
            assertTrue(layout.contentTop() < layout.contentBottom());
        }
        assertFalse(MechanicGuideLayout.of(200, 180, 1, 3).cellList());
    }

    @Test void longGuidesAndTallPatternsReachBothEndsWithoutOverscroll() {
        assertEquals(0, MechanicGuideLayout.scroll(0, -24, 1500, 50));
        assertEquals(1450, MechanicGuideLayout.scroll(1430, 60, 1500, 50));
        assertEquals(1426, MechanicGuideLayout.scroll(1450, -24, 1500, 50));
        assertEquals(0, MechanicGuideLayout.scroll(800, 0, 30, 100));
    }

    @Test void onlyUnlockedMechanicObjectivesHaveStableDistinctEntrances() {
        var objectives = List.of(new QuestProtocol.Objective("activate_mechanic", "altar", "Same display name", 0, 1),
            new QuestProtocol.Objective("activate_mechanic", "gate", "Same display name", 0, 1),
            new QuestProtocol.Objective("activate_mechanic", "altar", "Renamed Altar", 0, 1),
            new QuestProtocol.Objective("deliver_item", "minecraft:stone", "Stone", 0, 1));
        for (var status : List.of(QuestProtocol.Status.ACTIVE, QuestProtocol.Status.READY, QuestProtocol.Status.CLAIMED)) {
            var quest = new QuestProtocol.Entry("route", "Route", "", status, objectives, List.of());
            assertEquals(List.of("altar", "gate"), MechanicGuideAccess.references(quest));
        }
        assertEquals(List.of(), MechanicGuideAccess.references(new QuestProtocol.Entry("route", "Route", "", QuestProtocol.Status.LOCKED, objectives, List.of())));
        assertEquals(List.of(), MechanicGuideAccess.references(null));
    }

    @Test void repliesMustMatchWorldMechanismAnchorAndRequestAndArriveBeforeTimeout() {
        var request = new MechanicGuideRequest("world-one", "minecraft:overworld", "altar", 123L, 8, 100L);
        assertTrue(request.accepts("world-one", "minecraft:overworld", "altar", 123L, 8, 101L));
        assertFalse(request.accepts("world-two", "minecraft:overworld", "altar", 123L, 8, 101L));
        assertFalse(request.accepts("world-one", "minecraft:the_nether", "altar", 123L, 8, 101L));
        assertFalse(request.accepts("world-one", "minecraft:overworld", "gate", 123L, 8, 101L));
        assertFalse(request.accepts("world-one", "minecraft:overworld", "altar", 124L, 8, 101L));
        assertFalse(request.accepts("world-one", "minecraft:overworld", "altar", 123L, 7, 101L));
        assertFalse(request.accepts("world-one", "minecraft:overworld", "altar", 123L, 8, 100L + MechanicGuideRequest.TIMEOUT_NANOS));
        assertTrue(request.expired(101L + MechanicGuideRequest.TIMEOUT_NANOS));
    }

    @Test void timeoutArithmeticRemainsCorrectAcrossNanoTimeWrap() {
        long start = Long.MAX_VALUE - 100;
        var request = new MechanicGuideRequest("world", "minecraft:overworld", "altar", 0L, 1, start);
        assertFalse(request.expired(start + 200));
        assertTrue(request.expired(start + MechanicGuideRequest.TIMEOUT_NANOS));
    }
}
