package com.wjz.worldsmith.core.hash

import com.wjz.worldsmith.core.model.WorldsmithPackManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureIndex
import com.wjz.worldsmith.core.structure.StructurePackIO
import com.wjz.worldsmith.core.content.ContentAssetValidation
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.structure.StructureBlueprint
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.content.WorldTheme
import com.wjz.worldsmith.core.content.CustomBlockLibrary
import com.wjz.worldsmith.core.content.CreatureLibrary
import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.content.QuestLibrary
import com.wjz.worldsmith.core.pack.LegacyCreaturesV3
import com.wjz.worldsmith.core.pack.LegacyItemsV1

/** Computes the immutable id of the files that affect world generation. */
object WorldsmithHashUtil {
    private const val HASH_DOMAIN_V3 = "worldsmith-world-content-bundle-v3"
    private const val HASH_DOMAIN_V4 = "worldsmith-world-content-bundle-v4"
    private const val HASH_DOMAIN_V5 = "worldsmith-world-content-bundle-v5"
    private const val HASH_DOMAIN_V6 = "worldsmith-world-content-bundle-v6"

    @JvmStatic @JvmOverloads
    fun computeGenerationId(manifest: WorldsmithPackManifest, contents: Map<String, String>,binaries:Map<String,ByteArray> = emptyMap()): String {
        WorldContentBundleIO.validateManifest(manifest)
        require(contents.size <= 1024 && contents.values.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() } <= WorldContentBundleIO.MAX_TEXT_BYTES) { "Bundle text budget exceeded" }
        require(binaries.size <= 1024 && binaries.values.sumOf { it.size.toLong() } <= WorldContentBundleIO.MAX_DRAWING_BYTES + ContentAssetValidation.MAX_TOTAL_BYTES) { "Bundle binary budget exceeded" }
        val digest = MessageDigest.getInstance("SHA-256")
        updateField(digest, "domain", when (manifest.formatVersion) {
            WorldContentBundleIO.LEGACY_FORMAT_VERSION -> HASH_DOMAIN_V3
            WorldContentBundleIO.PREVIOUS_FORMAT_VERSION -> HASH_DOMAIN_V4
            5 -> HASH_DOMAIN_V5
            else -> HASH_DOMAIN_V6
        })
        updateField(digest, "formatVersion", manifest.formatVersion.toString())

        manifest.modules.toSortedMap().forEach { (role, file) ->
            val path = file.path
            val raw = requireNotNull(contents[path]) { "Missing generation content '$path'" }
            val parsed = Json.parseToJsonElement(raw)
            require(parsed.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull == file.schemaVersion) { "Module schema differs from manifest: $role" }
            if(manifest.formatVersion==WorldContentBundleIO.PREVIOUS_FORMAT_VERSION && role=="creatures")
                require(WorldsmithJson.decode<CreatureLibrary>(raw).creatures.none {it.boss!=null}) {"Format 4 has no Boss behavior; publish Boss profiles in format 5 with creature schema 2"}
            updateField(digest, "module:$role", file.schemaVersion.toString())
            val normalized = if (manifest.formatVersion == WorldContentBundleIO.LEGACY_FORMAT_VERSION && role == "creatures")
                LegacyCreaturesV3.normalize(raw) else if (role == "items" && manifest.formatVersion < 6)
                LegacyItemsV1.normalize(raw) else normalizeTyped(role, raw)
            updateField(digest, "$role:$path", canonicalJson(normalized))
        }

        val index = WorldsmithJson.decode<StructureIndex>(requireNotNull(contents[manifest.modulePath("structures")]))
        StructurePackIO.paths(index).forEach { path ->
            val raw = requireNotNull(contents[path]) { "Missing generation content '$path'" }
            val blueprint = WorldsmithJson.decode<StructureBlueprint>(raw)
            if (blueprint.interactions.any { it is StructureInteraction.BossSpawner }) {
                require(manifest.formatVersion >= 5) { "Boss spawner encounters require bundle format 5" }
                require(index.schemaVersion == 2) { "Boss spawner encounters require structure schema 2" }
            }
            updateField(digest, "blueprint:$path", canonicalJson(WorldsmithJson.format.encodeToJsonElement(StructureBlueprint.serializer(), blueprint)))
        }
        index.artifacts.toSortedMap().forEach { (id,meta)->
            require(id.matches(Regex("[a-f0-9]{64}")) && meta.id==id)
            val bytes=requireNotNull(binaries[meta.path]) { "Missing frozen drawing ${meta.path}" }
            require(com.wjz.worldsmith.core.draw.DrawSnapshotCodec.hash(bytes)==meta.dataHash)
            updateField(digest,"drawing:${meta.path}",meta.dataHash)
        }

