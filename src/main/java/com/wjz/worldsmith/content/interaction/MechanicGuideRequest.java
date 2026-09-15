package com.wjz.worldsmith.content.interaction;

/** A manual inspection correlates all identity fields; old/reordered replies never become live feedback. */
public record MechanicGuideRequest(String scope, String dimension, String mechanicId, long anchor, int requestId, long startedAt) {
    public static final long TIMEOUT_NANOS = 10_000_000_000L;
    public boolean expired(long now) { return now - startedAt >= TIMEOUT_NANOS; }
    public boolean accepts(String replyScope, String replyDimension, String replyMechanic, long replyAnchor, int replyId, long now) {
        return !expired(now) && requestId == replyId && anchor == replyAnchor && scope.equals(replyScope) && dimension.equals(replyDimension) && mechanicId.equals(replyMechanic);
    }
}
