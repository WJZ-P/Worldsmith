package com.wjz.worldsmith.content.story;

/** The server may reject an early handshake before a world is bound; only that exact pending request may be acknowledged. */
public final class StoryReplyPolicy {
    private StoryReplyPolicy() {}
    public static boolean matchingUnavailable(int latestRequest, int pendingRequest, StoryProtocol.Snapshot reply) {
        return pendingRequest > 0 && pendingRequest == latestRequest && reply.requestId() == pendingRequest
            && reply.scope().isEmpty() && reply.feedback() == StoryProtocol.Feedback.UNAVAILABLE;
    }
}
