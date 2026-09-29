package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recipes give this world's items a place in the native crafting loop: gather a
 * raw drop, smelt it, forge it into a tool. The validator checks the shape of a
 * recipe and the local items it names; native ids are checked by the Minecraft side.
 */
class ItemRecipesTest {
    private val texture = "a".repeat(64)
    private fun item(id: String, stack: Int = 64) = CustomItemDefinition(id, id, texture, maxStackSize = stack)
    private val items = listOf(item("raw_ember"), item("ember_shard"), item("ember_blade", 1))

    private val smelt = ItemRecipe.Cooking("ember_shard", "worldsmith:item/raw_ember", "worldsmith:item/ember_shard", station = CookingStation.BLAST_FURNACE)
    private val blade = ItemRecipe.Shaped("ember_blade", listOf(" E ", " E ", " S "),
        mapOf("E" to "worldsmith:item/ember_shard", "S" to "minecraft:stick"), "worldsmith:item/ember_blade")
    private val mix = ItemRecipe.Shapeless("shards_from_ore", listOf("#minecraft:coals", "worldsmith:content/ember_ore"), "worldsmith:item/ember_shard", count = 2)

    private fun problems(vararg recipes: ItemRecipe, schema: Int = 5) =
        CustomItemValidation.validate(CustomItemLibrary(schema, items, recipes.toList())).map { "${it.path} ${it.code}" }

    @Test
    fun `a small crafting chain validates`() {
        assertEquals(emptyList<String>(), problems(smelt, blade, mix))
    }

    @Test
    fun `recipes need the schema that introduced them`() {
        assertTrue(problems(blade, schema = 4).any { it == "recipes items.schema" })
        assertEquals(emptyList<String>(), CustomItemValidation.validate(CustomItemLibrary(4, items)).map { it.code })
    }

    @Test
    fun `malformed recipes are refused`() {
        val found = problems(
            blade.copy(id = "ragged", pattern = listOf("EE", "E")),
            blade.copy(id = "unkeyed", pattern = listOf("EX")),
            blade.copy(id = "spare_key", pattern = listOf("E")),
            smelt.copy(id = "tag_result", result = "#minecraft:coals"),
            smelt.copy(id = "ghost", ingredient = "worldsmith:item/no_such_item"),
            smelt.copy(id = "host", ingredient = "worldsmith:content/item/host"),
            blade.copy(id = "too_many", count = 2),
            ItemRecipe.Shapeless("native_only", listOf("minecraft:iron_ingot"), "minecraft:iron_nugget"),
            ItemRecipe.Shapeless("crowded", List(10) { "minecraft:stick" }, "worldsmith:item/ember_shard"),
            smelt,
            smelt,
        )
        fun has(path: String, code: String) = assertTrue("$path $code" in found, "$path $code not in $found")
        has("recipes[0].pattern", "items.recipe_pattern")
        has("recipes[1].pattern", "items.recipe_key")
        has("recipes[2].key.S", "items.recipe_key")
        has("recipes[3].result", "items.recipe_reference")
        has("recipes[4].ingredient", "items.recipe_item")
        has("recipes[5].ingredient", "items.recipe_reference")
        has("recipes[6].count", "items.recipe_count")
        has("recipes[7]", "items.recipe_scope")
        has("recipes[8].ingredients", "items.recipe_ingredients")
        has("recipes[10].id", "items.recipe_duplicate")
    }

    @Test
    fun `a library without recipes keeps its exact encoding`() {
        // Earlier bundles hash their encoded modules; an empty recipe list must not appear.
        assertFalse("recipes" in WorldsmithJson.encode(CustomItemLibrary(4, items)))
        val library = CustomItemLibrary(5, items, listOf(smelt, blade, mix))
        val json = WorldsmithJson.encode(library)
        assertTrue("\"kind\": \"cooking\"" in json && "\"kind\": \"shaped\"" in json, json)
        assertEquals(library, WorldsmithJson.decode<CustomItemLibrary>(json))
        assertEquals(library, CustomItemValidation.freeze(library))
    }

    @Test
    fun `recipe references join the content graph`() {
        val document = WorldsmithJson.format.encodeToJsonElement(CustomItemLibrary.serializer(), CustomItemLibrary(5, items, listOf(smelt, blade, mix))).jsonObject
        val plan = ExistingWorldContentModules.registry().plan(WorldContentInput("scope", mapOf("items" to document)))
        // The custom ore block is not defined in this input, so the shapeless recipe dangles.
        assertTrue(plan.diagnostics.any { it.code == "CONTENT_REFERENCE_MISSING" && it.path == "items.recipes[2].ingredients[1]" }, plan.diagnostics.toString())
        val shard = plan.catalog.entries.single { it.key == ContentKey("item", "ember_shard") }
        assertTrue(NativeContentReference("item_tag", "minecraft:coals") in shard.nativeReferences)
        assertEquals(1, plan.requirements.count { it.capability == "custom_items.recipes" })
    }
}
