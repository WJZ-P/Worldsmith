package com.wjz.worldsmith.content.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.content.CookingStation;
import com.wjz.worldsmith.core.content.CustomItemDefinition;
import com.wjz.worldsmith.core.content.CustomItemLibrary;
import com.wjz.worldsmith.core.content.ItemConsumable;
import com.wjz.worldsmith.core.content.ItemEquipment;
import com.wjz.worldsmith.core.content.ItemEquipmentType;
import com.wjz.worldsmith.core.content.ItemRecipe;
import com.wjz.worldsmith.worldgen.WorldsmithTestBootstrap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Items schema 5 becomes native recipe files whose made item is the full world item. */
class GeneratedItemRecipesTest {
    private static final String WORLD = "c".repeat(64);
    private static final String TEXTURE = "a".repeat(64);
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrapCustomItems(); }

    private static final List<CustomItemDefinition> ITEMS = List.of(
        new CustomItemDefinition("raw_ember", "Raw ember", TEXTURE),
        new CustomItemDefinition("ember_shard", "Ember shard", TEXTURE),
        new CustomItemDefinition("ember_blade", "Ember blade", TEXTURE, com.wjz.worldsmith.core.content.CustomItemKind.RELIC, 1,
            com.wjz.worldsmith.core.content.CustomItemRarity.RARE, "", "", new ItemEquipment(ItemEquipmentType.MELEE)),
        new CustomItemDefinition("trail_ration", "Trail ration", TEXTURE, com.wjz.worldsmith.core.content.CustomItemKind.RESOURCE, 64,
            com.wjz.worldsmith.core.content.CustomItemRarity.COMMON, "", "", null, new ItemConsumable(6)));
    private static final ItemRecipe SMELT = new ItemRecipe.Cooking("ember_shard", "worldsmith:item/raw_ember", "worldsmith:item/ember_shard", 1, CookingStation.BLAST_FURNACE, 0.7f, null);
    private static final ItemRecipe BLADE = new ItemRecipe.Shaped("ember_blade", List.of(" E ", " E ", " S "),
        Map.of("E", "worldsmith:item/ember_shard", "S", "minecraft:stick"), "worldsmith:item/ember_blade", 1);
    private static final ItemRecipe NATIVE_BLADE = new ItemRecipe.Shaped("iron_blade", List.of("I", "I", "S"),
        Map.of("I", "minecraft:iron_ingot", "S", "minecraft:stick"), "worldsmith:item/ember_blade", 1);
    private static final ItemRecipe RATION = new ItemRecipe.Shapeless("trail_ration", List.of("minecraft:bread", "#minecraft:flowers"), "worldsmith:item/trail_ration", 2);
    private static final ItemRecipe NUGGETS = new ItemRecipe.Shapeless("shard_nuggets", List.of("worldsmith:item/ember_shard"), "minecraft:iron_nugget", 3);

    private static Map<String, byte[]> files(ItemRecipe... recipes) {
        var library = new CustomItemLibrary(5, ITEMS, List.of(recipes));
        return GeneratedItemRecipes.serverResources(CustomItemRuntime.prepare(WORLD, library), library, null);
    }
    private static JsonObject json(Map<String, byte[]> files, String recipe) {
        byte[] bytes = files.get("data/worldsmith/recipe/" + GeneratedItemRecipes.recipeId(WORLD, recipe).getPath() + ".json");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static RegistryOps<com.google.gson.JsonElement> ops() { return RegistryOps.create(JsonOps.INSTANCE, VanillaRegistries.createLookup()); }

    @Test void recipesBecomeNativeRecipeFilesUnlockedOnArrival() {
        var files = files(SMELT, BLADE, RATION, NUGGETS);
        assertEquals(5, files.size());
        var blade = json(files, "ember_blade");
        assertEquals("worldsmith:world_shaped", blade.get("type").getAsString());
        assertEquals("equipment", blade.get("category").getAsString());
        assertEquals("ember_blade", blade.getAsJsonObject("world_result").get("item").getAsString());
        assertEquals("fabric:components", blade.getAsJsonObject("key").getAsJsonObject("E").get("fabric:type").getAsString());
        var smelt = json(files, "ember_shard");
        assertEquals("worldsmith:world_blasting", smelt.get("type").getAsString());
        assertEquals(CookingStation.BLAST_FURNACE.getDefaultTicks(), smelt.get("cookingtime").getAsInt());
        assertEquals("#minecraft:flowers", json(files, "trail_ration").getAsJsonArray("ingredients").get(1).getAsString());
        // A native result needs no world item at all, so it stays a plain vanilla recipe.
        var nuggets = json(files, "shard_nuggets");
        assertEquals("minecraft:crafting_shapeless", nuggets.get("type").getAsString());
        assertFalse(nuggets.has("world_result"));
        var unlock = JsonParser.parseString(new String(files.get("data/worldsmith/advancement/" + GeneratedItemRecipes.unlockId(WORLD).getPath() + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(4, unlock.getAsJsonObject("rewards").getAsJsonArray("recipes").size());
    }

    @Test void aWorldRecipeDecodesThroughItsSerializerAndMakesTheCanonicalItem() {
        // Native ingredients only: the Fabric ingredient codec needs Fabric's mixins, which a plain test JVM lacks.
        var files = files(NATIVE_BLADE);
        Recipe<?> recipe = Recipe.CODEC.parse(ops(), json(files, "iron_blade")).getOrThrow();
        var shaped = assertInstanceOf(WorldItemRecipes.Shaped.class, recipe);
        var snapshot = CustomItemRuntime.prepare(WORLD, new CustomItemLibrary(5, ITEMS, List.of(NATIVE_BLADE)));
        var made = shaped.world().create(snapshot, shaped.shown().count());
        assertTrue(snapshot.isCanonical(made), "the made blade carries every authored component, tool rules included");
        assertEquals("ember_blade", made.get(CustomItemRuntime.identityComponent()).itemId());
        // No level of this world is running here, so a stale recipe crafts nothing rather than a hollow host.
        assertTrue(shaped.assemble(CraftingInput.EMPTY).isEmpty());
    }

    @Test void aWorldItemIngredientMatchesExactlyTheCanonicalStack() {
        var files = files(NUGGETS);
        var ingredient = json(files, "shard_nuggets").getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals(CustomItemRuntime.HOST_ID, ingredient.get("base").getAsString());
        var patch = DataComponentPatch.CODEC.parse(ops(), ingredient.get("components")).getOrThrow();
        var snapshot = CustomItemRuntime.prepare(WORLD, new CustomItemLibrary(5, ITEMS, List.of(NUGGETS)));
        var shard = snapshot.stack("worldsmith:item/ember_shard", 5);
        var raw = snapshot.stack("worldsmith:item/raw_ember", 1);
        // The same test Fabric's components ingredient applies: every listed component equals the stack's.
        for (var entry : patch.entrySet()) {
            assertEquals(entry.getValue().orElseThrow(), shard.get(entry.getKey()), entry.getKey().toString());
        }
        assertTrue(patch.entrySet().stream().anyMatch(entry -> !entry.getValue().orElseThrow().equals(raw.get(entry.getKey()))));
    }

    @Test void anUnknownNativeItemIsRefusedBeforeTheWorldStarts() {
        var typo = new ItemRecipe.Shapeless("typo", List.of("minecraft:no_such_ingot"), "worldsmith:item/ember_shard", 1);
        var failure = assertThrows(IllegalArgumentException.class, () -> files(typo));
        assertTrue(failure.getMessage().contains("minecraft:no_such_ingot"));
    }
}
