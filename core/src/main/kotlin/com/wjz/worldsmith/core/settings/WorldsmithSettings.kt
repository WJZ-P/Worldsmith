package com.wjz.worldsmith.core.settings

import com.wjz.worldsmith.core.ai.LlmSettings
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class WorldsmithSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val llm: LlmSettings = LlmSettings(),
    val mcp: McpSettings = McpSettings(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Reads and writes the settings file.
 *
 * This lives in core rather than the mod because none of it touches Minecraft;
 * the mod only supplies the path. A missing file is not an error, so a first
 * run starts from defaults without anyone having to create anything.
 *
 * The file holds an API key in plain text when one is typed into the settings
 * screen. That is a reasonable trade for a single-player tool and a bad one for
 * a shared machine, which is why [LlmSettings.effectiveApiKey] can fall back to
 * the environment.
 */
object WorldsmithSettingsStore {
    @JvmStatic
    @Throws(IOException::class)
    fun load(path: Path): WorldsmithSettings {
        if (!Files.isRegularFile(path)) {
            return WorldsmithSettings()
        }
        val text = Files.readString(path, StandardCharsets.UTF_8)
        return sanitize(WorldsmithJson.decode<WorldsmithSettings>(text))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun save(path: Path, settings: WorldsmithSettings) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, WorldsmithJson.encode(sanitize(settings)), StandardCharsets.UTF_8)
    }

    /** Clamps anything a hand-edited file could put out of range. */
    @JvmStatic
    fun sanitize(settings: WorldsmithSettings): WorldsmithSettings = settings.copy(
        llm = settings.llm.copy(
            baseUrl = settings.llm.baseUrl.trim(),
            apiKey = settings.llm.apiKey.trim(),
            apiSecret = settings.llm.apiSecret.trim(),
            region = settings.llm.region.trim().ifBlank { "us-east-1" },
            model = settings.llm.model.trim(),
            maxOutputTokens = settings.llm.maxOutputTokens
                .coerceIn(LlmSettings.MIN_OUTPUT_TOKENS, LlmSettings.MAX_OUTPUT_TOKENS),
            timeoutSeconds = settings.llm.timeoutSeconds
                .coerceIn(LlmSettings.MIN_TIMEOUT_SECONDS, LlmSettings.MAX_TIMEOUT_SECONDS),
        ),
        mcp = settings.mcp.copy(
            port = settings.mcp.port.coerceIn(McpSettings.MIN_PORT, McpSettings.MAX_PORT),
        ),
    )
}
