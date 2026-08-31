package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

/** Names one prompt document; the id is its path under `prompts/`, without the suffix. */
@Serializable
data class PromptTemplateRef(
    val id: String,
)

/**
 * The prompt documents one generation run reads.
 *
 * The pack contracts are split one file per document rather than kept in one
 * page, so that adding a field to a model changes exactly one prompt. They are
 * not split for context: measured against the built-in pack the agent is also
 * handed, all of them together are a small fraction of the run, and an agent
 * has to hold all three at once anyway because it submits the three documents
 * in a single call.
 */
@Serializable
data class PromptSet(
    val worldBible: PromptTemplateRef,
    val structureCatalog: PromptTemplateRef,
    val structureDetail: PromptTemplateRef,
    val consistencyReview: PromptTemplateRef,
    val worldEntry: PromptTemplateRef,
    val terrainPlan: PromptTemplateRef,
    val biomePlan: PromptTemplateRef,
    val featurePlan: PromptTemplateRef,
) {
    /** The three pack contracts, in the order the entry document tells an agent to decide them. */
    val contracts: Map<String, PromptTemplateRef>
        get() = linkedMapOf(
            CONTRACT_TERRAIN to terrainPlan,
            CONTRACT_BIOME to biomePlan,
            CONTRACT_FEATURE to featurePlan,
        )

    companion object {
        const val CONTRACT_TERRAIN: String = "terrain"
        const val CONTRACT_BIOME: String = "biome"
        const val CONTRACT_FEATURE: String = "feature"

        val DEFAULT = PromptSet(
            worldBible = PromptTemplateRef("world_bible"),
            structureCatalog = PromptTemplateRef("structure_catalog"),
            structureDetail = PromptTemplateRef("structure_detail"),
            consistencyReview = PromptTemplateRef("consistency_review"),
            worldEntry = PromptTemplateRef("world_entry"),
            terrainPlan = PromptTemplateRef("contract/terrain"),
            biomePlan = PromptTemplateRef("contract/biome"),
            featurePlan = PromptTemplateRef("contract/feature"),
        )
    }
}
