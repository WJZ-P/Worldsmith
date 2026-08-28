package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.PromptSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptTemplateRepositoryTest {
    @Test
    fun `version one prompt set resolves every system prompt`() {
        val repository = ClasspathPromptTemplateRepository()
        val refs = PromptSet.V1.let {
            listOf(it.worldBible, it.structureCatalog, it.structureDetail, it.consistencyReview)
        }

        val templates = refs.map(repository::load)

        assertEquals(refs, templates.map { it.ref })
        assertTrue(templates.all { it.systemPrompt.isNotBlank() })
        assertTrue(templates.all { "Worldsmith" in it.systemPrompt })
    }
}