        ContentAssetValidation.verifyAll(manifest.assets, manifest.assets.associate { it.id to requireNotNull(binaries[it.path]) { "Missing asset bytes: ${it.id}" } })
        manifest.assets.sortedBy { it.id }.forEach { asset ->
            updateField(digest, "asset:${asset.id}", canonicalJson(WorldsmithJson.format.encodeToJsonElement(com.wjz.worldsmith.core.content.ContentAsset.serializer(), asset)))
        }
        val expectedTexts = manifest.modules.values.map { it.path }.toSet() + StructurePackIO.paths(index)
        val expectedBinaries = index.artifacts.values.map { it.path }.toSet() + manifest.assets.map { requireNotNull(it.path) }
        require(contents.keys == expectedTexts && binaries.keys == expectedBinaries) { "Bundle contains missing or untracked files" }

        return HexFormat.of().formatHex(digest.digest())
    }

    @JvmStatic @JvmOverloads
    fun finalizeManifest(manifest: WorldsmithPackManifest, contents: Map<String, String>,binaries:Map<String,ByteArray> = emptyMap()): WorldsmithPackManifest =
        manifest.copy(id = computeGenerationId(manifest, contents,binaries))

    @JvmStatic
    fun matches(manifest: WorldsmithPackManifest, computedId: String): Boolean =
        manifest.id.equals(computedId, ignoreCase = true)

    private fun updateField(digest: MessageDigest, name: String, value: String) {
        updateBytes(digest, name.toByteArray(StandardCharsets.UTF_8))
        updateBytes(digest, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun updateBytes(digest: MessageDigest, bytes: ByteArray) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${JsonPrimitive(key)}:${canonicalJson(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalJson)
        else -> element.toString()
    }

    private fun normalize(role: String, element: JsonElement): JsonElement {
        if (role == "terrain" && element is JsonObject && element["seed"] === JsonNull) {
            return JsonObject(element - "seed")
        }
        return element
    }

    /** Defaults and optional fields are semantic, not an accidental dependence on author JSON spelling. */
    private fun normalizeTyped(role: String, raw: String): JsonElement = when (role) {
        "terrain" -> WorldsmithJson.format.encodeToJsonElement(TerrainPlan.serializer(), WorldsmithJson.decode<TerrainPlan>(raw))
        "biomes" -> WorldsmithJson.format.encodeToJsonElement(BiomePlan.serializer(), WorldsmithJson.decode<BiomePlan>(raw))
        "features" -> WorldsmithJson.format.encodeToJsonElement(FeatureLibrary.serializer(), WorldsmithJson.decode<FeatureLibrary>(raw))
        "structures" -> WorldsmithJson.format.encodeToJsonElement(StructureIndex.serializer(), WorldsmithJson.decode<StructureIndex>(raw))
        "theme" -> WorldsmithJson.format.encodeToJsonElement(WorldTheme.serializer(), WorldsmithJson.decode<WorldTheme>(raw))
        "blocks" -> WorldsmithJson.format.encodeToJsonElement(CustomBlockLibrary.serializer(), WorldsmithJson.decode<CustomBlockLibrary>(raw))
        "creatures" -> WorldsmithJson.format.encodeToJsonElement(CreatureLibrary.serializer(), WorldsmithJson.decode<CreatureLibrary>(raw))
        "items" -> WorldsmithJson.format.encodeToJsonElement(CustomItemLibrary.serializer(), WorldsmithJson.decode<CustomItemLibrary>(raw))
        "quests" -> WorldsmithJson.format.encodeToJsonElement(QuestLibrary.serializer(), WorldsmithJson.decode<QuestLibrary>(raw))
        else -> error("Uninstalled content module '$role'")
    }
}
