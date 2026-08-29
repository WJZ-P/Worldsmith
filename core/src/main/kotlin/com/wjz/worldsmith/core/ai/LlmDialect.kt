package com.wjz.worldsmith.core.ai

import java.time.Instant

/**
 * A request that has been built but not sent.
 *
 * Keeping this a plain value is what makes the wire format testable: every
 * vendor quirk lives in [LlmDialect.buildRequest], and a test can assert the
 * exact headers and body without a network.
 */
data class LlmHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One vendor's way of asking the same question.
 *
 * Worldsmith only ever needs "here is a system prompt and one user message,
 * give me back text", so the interface stays that narrow. Streaming, tools and
 * multi-turn chat are deliberately absent.
 */
interface LlmDialect {
    val defaultBaseUrl: String

    /** Blank means the provider has no sensible default and the user must choose. */
    val defaultModel: String

    /** Local OpenAI-compatible servers commonly run without authentication. */
    val requiresApiKey: Boolean
        get() = true

    fun buildRequest(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
        timestamp: Instant,
    ): LlmHttpRequest

    /** Pulls the assistant text out of a successful response body. */
    fun extractText(responseBody: String): String

    fun baseUrlOf(settings: LlmSettings): String =
        settings.baseUrl.ifBlank { defaultBaseUrl }.trimEnd('/')

    fun modelOf(settings: LlmSettings): String {
        val model = settings.model.ifBlank { defaultModel }
        if (model.isBlank()) {
            throw LlmException("No model is set for ${settings.provider} and it has no default")
        }
        return model
    }
}

object LlmDialects {
    fun of(provider: LlmProvider): LlmDialect = when (provider) {
        LlmProvider.ANTHROPIC -> AnthropicDialect
        LlmProvider.OPENAI -> OpenAiDialect.OPENAI
        LlmProvider.OPENAI_COMPATIBLE -> OpenAiDialect.COMPATIBLE
        LlmProvider.AWS_BEDROCK -> BedrockDialect
    }
}
