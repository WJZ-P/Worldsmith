package com.wjz.worldsmith.core.pipeline

enum class GenerationStage {
    INTENT_EXPANSION,
    STRUCTURE_CATALOG_PLANNING,
    STRUCTURE_DETAIL_GENERATION,
    CONSISTENCY_REVIEW,
    ASSEMBLY,
    COMPLETE,
}

data class GenerationProgress(
    val stage: GenerationStage,
    val completed: Int = 0,
    val total: Int = 1,
    val detail: String? = null,
)
