package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.PromptTemplateRef

data class PromptTemplate(
    val ref: PromptTemplateRef,
    val systemPrompt: String,
)

interface PromptTemplateRepository {
    fun load(ref: PromptTemplateRef): PromptTemplate
}

class ClasspathPromptTemplateRepository(
    private val classLoader: ClassLoader = ClasspathPromptTemplateRepository::class.java.classLoader,
) : PromptTemplateRepository {
    override fun load(ref: PromptTemplateRef): PromptTemplate {
        val path = "prompts/${ref.id}.system.md"
        val content = classLoader.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Prompt template '$path' was not found")
        return PromptTemplate(ref, content.trim())
    }
}
