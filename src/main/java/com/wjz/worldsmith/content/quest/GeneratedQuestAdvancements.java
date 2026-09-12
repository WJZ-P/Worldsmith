package com.wjz.worldsmith.content.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.core.content.QuestObjective;
import com.wjz.worldsmith.core.content.QuestValidation;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;

/** Derived native data, not a new authoring module or a second reward ledger. */
public final class GeneratedQuestAdvancements {
    public static final String ENTERED_WORLD = "entered_world";
    public static final String OBJECTIVES_MET = "objectives_met";
    public static final String CLAIMED = "claimed";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private GeneratedQuestAdvancements() {}

    public static Identifier rootId(String scope) { return Identifier.fromNamespaceAndPath("worldsmith", prefix(scope) + "/root"); }
    public static Identifier taskId(String scope, String task) {
        if (!QuestValidation.validId(task)) throw new IllegalArgumentException("Invalid quest advancement task ID");
        return Identifier.fromNamespaceAndPath("worldsmith", prefix(scope) + "/tasks/" + task);
    }
    private static String prefix(String scope) {
        if (scope == null || !scope.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid quest advancement world scope");
        return "generated/" + scope + "/quests";
    }

    public static Map<String, byte[]> serverResources(WorldsmithPack pack, String worldTitle) {
        var quests = QuestValidation.ordered(QuestValidation.freeze(pack.getQuests()));
        if (quests.isEmpty()) return Map.of();
        String scope = pack.getManifest().getId();
        Set<String> bosses = pack.getCreatures().getCreatures().stream().filter(creature -> creature.getBoss() != null)
                .map(creature -> creature.getId()).collect(Collectors.toSet());
        Map<String, byte[]> resources = new LinkedHashMap<>();
        JsonObject root = advancement(worldTitle, pack.getManifest().getDescription(), "minecraft:map", false, false, ENTERED_WORLD);
        root.getAsJsonObject("display").addProperty("background", "minecraft:gui/advancements/backgrounds/adventure");
        put(resources, rootId(scope), root);
        for (var quest : quests) {
            boolean boss = quest.getObjectives().stream().anyMatch(objective -> objective instanceof QuestObjective.KillCreature kill && bosses.contains(kill.getCreature()));
            JsonObject node = advancement(quest.getTitle(), quest.getDescription(), "minecraft:book", true, boss, OBJECTIVES_MET, CLAIMED);
            node.addProperty("parent", (quest.getPrerequisites().isEmpty() ? rootId(scope) : taskId(scope, quest.getPrerequisites().getFirst())).toString());
            JsonArray extra = new JsonArray();
            extra.add(text("\n\n"));
            JsonObject hint = new JsonObject(); hint.addProperty("translate", "worldsmith.advancements.claim_hint"); extra.add(hint);
            node.getAsJsonObject("display").getAsJsonObject("description").add("extra", extra);
            put(resources, taskId(scope, quest.getId()), node);
        }
        return Collections.unmodifiableMap(resources);
    }

    private static JsonObject advancement(String title, String description, String icon, boolean toast, boolean challenge, String... criteria) {
        JsonObject root = new JsonObject(), display = new JsonObject(), item = new JsonObject();
        item.addProperty("id", icon); display.add("icon", item);
        display.add("title", text(title)); display.add("description", text(description));
        display.addProperty("frame", challenge ? "challenge" : "task");
        display.addProperty("show_toast", toast); display.addProperty("announce_to_chat", false); display.addProperty("hidden", false);
        root.add("display", display);
        JsonObject conditions = new JsonObject(); JsonArray requirements = new JsonArray();
        for (String criterion : criteria) {
            JsonObject trigger = new JsonObject(); trigger.addProperty("trigger", "minecraft:impossible"); conditions.add(criterion, trigger);
            JsonArray required = new JsonArray(); required.add(criterion); requirements.add(required);
        }
        root.add("criteria", conditions); root.add("requirements", requirements);
        // Omitting rewards uses AdvancementRewards.EMPTY: no XP, loot, recipes or commands.
        root.addProperty("sends_telemetry_event", false);
        return root;
    }
    private static JsonObject text(String text) { JsonObject value = new JsonObject(); value.addProperty("text", text); return value; }
    private static void put(Map<String, byte[]> resources, Identifier id, JsonObject definition) {
        resources.put("data/" + id.getNamespace() + "/advancement/" + id.getPath() + ".json", JSON.toJson(definition).getBytes(StandardCharsets.UTF_8));
    }
}
