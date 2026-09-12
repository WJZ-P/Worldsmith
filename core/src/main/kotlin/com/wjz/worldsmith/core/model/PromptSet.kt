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
 * Domain contracts own their field grammar; the grand-world guide coordinates
 * intent across them. MCP exposes full text, indexes and stable sections so a
 * resumable run can keep a compact world plan and retrieve only the current
 * domain's details. Domain documents still have to agree at publication.
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
    val structurePlan: PromptTemplateRef,
) {
    /** Planning and worldgen/architecture contracts; typed content modules have their own MCP lookup. */
    val contracts: Map<String, PromptTemplateRef>
        get() = linkedMapOf(
            CONTRACT_GRAND_WORLD to PromptTemplateRef("contract/grand_world"),
            CONTRACT_TERRAIN to terrainPlan,
            CONTRACT_BIOME to biomePlan,
            CONTRACT_FEATURE to featurePlan,
            CONTRACT_STRUCTURE to structurePlan,
            CONTRACT_ARCHITECTURE to PromptTemplateRef("contract/architecture"),
            CONTRACT_DRAW to PromptTemplateRef("contract/draw"),
        )

    companion object {
        const val CONTRACT_GRAND_WORLD: String = "grand_world"
        const val CONTRACT_TERRAIN: String = "terrain"
        const val CONTRACT_BIOME: String = "biome"
        const val CONTRACT_FEATURE: String = "feature"
        const val CONTRACT_STRUCTURE: String = "structure"
        const val CONTRACT_ARCHITECTURE: String = "architecture"
        const val CONTRACT_DRAW: String = "draw"

        val DEFAULT = PromptSet(
            worldBible = PromptTemplateRef("world_bible"),
            structureCatalog = PromptTemplateRef("structure_catalog"),
            structureDetail = PromptTemplateRef("structure_detail"),
            consistencyReview = PromptTemplateRef("consistency_review"),
            worldEntry = PromptTemplateRef("world_entry"),
            terrainPlan = PromptTemplateRef("contract/terrain"),
            biomePlan = PromptTemplateRef("contract/biome"),
            featurePlan = PromptTemplateRef("contract/feature"),
            structurePlan = PromptTemplateRef("contract/structure"),
        )
    }
}
