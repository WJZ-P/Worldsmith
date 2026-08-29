package com.wjz.worldsmith.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * The `/chat/completions` shape, spoken by OpenAI and by everything that copied
 * it.
 *
 * The two instances differ only in their defaults and in the token field name:
 * current OpenAI models reject `max_tokens` and want `max_completion_tokens`,
 * while the clones have not followed. That single word is the whole reason
 * these are separate providers rather than one.
 */
class OpenAiDialect private constructor(
    override val defaultBaseUrl: String,
    override val defaultModel: String,
    private val maxTokensField: String,
    override val requiresApiKey: Boolean,
) : LlmDialect {
    override fun buildRequest(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
        timestamp: Instant,
    ): LlmHttpRequest {
        val body = buildJsonObject {
            put("model", modelOf(settings))
            put(maxTokensField, settings.maxOutputTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                }
            }
        }
        val headers = buildMap<String, String> {
            put("content-type", "application/json")
            settings.effectiveApiKey().takeIf(String::isNotBlank)?.let { key ->
                put("authorization", "Bearer $key")
            }
        }
        return LlmHttpRequest(
            url = baseUrlOf(settings) + "/chat/completions",
            headers = headers,
            body = body.toString(),
        )
    }

    override fun extractText(responseBody: String): String {
        val root = LlmJson.parseObject(responseBody)
        val choice = runCatching { root.getValue("choices").jsonArray.first().jsonObject }
            .getOrElse { throw LlmException("Response had no choices: " + LlmJson.preview(responseBody)) }
        val content = runCatching { choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.contentOrNull }
            .getOrNull()
        if (content.isNullOrBlank()) {
            throw LlmException("Response choice had no text content: " + LlmJson.preview(responseBody))
        }
        return content
    }

    companion object {
        val OPENAI: OpenAiDialect = OpenAiDialect(
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-5",
            maxTokensField = "max_completion_tokens",
            requiresApiKey = true,
        )

        /** No default model: a compatible endpoint could be serving anything. */
        val COMPATIBLE: OpenAiDialect = OpenAiDialect(
            defaultBaseUrl = "http://localhost:11434/v1",
            defaultModel = "",
            maxTokensField = "max_tokens",
            requiresApiKey = false,
        )
    }
}

internal object LlmJson {
    private val lenient = Json { ignoreUnknownKeys = true }

    fun parseObject(body: String): JsonObject = runCatching { lenient.parseToJsonElement(body).jsonObject }
        .getOrElse { throw LlmException("Response was not a JSON object: " + preview(body), it) }

    /** Truncated so an error message never dumps a whole model response into a log. */
    fun preview(body: String): String = body.take(400).replace('\n', ' ')
}
