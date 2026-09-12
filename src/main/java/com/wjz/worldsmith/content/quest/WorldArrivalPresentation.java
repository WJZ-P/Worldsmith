package com.wjz.worldsmith.content.quest;

import com.wjz.worldsmith.core.model.WorldsmithPack;

/** Read-only lore from the verified world, separate from the player's authoritative progress. */
public record WorldArrivalPresentation(String scope, String premise, String playerRole, java.util.Map<String,String> questBackgrounds) {
    public WorldArrivalPresentation {
        questBackgrounds = java.util.Map.copyOf(questBackgrounds);
    }
    public WorldArrivalPresentation(String scope, String premise, String playerRole) { this(scope, premise, playerRole, java.util.Map.of()); }
    public static WorldArrivalPresentation from(WorldsmithPack pack) {
        var beats = new java.util.HashMap<String,String>();
        pack.getTheme().getBeats().forEach(beat -> beats.put(beat.getId(), beat.getDescription()));
        var chapters = new java.util.HashMap<String,String>();
        pack.getQuests().getQuests().forEach(quest -> {
            String background = beats.get(quest.getThemeBeat());
            if (background != null && !background.isBlank()) chapters.put(quest.getId(), background);
        });
        return new WorldArrivalPresentation(pack.getManifest().getId(), pack.getTheme().getPremise(), pack.getTheme().getPlayerRole(), chapters);
    }
    public String background(QuestProtocol.Entry quest) {
        String fallback = premise.isBlank() ? playerRole : premise;
        return quest == null ? fallback : questBackgrounds.getOrDefault(quest.id(), fallback);
    }
    public static QuestProtocol.Entry currentQuest(java.util.List<QuestProtocol.Entry> quests) {
        return quests.stream().filter(quest -> quest.status() == QuestProtocol.Status.READY || quest.status() == QuestProtocol.Status.ACTIVE).findFirst().orElse(null);
    }
    public static boolean complete(java.util.List<QuestProtocol.Entry> quests) {
        return !quests.isEmpty() && quests.stream().allMatch(quest -> quest.status() == QuestProtocol.Status.CLAIMED);
    }
}
