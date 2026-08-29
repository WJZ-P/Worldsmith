package com.wjz.worldsmith.core.settings

import com.wjz.worldsmith.core.ai.LlmProvider
import com.wjz.worldsmith.core.ai.LlmSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorldsmithSettingsStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a missing settings file loads defaults without creating it`() {
        val path = directory.resolve("worldsmith.json")

        assertEquals(WorldsmithSettings(), WorldsmithSettingsStore.load(path))
        assertFalse(Files.exists(path))
    }

    @Test
    fun `settings round trip through readable json`() {
        val path = directory.resolve("nested/worldsmith.json")
        val settings = WorldsmithSettings(
            llm = LlmSettings(
                provider = LlmProvider.OPENAI,
                baseUrl = " https://example.invalid/v1/ ",
                apiKey = " test-key ",
                model = " test-model ",
                maxOutputTokens = 1,
                timeoutSeconds = 9999,
            ),
        )

        WorldsmithSettingsStore.save(path, settings)
        val loaded = WorldsmithSettingsStore.load(path)

        assertEquals(LlmProvider.OPENAI, loaded.llm.provider)
        assertEquals("https://example.invalid/v1/", loaded.llm.baseUrl)
        assertEquals("test-key", loaded.llm.apiKey)
        assertEquals("test-model", loaded.llm.model)
        assertEquals(LlmSettings.MIN_OUTPUT_TOKENS, loaded.llm.maxOutputTokens)
        assertEquals(LlmSettings.MAX_TIMEOUT_SECONDS, loaded.llm.timeoutSeconds)

        val json = Files.readString(path)
        assertTrue(json.contains("\"provider\": \"OPENAI\""))
        assertTrue(json.contains("\"apiKey\": \"test-key\""))
    }
}
