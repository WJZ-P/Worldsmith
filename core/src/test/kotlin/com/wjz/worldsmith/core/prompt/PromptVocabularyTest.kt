package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.BandEffect
import com.wjz.worldsmith.core.model.BandRegion
import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.HumidityBand
import com.wjz.worldsmith.core.model.MaterialRole
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.PromptTemplateRef
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.RiverFill
import com.wjz.worldsmith.core.model.SurfaceHydrology
import com.wjz.worldsmith.core.model.SurfaceNoise
import com.wjz.worldsmith.core.model.SurfaceSlope
import com.wjz.worldsmith.core.model.SurfaceTemperature
import com.wjz.worldsmith.core.model.SurfaceWater
import com.wjz.worldsmith.core.model.TemperatureBand
import com.wjz.worldsmith.core.model.TemperatureVariation
import com.wjz.worldsmith.core.model.FeatureRecipe
import com.wjz.worldsmith.core.model.TreeSilhouette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Holds the contracts and the model to the same vocabulary.
 *
 * A contract is the only place an outside agent learns which names a field
 * accepts, and nothing else in the build compares the two. The failure this
 * catches is silent in both directions and was live when the test was written:
 * two of the three vegetation recipes and every biome archetype existed in the
 * model and appeared in no prompt at all, so an agent could not use them and no
 * diagnostic ever mentioned them. The other direction is a rename, where the
 * contract keeps offering a value the model dropped and every document built
 * from it is rejected for a reason the contract denies.
 */
class PromptVocabularyTest {
    private val repository = ClasspathPromptTemplateRepository()

    /** Which contract owns which enum. An enum absent here is one no agent is offered. */
    private val documented: Map<PromptTemplateRef, List<Class<out Enum<*>>>> = mapOf(
        PromptSet.DEFAULT.terrainPlan to listOf(
            BandEffect::class.java,
            BandRegion::class.java,
            RiverFill::class.java,
        ),
        PromptSet.DEFAULT.biomePlan to listOf(
            BiomeArchetypeRole::class.java,
            ReliefBand::class.java,
            TemperatureBand::class.java,
            HumidityBand::class.java,
            SurfaceSlope::class.java,
            SurfaceWater::class.java,
            SurfaceTemperature::class.java,
            SurfaceNoise::class.java,
            SurfaceHydrology::class.java,
            TemperatureVariation::class.java,
        ),
        PromptSet.DEFAULT.featurePlan to listOf(
            FeatureRecipe::class.java,
            MaterialRole::class.java,
            TreeSilhouette::class.java,
        ),
    )

    /**
     * Screaming-case words in the contracts that are not model vocabulary.
     *
     * Prose acronyms, plus the diagnostic codes a contract quotes so an agent
     * can connect a rejection to the rule that produced it. Codes are string
     * literals in the validators rather than a registry, so this list is the
     * one part of the check that a rename can still walk past.
     */
    private val notVocabulary = setOf(
        "JSON", "ASCII", "RRGGBB", "AARRGGBB",
        "NO_WOOD_IN_WORLD", "NO_WOOD_ON_LAND", "VEGETATION_BUDGET_EXCEEDED",
    )

    private val screamingCase = Regex("\\b[A-Z][A-Z_]{2,}\\b")

    @Test
    fun `every value an agent may send is named by the contract that owns it`() {
        val missing = documented.flatMap { (ref, enums) ->
            val contract = repository.load(ref).systemPrompt
            enums.flatMap { type ->
                type.enumConstants.orEmpty()
                    .filterNot { screamingCase.findAll(contract).any { token -> token.value == it.name } }
                    .map { "${ref.id} never names ${type.simpleName}.${it.name}" }
            }
        }

        assertEquals(emptyList<String>(), missing, "an undocumented value is one no agent will ever use")
    }

    @Test
    fun `no contract offers a value the model would reject`() {
        val known = documented.values.flatten().flatMap { type ->
            type.enumConstants.orEmpty().map { it.name }
        }.toSet()

        val invented = documented.keys.flatMap { ref ->
            screamingCase.findAll(repository.load(ref).systemPrompt)
                .map { it.value }
                .filterNot { it in known || it in notVocabulary }
                .map { "${ref.id} offers $it, which no model type accepts" }
                .toList()
        }.distinct()

        assertEquals(emptyList<String>(), invented, "a contract that invents a value gets its documents rejected")
    }
}
