package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

@Serializable
data class PromptTemplateRef(
    val id: String,
    val version: Int,
)

@Serializable
data class PromptSet(
    val worldBible: PromptTemplateRef,
    val structureCatalog: PromptTemplateRef,
    val structureDetail: PromptTemplateRef,
    val consistencyReview: PromptTemplateRef,
) {
    companion object {
        val V1 = PromptSet(
            worldBible = PromptTemplateRef("world_bible", WorldsmithCore.PROMPT_SET_VERSION),
            structureCatalog = PromptTemplateRef("structure_catalog", WorldsmithCore.PROMPT_SET_VERSION),
            structureDetail = PromptTemplateRef("structure_detail", WorldsmithCore.PROMPT_SET_VERSION),
            consistencyReview = PromptTemplateRef("consistency_review", WorldsmithCore.PROMPT_SET_VERSION),
        )
    }
}
