package com.wjz.worldsmith.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

interface LlmClient {
    suspend fun complete(settings: LlmSettings, systemPrompt: String, userPrompt: String): String
}

/**
 * Sends what a dialect builds.
 *
 * Everything vendor-specific lives in the dialect, so this class stays a thin
 * shell over the JDK client and needs no dependency of its own. The clock is
 * injectable because Bedrock signatures are time-dependent.
 */
class HttpLlmClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
    private val clock: () -> Instant = Instant::now,
) : LlmClient {
    override suspend fun complete(
        settings: LlmSettings,
        systemPrompt: String,
        userPrompt: String,
    ): String = withContext(Dispatchers.IO) {
        val dialect = LlmDialects.of(settings.provider)
        if (dialect.requiresApiKey && settings.effectiveApiKey().isBlank()) {
            throw LlmException(
                "No API key for ${settings.provider}. Set one in the mod settings " +
                    "or export ${LlmSettings.API_KEY_ENV}.",
            )
        }

        val spec = dialect.buildRequest(settings, systemPrompt, userPrompt, clock())
        val request = HttpRequest.newBuilder(URI.create(spec.url))
            .timeout(Duration.ofSeconds(settings.timeoutSeconds.toLong()))
            .POST(HttpRequest.BodyPublishers.ofString(spec.body))
            .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val response = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw LlmException("Could not reach ${spec.url}: ${it.message}", it) }

        if (response.statusCode() !in 200..299) {
            throw LlmException(
                "${settings.provider} returned HTTP ${response.statusCode()}: " +
                    LlmJson.preview(response.body()),
            )
        }
        dialect.extractText(response.body())
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
    }
}
