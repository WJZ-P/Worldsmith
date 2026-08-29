package com.wjz.worldsmith.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LlmDialectTest {
    private val timestamp: Instant = Instant.parse("2026-08-29T07:30:00Z")

    @Test
    fun `openai sends max_completion_tokens and a bearer token`() {
        val settings = LlmSettings(provider = LlmProvider.OPENAI, apiKey = "sk-test", model = "gpt-5")

        val request = LlmDialects.of(settings.provider).buildRequest(settings, "system", "user", timestamp)
        val body = Json.parseToJsonElement(request.body).jsonObject

        assertEquals("https://api.openai.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test", request.headers["authorization"])
        assertEquals(8192, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_tokens"])
    }

    @Test
    fun `an openai compatible endpoint keeps max_tokens and honours a custom base url`() {
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            baseUrl = "http://192.168.1.10:1234/v1/",
            apiKey = "local",
            model = "qwen3",
        )

        val request = LlmDialects.of(settings.provider).buildRequest(settings, "system", "user", timestamp)
        val body = Json.parseToJsonElement(request.body).jsonObject

        assertEquals("http://192.168.1.10:1234/v1/chat/completions", request.url)
        assertEquals(8192, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_completion_tokens"])
    }

    @Test
    fun `a compatible endpoint with no model is refused rather than guessed`() {
        val settings = LlmSettings(provider = LlmProvider.OPENAI_COMPATIBLE, apiKey = "local")

        assertThrows(LlmException::class.java) {
            LlmDialects.of(settings.provider).buildRequest(settings, "system", "user", timestamp)
        }
    }

    @Test
    fun `a local compatible endpoint may omit authentication`() {
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = "qwen3",
        )

        val dialect = LlmDialects.of(settings.provider)
        val request = dialect.buildRequest(settings, "system", "user", timestamp)

        assertFalse(dialect.requiresApiKey)
        assertFalse(request.headers.containsKey("authorization"))
    }

    @Test
    fun `anthropic puts the system prompt beside the messages`() {
        val settings = LlmSettings(provider = LlmProvider.ANTHROPIC, apiKey = "sk-ant")

        val request = LlmDialects.of(settings.provider).buildRequest(settings, "be brief", "hello", timestamp)
        val body = Json.parseToJsonElement(request.body).jsonObject

        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertEquals(AnthropicDialect.API_VERSION, request.headers["anthropic-version"])
        assertEquals("be brief", body["system"]?.jsonPrimitive?.content)
        assertEquals("claude-sonnet-5", body["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `bedrock moves the model into the path and signs the request`() {
        val settings = LlmSettings(
            provider = LlmProvider.AWS_BEDROCK,
            apiKey = "AKIAEXAMPLE",
            apiSecret = "secret",
            region = "eu-central-1",
        )

        val request = LlmDialects.of(settings.provider).buildRequest(settings, "system", "user", timestamp)
        val body = Json.parseToJsonElement(request.body).jsonObject

        assertEquals(
            "https://bedrock-runtime.eu-central-1.amazonaws.com" +
                "/model/anthropic.claude-3-5-sonnet-20241022-v2:0/invoke",
            request.url,
        )
        assertEquals(AnthropicDialect.BEDROCK_API_VERSION, body["anthropic_version"]?.jsonPrimitive?.content)
        assertNull(body["model"])
        assertEquals("20260829T073000Z", request.headers["x-amz-date"])

        val authorization = request.headers.getValue("authorization")
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20260829/eu-central-1/bedrock/aws4_request"))
        assertTrue(authorization.contains("SignedHeaders=content-type;host;x-amz-date"))
        // host is signed but must not be sent by hand.
        assertFalse(request.headers.containsKey("host"))
    }

    @Test
    fun `a bedrock signature depends on the clock`() {
        val settings = LlmSettings(
            provider = LlmProvider.AWS_BEDROCK,
            apiKey = "AKIAEXAMPLE",
            apiSecret = "secret",
        )
        val dialect = LlmDialects.of(settings.provider)

        val first = dialect.buildRequest(settings, "system", "user", timestamp)
        val same = dialect.buildRequest(settings, "system", "user", timestamp)
        val later = dialect.buildRequest(settings, "system", "user", timestamp.plusSeconds(1))

        assertEquals(first.headers["authorization"], same.headers["authorization"])
        assertNotEquals(first.headers["authorization"], later.headers["authorization"])
    }

    @Test
    fun `bedrock refuses to sign without a secret`() {
        val settings = LlmSettings(provider = LlmProvider.AWS_BEDROCK, apiKey = "AKIAEXAMPLE")

        assertThrows(LlmException::class.java) {
            LlmDialects.of(settings.provider).buildRequest(settings, "system", "user", timestamp)
        }
    }

    @Test
    fun `openai replies are read out of the first choice`() {
        val text = OpenAiDialect.OPENAI.extractText(
            """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""",
        )

        assertEquals("hello", text)
    }

    @Test
    fun `anthropic replies join every text block and ignore the rest`() {
        val text = AnthropicDialect.extractText(
            """{"content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":"a"},{"type":"text","text":"b"}]}""",
        )

        assertEquals("ab", text)
    }

    @Test
    fun `an unreadable reply names the provider rather than crashing`() {
        assertThrows(LlmException::class.java) { AnthropicDialect.extractText("not json") }
        assertThrows(LlmException::class.java) { OpenAiDialect.OPENAI.extractText("""{"choices":[]}""") }
    }

    @Test
    fun `a blank key falls back to the environment`() {
        val settings = LlmSettings(apiKey = "")
        val environment = mapOf(LlmSettings.API_KEY_ENV to "from-env")

        assertEquals("from-env", settings.effectiveApiKey(environment::get))
        assertEquals("typed", settings.copy(apiKey = "typed").effectiveApiKey(environment::get))
    }
}
