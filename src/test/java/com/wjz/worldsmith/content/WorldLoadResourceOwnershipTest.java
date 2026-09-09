package com.wjz.worldsmith.content;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class WorldLoadResourceOwnershipTest {
    @Test void preparationFailureClosesOnlyItsOwnedHandlesInOrderAndOnlyOnce() {
        List<String> closed = new ArrayList<>();
        var ownership = new WorldLoadResourceOwnership(() -> closed.add("stem"), () -> closed.add("storage"));
        ownership.abort(new IllegalArgumentException("bad embedded content"));
        ownership.abort(new IllegalArgumentException("repeated completion"));
        assertEquals(List.of("stem", "storage"), closed);
        assertThrows(IllegalStateException.class, ownership::transferToVanilla);
    }

    @Test void failedCloseStillClosesStorageAndRetainsBothErrors() {
        List<String> closed = new ArrayList<>();
        var ownership = new WorldLoadResourceOwnership(() -> { closed.add("stem"); throw new Exception("stem close"); },
            () -> { closed.add("storage"); throw new Exception("storage close"); });
        var failure = new IllegalArgumentException("preparation failed");
        ownership.abort(failure);
        assertEquals(List.of("stem", "storage"), closed);
        assertEquals(2, failure.getSuppressed().length);
    }

    @Test void handoffNeverClosesHandlesThatMayAlreadyBelongToTheIntegratedServer() {
        List<String> closed = new ArrayList<>();
        var ownership = new WorldLoadResourceOwnership(() -> closed.add("stem"), () -> closed.add("storage"));
        ownership.transferToVanilla();
        ownership.abort(new IllegalArgumentException("server startup failed later"));
        assertTrue(closed.isEmpty());
        assertThrows(IllegalStateException.class, ownership::transferToVanilla);
    }
}
