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
        ).plus(it.contracts.values).distinct()
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
    fun `the contract index exposes persistent authoring alongside existing planning and domain contracts`() {
        val contracts = PromptSet.DEFAULT.contracts

        assertEquals(
            listOf(PromptSet.CONTRACT_GRAND_WORLD, "world_bible", "module_briefs", PromptSet.CONTRACT_TERRAIN, PromptSet.CONTRACT_BIOME, PromptSet.CONTRACT_FEATURE, PromptSet.CONTRACT_STRUCTURE,
                PromptSet.CONTRACT_ARCHITECTURE, PromptSet.CONTRACT_DRAW),
            contracts.keys.toList(),
            "the shared contract index retains legacy worldgen entries; mode-specific workflow progress chooses the first authoring action",
        )
        assertTrue(contracts.values.all { it in refs })
    }

    @Test
    fun `complete-world entry puts the setting and self-review before physical production`() {
        val repository = ClasspathPromptTemplateRepository()
        val entry = repository.load(PromptSet.DEFAULT.worldEntry).systemPrompt
        val bible = entry.indexOf("WorldBible")
        val review = entry.indexOf("AI self-review")
        val production = entry.indexOf("**terrain first**")
        assertTrue(bible >= 0 && review > bible && production > review)
        val worldBible = repository.load(PromptSet.DEFAULT.contracts.getValue("world_bible")).systemPrompt
        val briefs = repository.load(PromptSet.DEFAULT.contracts.getValue("module_briefs")).systemPrompt
        assertTrue("AUTHORING_AI" in worldBible)
        assertTrue("expectedBasisDigest" in worldBible && "expectedContentDigest" in worldBible)
        assertTrue("requiredCheckIds" in worldBible && "evidenceRoots" in worldBible)
        assertTrue("WORLDGEN_ONLY and STANDALONE stay" in worldBible)
        assertTrue("removeIds" in briefs && "Unmentioned IDs are" in briefs)
        assertTrue("evidenceDocumentIncluded" in briefs)
        assertTrue("briefIds" in briefs)
    }
}
