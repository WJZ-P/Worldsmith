package com.wjz.worldsmith.core.ai

import kotlinx.serialization.Serializable

/**
 * Which wire protocol to speak.
 *
 * [OPENAI_COMPATIBLE] is deliberately separate from [OPENAI]: the shape is the
 * same, but the defaults differ and current OpenAI models want
 * `max_completion_tokens` where every clone still wants `max_tokens`.
 * Pointing it at Ollama, LM Studio, vLLM, DeepSeek or OpenRouter is the
 * intended use.
 */
@Serializable
enum class LlmProvider {
    ANTHROPIC,
    OPENAI,
    OPENAI_COMPATIBLE,
    AWS_BEDROCK,
}

/**
 * Everything needed to reach one model.
 *
 * Blank [baseUrl] and [model] mean "use the provider default", so a fresh
 * install only has to supply a key. [apiSecret] and [region] are only read by
 * AWS Bedrock, which signs requests instead of sending a bearer token.
 */
@Serializable
data class LlmSettings(
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
    val region: String = "us-east-1",
    val model: String = "",
    val maxOutputTokens: Int = 8192,
    val timeoutSeconds: Int = 120,
) {
    /**
     * The key actually used, falling back to the environment when the saved
     * field is blank.
     *
     * A key typed into the settings screen is written to disk in plain text,
     * which is fine for a single-player tool and wrong for anything shared.
     * Leaving the field blank and exporting [API_KEY_ENV] keeps the secret out
     * of the config file entirely.
     */
    fun effectiveApiKey(environment: (String) -> String? = System::getenv): String =
        apiKey.ifBlank { environment(API_KEY_ENV).orEmpty() }

    fun effectiveApiSecret(environment: (String) -> String? = System::getenv): String =
        apiSecret.ifBlank { environment(API_SECRET_ENV).orEmpty() }

    companion object {
        const val API_KEY_ENV: String = "WORLDSMITH_API_KEY"
        const val API_SECRET_ENV: String = "WORLDSMITH_API_SECRET"

        const val MIN_OUTPUT_TOKENS: Int = 256
        const val MAX_OUTPUT_TOKENS: Int = 64000
        const val MIN_TIMEOUT_SECONDS: Int = 10
        const val MAX_TIMEOUT_SECONDS: Int = 600
    }
}
