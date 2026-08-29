package com.wjz.worldsmith.core.ai

import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant

/**
 * Anthropic models hosted on AWS Bedrock.
 *
 * The body is Anthropic's, with the model moved into the URL and replaced by an
 * `anthropic_version` marker. The difference that matters is authentication:
 * Bedrock signs the whole request instead of accepting a key header, so this is
 * the only dialect whose output depends on the clock.
 */
object BedrockDialect : LlmDialect {
    private const val SERVICE = "bedrock"

    /** Shown as a hint; the real host is derived from the configured region. */
    override val defaultBaseUrl: String = "https://bedrock-runtime.<region>.amazonaws.com"

    override val defaultModel: String = "anthropic.claude-3-5-sonnet-20241022-v2:0"

    override fun baseUrlOf(settings: LlmSettings): String =
        settings.baseUrl.ifBlank { "https://bedrock-runtime.${settings.region}.amazonaws.com" }.trimEnd('/')

    override fun buildRequest(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
        timestamp: Instant,
    ): LlmHttpRequest {
        if (settings.region.isBlank()) {
            throw LlmException("AWS Bedrock needs a region")
        }

        val url = baseUrlOf(settings) + "/model/" + modelOf(settings) + "/invoke"
        val body = AnthropicDialect.messagesBody(settings, systemPrompt, userPrompt) {
            put("anthropic_version", AnthropicDialect.BEDROCK_API_VERSION)
        }
        val headers = AwsSigV4.signedHeaders(
            method = "POST",
            url = URI.create(url),
            headers = mapOf("content-type" to "application/json"),
            payload = body,
            accessKeyId = settings.effectiveApiKey(),
            secretAccessKey = settings.effectiveApiSecret(),
            region = settings.region,
            service = SERVICE,
            timestamp = timestamp,
        )
        return LlmHttpRequest(url, headers, body)
    }

    override fun extractText(responseBody: String): String = AnthropicDialect.extractText(responseBody)
}
