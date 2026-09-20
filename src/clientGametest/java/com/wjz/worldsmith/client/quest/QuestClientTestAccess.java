package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import net.minecraft.network.chat.Component;

/** Read-only fixture access to the actual network projection. Never included in the production mod. */
public final class QuestClientTestAccess {
    private QuestClientTestAccess() {}
    public static QuestProtocol.Snapshot snapshot() {
        String scope = WorldContentClientRuntime.activeScope();
        return scope == null ? null : QuestJournalClient.snapshot(scope);
    }
    public static boolean pending() { return QuestJournalClient.pending(); }
    public static String notice() { return QuestJournalClient.notice().getString(); }
    public static boolean staleRevisionFeedback() {
        var state = snapshot();
        return state != null && (state.feedback() == QuestProtocol.Feedback.STALE_REVISION
            || notice().startsWith(Component.translatable("worldsmith.quests.feedback.stale_revision").getString()));
    }
}
