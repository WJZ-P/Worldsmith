package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Frozen format-3 document projection. New default fields must never silently change an old world's hash. */
object LegacyCreaturesV3 {
    @Serializable private data class Library(val schemaVersion: Int = 1, val creatures: List<Definition> = emptyList())
    @Serializable private data class Definition(
        val id: String,
        val displayName: String,
        val category: CreatureCategory,
        val model: CreatureModel,
        val attributes: CreatureAttributes = CreatureAttributes(),
        val behavior: CreatureBehavior = CreatureBehavior(),
        val spawn: CreatureSpawn = CreatureSpawn(),
        val themeRole: String = "",
    )

    fun normalize(raw: String): JsonElement = WorldsmithJson.format.encodeToJsonElement(read(raw))

    fun decode(raw: String): CreatureLibrary {
        val legacy = read(raw)
        return CreatureLibrary(legacy.schemaVersion, legacy.creatures.map {
            CreatureDefinition(it.id, it.displayName, it.category, it.model, it.attributes, it.behavior, it.spawn, it.themeRole)
        })
    }

    fun encode(library: CreatureLibrary): String {
        // An empty default may be added by the current serializer; a nonempty new field is never discarded.
        val raw = WorldsmithJson.encode(library)
        return WorldsmithJson.encode(read(raw))
    }

    private fun read(raw: String): Library {
        val document = Json.parseToJsonElement(raw).jsonObject
        val creatures = document["creatures"]?.jsonArray ?: JsonArray(emptyList())
        val oldDefinitions = creatures.map { entry ->
            val fields = entry.jsonObject
            val drops = fields["drops"]
            require(drops == null || drops == JsonNull || drops is JsonArray && drops.isEmpty()) {
                "Format 3 contains no creature drop rules; publish new linked content as format 4"
            }
            JsonObject(fields - "drops")
        }
        return WorldsmithJson.format.decodeFromJsonElement(Library.serializer(), JsonObject(document + ("creatures" to JsonArray(oldDefinitions))))
    }
}
