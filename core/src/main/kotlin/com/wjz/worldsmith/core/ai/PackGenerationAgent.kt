package com.wjz.worldsmith.core.ai

import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.model.WorldGenerationRequest
import com.wjz.worldsmith.core.prompt.ClasspathPromptTemplateRepository
import com.wjz.worldsmith.core.prompt.PromptTemplateRepository
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.BiomePlanValidator
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.FeatureLibraryValidator
import kotlinx.serialization.Serializable
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.structure.StructureValidator

/** Biome, feature and structure documents authored in one in-process response. */
@Serializable
data class GeneratedPack(
    val biomes: BiomePlan,
    val features: FeatureLibrary,
    val structures: StructureLibrary = StructureLibrary(),
)

sealed interface PackGenerationResult {
    val attempts: Int

    data class Success(val pack: GeneratedPack, override val attempts: Int) : PackGenerationResult

    data class Rejected(val diagnostics: List<Diagnostic>, override val attempts: Int) : PackGenerationResult
}

/**
 * Asks a model for a pack and refuses to accept a broken one.
 *
 * Every rule the validators enforce is a rule a model can break, so a single
 * call is not enough. A rejected answer is sent back with the exact diagnostics
 * and the previous document, which turns a reroll into a repair: the model
 * usually only has to fix the cells it missed rather than invent sixteen biomes
 * again.
 *
 * Only errors block. The player prompt decides distribution; raw climate boxes
 * and intentionally uncovered parameter regions are ordinary valid input.
 */
class PackGenerationAgent(
    private val client: LlmClient,
    private val templates: PromptTemplateRepository = ClasspathPromptTemplateRepository(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    suspend fun generate(
        settings: LlmSettings,
        request: WorldGenerationRequest,
        promptSet: PromptSet = PromptSet.DEFAULT,
    ): PackGenerationResult {
        // This combined in-process transport asks for three documents in one response.
        // Keep that transport wrapper out of either document contract: MCP
        // clients submit BiomePlan and FeatureLibrary independently.
        val systemPrompt = listOf(
            templates.load(promptSet.biomePlan).systemPrompt,
            templates.load(promptSet.featurePlan).systemPrompt,
            templates.load(promptSet.structurePlan).systemPrompt,
            templates.load(PACK_OUTPUT_PROMPT).systemPrompt,
        ).joinToString("\n\n")
        var userPrompt = request.playerPrompt
        var lastDiagnostics: List<Diagnostic> = emptyList()

        for (attempt in 1..maxAttempts) {
            val reply = client.complete(settings, systemPrompt, userPrompt)
            val parsed = parse(reply)

            if (parsed != null) {
                val errors = validate(parsed).filter { it.severity == DiagnosticSeverity.ERROR }
                if (errors.isEmpty()) {
                    return PackGenerationResult.Success(parsed, attempt)
                }
                lastDiagnostics = errors
            } else {
                lastDiagnostics = listOf(
                    Diagnostic(
                        path = "response",
                        code = "UNPARSEABLE_RESPONSE",
                        severity = DiagnosticSeverity.ERROR,
                        message = "The reply was not a GeneratedPack document",
                    ),
                )
            }

            userPrompt = repairPrompt(request.playerPrompt, lastDiagnostics, reply)
        }
        return PackGenerationResult.Rejected(lastDiagnostics, maxAttempts)
    }

    private fun validate(pack: GeneratedPack): List<Diagnostic> =
        FeatureLibraryValidator.validate(pack.features) + BiomePlanValidator.validate(pack.biomes, pack.features) + StructureValidator.validate(pack.structures, pack.biomes)

    private fun parse(reply: String): GeneratedPack? =
        runCatching { WorldsmithJson.decode<GeneratedPack>(stripFence(reply)) }.getOrNull()

    /**
     * Models wrap JSON in a fenced block often enough that refusing one would
     * waste an attempt on a document that is otherwise fine.
     */
    private fun stripFence(reply: String): String {
        val trimmed = reply.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun repairPrompt(originalPrompt: String, diagnostics: List<Diagnostic>, previous: String): String =
        buildString {
            appendLine(originalPrompt)
            appendLine()
            appendLine("Your previous answer was rejected. Fix exactly these problems and return the whole document again:")
            diagnostics.forEach { appendLine("- ${it.path} ${it.code}: ${it.message}") }
            appendLine()
            appendLine("Previous answer:")
            append(previous)
        }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        private val PACK_OUTPUT_PROMPT = PromptTemplateRef("pack_generation")
    }
}
