package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.PromptSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptTemplateRepositoryTest {
    private val refs = PromptSet.DEFAULT.let {
        listOf(
            it.worldBible,
            it.structureCatalog,
            it.structureDetail,
            it.consistencyReview,
            it.worldEntry,
            it.terrainPlan,
            it.biomePlan,
            it.featurePlan,
            it.structurePlan,
        )
    }

    @Test
    fun `default prompt set resolves every system prompt`() {
        val repository = ClasspathPromptTemplateRepository()

        val templates = refs.map(repository::load)

        assertEquals(refs, templates.map { it.ref })
        assertTrue(templates.all { it.systemPrompt.isNotBlank() })
        assertTrue(templates.all { "Worldsmith" in it.systemPrompt })
    }

    @Test
    fun `the contract set is exactly the documents a pack is written from`() {
        val contracts = PromptSet.DEFAULT.contracts

        assertEquals(
            listOf(PromptSet.CONTRACT_TERRAIN, PromptSet.CONTRACT_BIOME, PromptSet.CONTRACT_FEATURE, PromptSet.CONTRACT_STRUCTURE),
            contracts.keys.toList(),
            "the order is the order the entry document tells an agent to decide them in",
        )
        assertTrue(contracts.values.all { it in refs })
    }
}
