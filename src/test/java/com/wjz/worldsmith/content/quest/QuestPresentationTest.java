package com.wjz.worldsmith.content.quest;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestPresentationTest {
    @Test void committedMechanicObjectiveIsAFirstClassJournalKind() {
        var objective = new QuestProtocol.Objective("activate_mechanic", "ritual_altar", "Ritual Altar", 1, 2);
        var quest = new QuestProtocol.Entry("ritual", "Ritual", "Awaken the altar", QuestProtocol.Status.ACTIVE, List.of(objective), List.of());
        assertEquals("activate_mechanic", quest.objectives().getFirst().kind());
        assertEquals(1, quest.objectives().getFirst().progress());
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Objective("run_mechanic", "altar", "Altar", 0, 1));
    }

    @Test void normalWindowsShowAtLeastFourRowsWithoutTouchingPagerOrFooter() {
        for (int height : List.of(232, 239, 240, 256, 270, 360, 540)) {
            var layout = QuestJournalLayout.of(622, height);
            assertTrue(layout.pageSize() >= 4, "GUI height " + height);
            int lastButtonBottom = layout.bodyTop() + 4 + (layout.pageSize() - 1) * 26 + 22;
            assertTrue(lastButtonBottom <= layout.bodyBottom() - 21, "Last row must clear the pager");
            assertTrue(layout.bodyBottom() < height - 48, "Panel clears transient feedback");
        }
    }

    @Test void compactWindowsRemainBoundedRatherThanForcingFourOverlappingRows() {
        var layout = QuestJournalLayout.of(320, 180);
        assertTrue(layout.pageSize() >= 1 && layout.pageSize() < 4);
        assertTrue(layout.detailWidth() >= 100);
        assertTrue(layout.bodyTop() + 4 + (layout.pageSize() - 1) * 26 + 22 <= layout.bodyBottom() - 21);
    }

    @Test void arrivalShowsOncePerConnectionAndExpiresAfterFiveSeconds() {
        var session = new WorldArrivalSession(); Object connection = new Object();
        assertFalse(session.waiting(connection));
        session.connect(connection); assertTrue(session.waiting(connection));
        assertTrue(session.start(connection, 100));
        assertEquals(5_000_000_000L, WorldArrivalSession.DURATION_NANOS);
        assertTrue(session.active(100)); assertTrue(session.active(5_000_000_099L));
        assertFalse(session.active(5_000_000_100L));
        // A respawn, dimension change, journal reset or resource reload retains the connection token.
        session.connect(connection); assertFalse(session.waiting(connection)); assertFalse(session.start(connection, 9_000_000_000L));
        session.connect(null); assertFalse(session.active(100));
        Object reconnect = new Object(); session.connect(reconnect); assertTrue(session.start(reconnect, 20));
        session.dismiss(); assertFalse(session.active(21)); assertFalse(session.waiting(reconnect));
    }

    @Test void arrivalUsesTheActiveChapterAndDoesNotCallAnEmptyWorldComplete() {
        var claimed = entry("first", QuestProtocol.Status.CLAIMED);
        var ready = entry("second", QuestProtocol.Status.READY);
        var locked = entry("third", QuestProtocol.Status.LOCKED);
        assertEquals(ready, WorldArrivalPresentation.currentQuest(List.of(claimed, ready, locked)));
        assertFalse(WorldArrivalPresentation.complete(snapshot(List.of(), false)));
        assertFalse(WorldArrivalPresentation.complete(snapshot(List.of(claimed, ready), false)));
        assertFalse(WorldArrivalPresentation.complete(snapshot(List.of(claimed), false)), "A filtered all-claimed list may hide a future chapter");
        assertTrue(WorldArrivalPresentation.complete(snapshot(List.of(claimed), true)));
        assertNull(WorldArrivalPresentation.currentQuest(List.of(claimed)));
    }

    private static QuestProtocol.Entry entry(String id, QuestProtocol.Status status) {
        return new QuestProtocol.Entry(id, id, "A chapter of the journey", status,
            List.of(new QuestProtocol.Objective("deliver_item", "worldsmith:item/token", "Token", 0, 1)), List.of());
    }
    private static QuestProtocol.Snapshot snapshot(List<QuestProtocol.Entry> entries, boolean complete) {
        return new QuestProtocol.Snapshot("a".repeat(64), "World", 0, 1, entries, QuestProtocol.Feedback.NONE, "", complete, null);
    }
}
