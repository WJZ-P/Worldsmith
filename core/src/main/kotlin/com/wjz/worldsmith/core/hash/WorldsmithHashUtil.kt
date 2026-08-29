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

/** Computes the immutable id of the files that affect world generation. */
object WorldsmithHashUtil {
    private const val HASH_DOMAIN = "worldsmith-generation-pack-v1"

    @JvmStatic
    fun computeGenerationId(manifest: WorldsmithPackManifest, contents: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateField(digest, "domain", HASH_DOMAIN)
        updateField(digest, "formatVersion", manifest.formatVersion.toString())

        listOf(
            "terrain" to manifest.files.terrain,
            "biomeLayout" to manifest.files.biomeLayout,
            "biomeSkins" to manifest.files.biomeSkins,
        ).forEach { (role, path) ->
            val raw = requireNotNull(contents[path]) { "Missing generation content '$path'" }
            val parsed = Json.parseToJsonElement(raw)
            updateField(digest, "$role:$path", canonicalJson(normalize(role, parsed)))
        }

        return HexFormat.of().formatHex(digest.digest())
    }

    @JvmStatic
    fun finalizeManifest(manifest: WorldsmithPackManifest, contents: Map<String, String>): WorldsmithPackManifest =
        manifest.copy(id = computeGenerationId(manifest, contents))

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
}
