package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Frozen schema-1 shape. New optional ability fields must never enter a format-4/5 hash. */
object LegacyItemsV1 {
    @Serializable private data class Item(
        val id: String, val displayName: String, val textureAsset: String,
        val kind: CustomItemKind = CustomItemKind.RESOURCE, val maxStackSize: Int = 64,
        val rarity: CustomItemRarity = CustomItemRarity.COMMON,
        val description: String = "", val themeRole: String = "",
    )
    @Serializable private data class Library(val schemaVersion: Int = 1, val items: List<Item> = emptyList())
    private fun legacy(library: CustomItemLibrary): Library {
        require(library.schemaVersion == 1 && library.items.all { it.equipment == null && it.consumable == null && it.actions.isEmpty() }) {
            "Item abilities require schema 2 in a format-6 bundle"
        }
        return Library(items = library.items.map { Item(it.id, it.displayName, it.textureAsset, it.kind, it.maxStackSize, it.rarity, it.description, it.themeRole) })
    }
    fun encode(library: CustomItemLibrary): String = WorldsmithJson.encode(legacy(library))
    fun normalize(raw: String): JsonElement = WorldsmithJson.format.encodeToJsonElement(Library.serializer(), legacy(WorldsmithJson.decode<CustomItemLibrary>(raw)))
}
