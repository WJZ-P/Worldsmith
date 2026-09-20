package com.wjz.worldsmith.content.story;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryRequestGateTest {
    @Test void allRequestsIncludingWrongScopesSpendTheSameResponseBudget() {
        var gate = new StoryRequestGate();
        // These requests stop at the scope check: they must not get an unbounded number of UNAVAILABLE snapshots.
        for (int i = 0; i < StoryRequestGate.LIMIT; i++) assertTrue(gate.allowRequest(100));
        for (int i = 0; i < 10000; i++) assertFalse(gate.allowRequest(100));
        assertFalse(gate.allowRequest(119));
        assertTrue(gate.allowRequest(120));
        assertTrue(gate.advanceNonce(1), "Wrong-world requests must not consume an unrelated world's nonce");
    }
    @Test void repeatedNoncesStillGetExpiredFeedbackWithinBudgetButNotBeyondIt() {
        var gate = new StoryRequestGate();
        assertTrue(gate.allowRequest(0)); assertTrue(gate.advanceNonce(1));
        for (int i = 1; i < StoryRequestGate.LIMIT; i++) {
            assertTrue(gate.allowRequest(0)); assertFalse(gate.advanceNonce(1));
        }
        assertFalse(gate.allowRequest(0));
        assertTrue(gate.allowRequest(20)); assertFalse(gate.advanceNonce(1));
        assertTrue(gate.allowRequest(20)); assertTrue(gate.advanceNonce(2));
    }
    @Test void aDroppedRequestHasNoIntentOrNonceSideEffects() {
        var gate = new StoryRequestGate();
        for (int id = 1; id <= StoryRequestGate.LIMIT; id++) { assertTrue(gate.allowRequest(5)); assertTrue(gate.advanceNonce(id)); }
        assertFalse(gate.allowRequest(5)); // Action 13 is dropped before scope lookup, projection or nonce processing.
        assertTrue(gate.allowRequest(25)); assertTrue(gate.advanceNonce(13));
        assertFalse(gate.advanceNonce(12));
    }
    @Test void timeWindowsDoNotResetNonceHistory() {
        var gate = new StoryRequestGate();
        assertTrue(gate.allowRequest(100)); assertTrue(gate.advanceNonce(Integer.MAX_VALUE));
        assertTrue(gate.allowRequest(10000)); assertFalse(gate.advanceNonce(Integer.MAX_VALUE)); assertFalse(gate.advanceNonce(1));
    }
    @Test void clockRebaseKeepsSpentBudgetAndRecoversAfterANewWindow() {
        var gate = new StoryRequestGate();
        for (int i = 0; i < StoryRequestGate.LIMIT; i++) assertTrue(gate.allowRequest(100));
        assertTrue(gate.advanceNonce(7));
        assertFalse(gate.allowRequest(80)); assertFalse(gate.allowRequest(99));
        assertTrue(gate.allowRequest(100)); assertFalse(gate.advanceNonce(7)); assertTrue(gate.advanceNonce(8));
    }
    @Test void negativeTimeAndInvalidNoncesDoNotCreateNewState() {
        var gate = new StoryRequestGate(); assertFalse(gate.allowRequest(-1));
        assertFalse(gate.advanceNonce(0)); assertFalse(gate.advanceNonce(-1));
        for (int i = 0; i < StoryRequestGate.LIMIT; i++) assertTrue(gate.allowRequest(Long.MAX_VALUE - 20));
        assertFalse(gate.allowRequest(Long.MAX_VALUE - 1)); assertTrue(gate.allowRequest(Long.MAX_VALUE));
        assertTrue(gate.advanceNonce(1));
    }
}
