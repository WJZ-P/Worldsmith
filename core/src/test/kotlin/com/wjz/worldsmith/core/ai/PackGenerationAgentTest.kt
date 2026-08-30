package com.wjz.worldsmith.core.ai

import com.wjz.worldsmith.core.model.WorldGenerationRequest
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackGenerationAgentTest {
    private val settings = LlmSettings(apiKey = "test")
    private val request = WorldGenerationRequest(playerPrompt = "a wind-scoured wasteland")

    /** The shipped pack is a document that is known to pass every validator. */
    private val validPack: String = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").let {
        WorldsmithJson.encode(GeneratedPack(it.biomes, it.features))
    }

    /** A biome still needs one concrete placement even though global coverage is optional. */
    private val invalidPack: String = WorldsmithJson.decode<GeneratedPack>(validPack).let { pack ->
        val broken = pack.biomes.biomes.mapIndexed { index, biome ->
            if (index == pack.biomes.biomes.lastIndex) biome.copy(slot = null, climate = null) else biome
        }
        WorldsmithJson.encode(pack.copy(biomes = pack.biomes.copy(biomes = broken)))
    }

    private class ScriptedClient(replies: List<String>) : LlmClient {
        private val remaining = replies.toMutableList()
        val prompts = mutableListOf<String>()

        override suspend fun complete(settings: LlmSettings, systemPrompt: String, userPrompt: String): String {
            prompts += userPrompt
            return remaining.removeFirst()
        }
    }

    @Test
    fun `a valid answer is accepted on the first attempt`(): Unit = runBlocking {
        val client = ScriptedClient(listOf(validPack))

        val result = PackGenerationAgent(client).generate(settings, request)

        val success = assertInstanceOf(PackGenerationResult.Success::class.java, result)
        assertEquals(1, success.attempts)
        assertEquals(16, success.pack.biomes.biomes.size)
        assertEquals(listOf(request.playerPrompt), client.prompts)
    }

    @Test
    fun `a fenced answer is unwrapped rather than wasting an attempt`(): Unit = runBlocking {
        val client = ScriptedClient(listOf("```json\n$validPack\n```"))

        val result = PackGenerationAgent(client).generate(settings, request)

        assertInstanceOf(PackGenerationResult.Success::class.java, result)
    }

    @Test
    fun `a rejected answer comes back with its diagnostics and the previous document`(): Unit = runBlocking {
        val client = ScriptedClient(listOf(invalidPack, validPack))

        val result = PackGenerationAgent(client).generate(settings, request)

        val success = assertInstanceOf(PackGenerationResult.Success::class.java, result)
        assertEquals(2, success.attempts)

        val repair = client.prompts[1]
        assertTrue("MISSING_CLIMATE" in repair, "the repair prompt should name the failure")
        assertTrue(request.playerPrompt in repair, "the repair prompt should keep the original request")
        assertTrue("Previous answer:" in repair, "the repair prompt should include what to fix")
    }

    @Test
    fun `an unparseable answer is treated as a failure and retried`(): Unit = runBlocking {
        val client = ScriptedClient(listOf("I cannot help with that.", validPack))

        val result = PackGenerationAgent(client).generate(settings, request)

        assertInstanceOf(PackGenerationResult.Success::class.java, result)
        assertTrue("UNPARSEABLE_RESPONSE" in client.prompts[1])
    }

    @Test
    fun `giving up reports the last diagnostics rather than throwing`(): Unit = runBlocking {
        val client = ScriptedClient(List(3) { invalidPack })

        val result = PackGenerationAgent(client, maxAttempts = 3).generate(settings, request)

        val rejected = assertInstanceOf(PackGenerationResult.Rejected::class.java, result)
        assertEquals(3, rejected.attempts)
        assertTrue(rejected.diagnostics.all { it.code == "MISSING_CLIMATE" })
        assertEquals(3, client.prompts.size)
    }
}
