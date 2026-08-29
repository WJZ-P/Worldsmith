package com.wjz.worldsmith.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * Anthropic's Messages API.
 *
 * The system prompt is a top-level field rather than a message, and the reply
 * arrives as a list of content blocks rather than a single string. Bedrock
 * serves the same body shape, so both live here.
 */
object AnthropicDialect : LlmDialect {
    const val API_VERSION: String = "2023-06-01"

    /** Bedrock replaces the `model` field with this marker. */
    const val BEDROCK_API_VERSION: String = "bedrock-2023-05-31"

    override val defaultBaseUrl: String = "https://api.anthropic.com"
    override val defaultModel: String = "claude-sonnet-5"

    override fun buildRequest(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
        timestamp: Instant,
    ): LlmHttpRequest {
        val body = messagesBody(settings, systemPrompt, userPrompt) {
            put("model", modelOf(settings))
        }
        return LlmHttpRequest(
            url = baseUrlOf(settings) + "/v1/messages",
            headers = mapOf(
                "content-type" to "application/json",
                "x-api-key" to settings.effectiveApiKey(),
                "anthropic-version" to API_VERSION,
            ),
            body = body,
        )
    }

    /**
     * Builds the shared request body. [identity] adds whichever field names the
     * model for this transport: a `model` for the direct API, an
     * `anthropic_version` for Bedrock.
     */
    fun messagesBody(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
        identity: JsonObjectBuilder.() -> Unit,
    ): String = buildJsonObject {
        identity()
        put("max_tokens", settings.maxOutputTokens)
        put("system", systemPrompt)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", userPrompt)
            }
        }
    }.toString()

    /** Joins every text block, because a reply may be split across several. */
    override fun extractText(responseBody: String): String {
        val root: JsonObject = LlmJson.parseObject(responseBody)
        val blocks = runCatching { root.getValue("content").jsonArray }
            .getOrElse { throw LlmException("Response had no content blocks: " + LlmJson.preview(responseBody)) }
        val text = blocks.mapNotNull { block ->
            val obj = runCatching { block.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") null
            else obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("")
        if (text.isBlank()) {
            throw LlmException("Response had no text block: " + LlmJson.preview(responseBody))
        }
        return text
    }
}
