package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PromptTemplateRef(
    val id: String,
)

@Serializable
data class PromptSet(
    val worldBible: PromptTemplateRef,
    val structureCatalog: PromptTemplateRef,
    val structureDetail: PromptTemplateRef,
    val consistencyReview: PromptTemplateRef,
) {
    companion object {
        val DEFAULT = PromptSet(
            worldBible = PromptTemplateRef("world_bible"),
            structureCatalog = PromptTemplateRef("structure_catalog"),
            structureDetail = PromptTemplateRef("structure_detail"),
            consistencyReview = PromptTemplateRef("consistency_review"),
        )
    }
}
