package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.content.quest.QuestProtocol;
import java.util.List;

/** UI discovery uses stable references, never display labels; locked quests have no deep links. */
public final class MechanicGuideAccess {
    private MechanicGuideAccess() {}
    public static List<String> references(QuestProtocol.Entry quest) {
        if (quest == null || !(quest.status() == QuestProtocol.Status.ACTIVE || quest.status() == QuestProtocol.Status.READY || quest.status() == QuestProtocol.Status.CLAIMED)) return List.of();
        return quest.objectives().stream().filter(objective -> objective.kind().equals("activate_mechanic"))
            .map(QuestProtocol.Objective::reference).distinct().toList();
    }
}
