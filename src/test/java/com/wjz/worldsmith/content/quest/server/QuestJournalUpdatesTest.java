package com.wjz.worldsmith.content.quest.server;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestJournalUpdatesTest {
    @Test void areaKillsMergeIntoOnePushWithoutSlidingTheDeadline() {
        var queue = new QuestJournalUpdates(); Object server = new Object(); UUID player = UUID.randomUUID();
        for (int tick = 100; tick < 105; tick++) {
            queue.mark(player, server, tick);
            assertEquals(List.of(), queue.drain(server, tick));
        }
        assertEquals(List.of(player), queue.drain(server, 105));
        assertEquals(List.of(), queue.drain(server, 106));
    }

    @Test void actionSnapshotsAndDisconnectsRemoveRedundantPendingPushes() {
        var queue = new QuestJournalUpdates(); Object server = new Object(); UUID player = UUID.randomUUID();
        queue.mark(player, server, 5); queue.remove(player);
        assertTrue(queue.drain(server, 20).isEmpty());
        queue.mark(player, server, 21); queue.clear(server);
        assertTrue(queue.drain(server, 30).isEmpty());
    }

    @Test void serverTokensIsolateUpdatesAndIntegerTickWrapKeepsTheDelay() {
        var queue = new QuestJournalUpdates(); Object first = new Object(), second = new Object(); UUID player = UUID.randomUUID();
        queue.mark(player, first, Integer.MAX_VALUE - 2);
        assertTrue(queue.drain(second, Integer.MAX_VALUE).isEmpty());
        assertTrue(queue.drain(first, Integer.MIN_VALUE).isEmpty());
        assertEquals(List.of(player), queue.drain(first, Integer.MIN_VALUE + 2));
        queue.mark(player, first, 1); queue.mark(player, second, 2);
        assertTrue(queue.drain(first, 10).isEmpty());
        assertEquals(List.of(player), queue.drain(second, 10));
    }
}
