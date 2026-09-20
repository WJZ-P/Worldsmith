package com.wjz.worldsmith.content.story;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryReplyPolicyTest {
    @Test void anEarlyUnboundWorldReplyAcknowledgesOnlyTheExactPendingHandshake() {
        var unavailable = reply("", 7, StoryProtocol.Feedback.UNAVAILABLE);
        assertTrue(StoryReplyPolicy.matchingUnavailable(7, 7, unavailable));
        assertFalse(StoryReplyPolicy.matchingUnavailable(8, 8, unavailable), "An older connection/request must not release a newer action");
        assertFalse(StoryReplyPolicy.matchingUnavailable(7, 0, unavailable), "An already completed request has nothing to acknowledge");
        assertFalse(StoryReplyPolicy.matchingUnavailable(8, 7, unavailable), "Latest request identity must agree too");
    }
    @Test void unscopedPushesAndOtherFeedbackAreNotTreatedAsAcknowledgements() {
        assertFalse(StoryReplyPolicy.matchingUnavailable(7, 7, reply("", 0, StoryProtocol.Feedback.UNAVAILABLE)));
        assertFalse(StoryReplyPolicy.matchingUnavailable(7, 7, reply("", 7, StoryProtocol.Feedback.NONE)));
        assertFalse(StoryReplyPolicy.matchingUnavailable(7, 7, reply("a".repeat(64), 7, StoryProtocol.Feedback.UNAVAILABLE)));
    }
    private static StoryProtocol.Snapshot reply(String scope, int request, StoryProtocol.Feedback feedback) {
        return new StoryProtocol.Snapshot(scope, request, 0, null, List.of(), List.of(), List.of(), feedback, "");
    }
}
