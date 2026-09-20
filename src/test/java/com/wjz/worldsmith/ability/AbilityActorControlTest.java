package com.wjz.worldsmith.ability;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityActorControlTest {
    private final AbilityActorControl.Claims<Object, String> claims = new AbilityActorControl.Claims<>();
    private final Object actor = new Object();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();

    @Test void equalPriorityKeepsFirstOwnerAndHigherPriorityRetiresItOnce() {
        var retired = new AtomicInteger();
        assertTrue(claims.claim(actor, first, 1, 10, 0, 20, path -> {}, retired::incrementAndGet));
        assertFalse(claims.claim(actor, second, 1, 10, 1, 20, path -> {}, () -> fail("Rejected claim retired")));
        assertFalse(claims.claim(actor, second, 1, 9, 1, 20, path -> {}, () -> fail("Rejected claim retired")));
        assertEquals(0, retired.get());
        assertTrue(claims.claim(actor, second, 1, 11, 1, 20, path -> {}, () -> {}));
        assertEquals(1, retired.get());
        assertTrue(claims.current(actor, 2).matches(second, 1));
        assertFalse(claims.release(actor, first, 1));
    }

    @Test void handleIdentityIncludesInvocationAndActorObject() {
        assertTrue(claims.claim(actor, first, 7, 1, 0, 20, path -> {}, () -> {}));
        assertFalse(claims.release(actor, second, 7));
        assertFalse(claims.release(actor, first, 8));
        assertFalse(claims.release(new Object(), first, 7));
        assertNotNull(claims.current(actor, 1));
        assertTrue(claims.release(actor, first, 7));
        assertFalse(claims.release(actor, first, 7));
    }

    @Test void sameInvocationCanRenewAndInvalidatesItsOldHandle() {
        var retired = new AtomicInteger();
        assertTrue(claims.claim(actor, first, 1, 50, 0, 10, path -> {}, retired::incrementAndGet));
        assertTrue(claims.claim(actor, first, 2, 20, 5, 30, path -> {}, retired::incrementAndGet));
        assertEquals(1, retired.get());
        assertFalse(claims.release(actor, first, 1));
        assertTrue(claims.current(actor, 20).matches(first, 2));
        assertNull(claims.current(actor, 30));
        assertEquals(2, retired.get());
    }

    @Test void expiryUsesWorldTimeAndDoesNotRetireTwice() {
        var retired = new AtomicInteger();
        claims.claim(actor, first, 1, 0, 100, 120, path -> {}, retired::incrementAndGet);
        assertNotNull(claims.current(actor, 119));
        assertNull(claims.current(actor, 120));
        assertNull(claims.current(actor, 900));
        claims.revoke(actor); claims.clear();
        assertEquals(1, retired.get());
    }

    @Test void retirementUnpublishesBeforePathAndBudgetCleanup() {
        var events = new ArrayList<String>();
        claims.claim(actor, first, 1, 0, 0, 20, path -> {
            assertNull(claims.owners.get(actor)); events.add("path:" + path);
        }, () -> {
            assertNull(claims.owners.get(actor));
            assertFalse(claims.release(actor, first, 1)); events.add("budget");
        });
        claims.current(actor, 0).path = "old";
        claims.claim(actor, second, 2, 1, 1, 30, path -> {}, () -> {});
        assertEquals(List.of("path:old", "budget"), events);
        assertTrue(claims.current(actor, 1).matches(second, 2));
    }

    @Test void stalePathCleanupPreservesNewerNativeRoute() {
        var nativePath = new AtomicReference<>("old");
        claims.claim(actor, first, 1, 0, 0, 20, path -> {
            if (nativePath.get() == path) nativePath.set(null);
        }, () -> {});
        claims.current(actor, 0).path = nativePath.get();
        nativePath.set("new");
        assertTrue(claims.release(actor, first, 1));
        assertEquals("new", nativePath.get());
    }

    @Test void reentrantRetirementCannotOverwriteTheNewerOwner() {
        UUID third = UUID.randomUUID();
        claims.claim(actor, first, 1, 0, 0, 20, path -> {}, () ->
            assertTrue(claims.claim(actor, third, 3, 80, 1, 30, path -> {}, () -> {})));
        assertFalse(claims.claim(actor, second, 2, 50, 1, 30, path -> {}, () -> {}));
        assertTrue(claims.current(actor, 1).matches(third, 3));
    }

    @Test void pathFailureStillReturnsTheSharedBudget() {
        var retired = new AtomicInteger();
        claims.claim(actor, first, 1, 0, 0, 20, path -> { throw new IllegalStateException("native fixture"); }, retired::incrementAndGet);
        claims.current(actor, 0).path = "path";
        assertThrows(IllegalStateException.class, () -> claims.release(actor, first, 1));
        assertEquals(1, retired.get());
        assertNull(claims.current(actor, 0));
    }
}
