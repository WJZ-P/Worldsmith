package com.wjz.worldsmith.content.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.core.content.CustomItemLibrary;
import com.wjz.worldsmith.core.content.ItemRecipe;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Derived native recipe files, plus one advancement that puts them in every
 * player's recipe book on arrival. Not a second authoring module: the recipes
 * are items schema 5, and a native item id is checked here against the registry.
 */
public final class GeneratedItemRecipes {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private GeneratedItemRecipes() {}

    public static Identifier recipeId(String scope, String recipe) {
        return Identifier.fromNamespaceAndPath("worldsmith", "generated/" + scope + "/recipes/" + recipe);
    }
    public static Identifier unlockId(String scope) {
        return Identifier.fromNamespaceAndPath("worldsmith", "generated/" + scope + "/recipes");
    }

    public static Map<String, byte[]> serverResources(CustomItemRuntime.Snapshot items, CustomItemLibrary library, WorldBlockBindings.Resolver blocks) {
        if (library.getRecipes().isEmpty()) return Map.of();
        var context = new Context(items, blocks, RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonArray unlocked = new JsonArray();
        for (ItemRecipe recipe : library.getRecipes()) {
            Identifier id = recipeId(items.bundleHash(), recipe.getId());
            files.put("data/worldsmith/recipe/" + id.getPath() + ".json", bytes(context.recipe(recipe)));
            unlocked.add(id.toString());
        }
        JsonObject arrived = new JsonObject(); arrived.addProperty("trigger", "minecraft:tick");
        JsonObject criteria = new JsonObject(); criteria.add("arrived", arrived);
        JsonObject rewards = new JsonObject(); rewards.add("recipes", unlocked);
        JsonObject unlock = new JsonObject(); unlock.add("criteria", criteria); unlock.add("rewards", rewards);
        Identifier unlockId = unlockId(items.bundleHash());
        files.put("data/worldsmith/advancement/" + unlockId.getPath() + ".json", bytes(unlock));
        return Collections.unmodifiableMap(files);
    }

    record Context(CustomItemRuntime.Snapshot items, WorldBlockBindings.Resolver blocks, DynamicOps<JsonElement> ops) {
        JsonObject recipe(ItemRecipe recipe) {
            boolean world = CustomItemRuntime.isLogicalId(recipe.getResult());
            JsonObject json = new JsonObject();
            if (recipe instanceof ItemRecipe.Shaped shaped) {
                json.addProperty("type", world ? "worldsmith:world_shaped" : "minecraft:crafting_shaped");
                json.addProperty("category", craftingCategory(recipe.getResult()));
                JsonArray pattern = new JsonArray(); shaped.getPattern().forEach(pattern::add);
                JsonObject key = new JsonObject(); shaped.getKey().forEach((symbol, value) -> key.add(symbol, ingredient(value)));
                json.add("pattern", pattern); json.add("key", key);
            } else if (recipe instanceof ItemRecipe.Shapeless shapeless) {
                json.addProperty("type", world ? "worldsmith:world_shapeless" : "minecraft:crafting_shapeless");
                json.addProperty("category", craftingCategory(recipe.getResult()));
                JsonArray ingredients = new JsonArray(); shapeless.getIngredients().forEach(value -> ingredients.add(ingredient(value)));
                json.add("ingredients", ingredients);
            } else if (recipe instanceof ItemRecipe.Cooking cooking) {
                String station = switch (cooking.getStation()) {
                    case FURNACE -> "smelting";
                    case BLAST_FURNACE -> "blasting";
                    case SMOKER -> "smoking";
                    case CAMPFIRE -> "campfire_cooking";
                };
                json.addProperty("type", world ? "worldsmith:world_" + station : "minecraft:" + station);
                json.addProperty("category", cookingCategory(recipe.getResult()));
                json.add("ingredient", ingredient(cooking.getIngredient()));
                json.addProperty("experience", cooking.getExperience());
                json.addProperty("cookingtime", cooking.getCookingTicks() != null ? cooking.getCookingTicks() : cooking.getStation().getDefaultTicks());
            } else throw new IllegalArgumentException("Unsupported recipe kind: " + recipe);
            json.add("result", result(recipe.getResult(), recipe.getCount()));
            if (world) {
                JsonObject made = new JsonObject();
                made.addProperty("bundle", items.bundleHash());
                made.addProperty("item", recipe.getResult().substring(CustomItemRuntime.LOGICAL_PREFIX.length()));
                json.add("world_result", made);
            }
            return json;
        }

        /** A world item matches by its identity; its model and name also give the recipe book a real icon. */
        JsonElement ingredient(String reference) {
            if (reference.startsWith("#")) return new JsonPrimitive(reference);
            if (!CustomItemRuntime.isLogicalId(reference)) return new JsonPrimitive(nativeItem(reference));
            JsonObject json = new JsonObject();
            json.addProperty("fabric:type", "fabric:components");
            json.addProperty("base", CustomItemRuntime.HOST_ID);
            json.add("components", DataComponentPatch.CODEC.encodeStart(ops, display(reference)).getOrThrow());
            return json;
        }

        JsonElement result(String reference, int count) {
            ItemStackTemplate template = CustomItemRuntime.isLogicalId(reference)
                ? new ItemStackTemplate(BuiltInRegistries.ITEM.wrapAsHolder(CustomItemRuntime.host()), count, display(reference))
                : new ItemStackTemplate(BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.getValue(Identifier.parse(nativeItem(reference)))), count, DataComponentPatch.EMPTY);
            return ItemStackTemplate.CODEC.encodeStart(ops, template).getOrThrow();
        }

        DataComponentPatch display(String reference) {
            var stack = items.stack(reference, 1);
            return DataComponentPatch.builder()
                .set(CustomItemRuntime.identityComponent(), stack.get(CustomItemRuntime.identityComponent()))
                .set(DataComponents.ITEM_NAME, stack.get(DataComponents.ITEM_NAME))
                .set(DataComponents.ITEM_MODEL, stack.get(DataComponents.ITEM_MODEL))
                .build();
        }

        String nativeItem(String reference) {
            if (reference.startsWith("worldsmith:content/")) return BuiltInRegistries.ITEM.getKey(blocks.resolveItem(reference)).toString();
            Identifier id = Identifier.tryParse(reference);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Recipe names an unknown native item: " + reference);
            return id.toString();
        }

        String craftingCategory(String result) {
            if (result.startsWith("worldsmith:content/")) return "building";
            var definition = CustomItemRuntime.isLogicalId(result) ? items.requireDefinition(result) : null;
            return definition != null && definition.getEquipment() != null ? "equipment" : "misc";
        }

        String cookingCategory(String result) {
            if (result.startsWith("worldsmith:content/")) return "blocks";
            if (CustomItemRuntime.isLogicalId(result)) return items.requireDefinition(result).getConsumable() != null ? "food" : "misc";
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(nativeItem(result))).components().has(DataComponents.FOOD) ? "food" : "misc";
        }
    }

    private static byte[] bytes(JsonObject json) { return JSON.toJson(json).getBytes(StandardCharsets.UTF_8); }
}
