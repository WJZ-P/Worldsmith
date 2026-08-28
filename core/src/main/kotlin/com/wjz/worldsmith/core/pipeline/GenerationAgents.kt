package com.wjz.worldsmith.core.pipeline

import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.StructureBrief
import com.wjz.worldsmith.core.model.StructureDefinition
import com.wjz.worldsmith.core.model.WorldBible
import com.wjz.worldsmith.core.model.WorldBlueprint
import com.wjz.worldsmith.core.model.WorldGenerationRequest

data class WorldBibleAgentInput(
    val request: WorldGenerationRequest,
    val promptSet: PromptSet,
)

data class StructureCatalogAgentInput(
    val request: WorldGenerationRequest,
    val promptSet: PromptSet,
    val bible: WorldBible,
)

data class StructureDetailAgentInput(
    val request: WorldGenerationRequest,
    val promptSet: PromptSet,
    val bible: WorldBible,
    val brief: StructureBrief,
)

data class ConsistencyReviewAgentInput(
    val promptSet: PromptSet,
    val blueprint: WorldBlueprint,
)

interface WorldBibleAgent {
    suspend fun expand(input: WorldBibleAgentInput): WorldBible
}

interface StructureCatalogAgent {
    suspend fun plan(input: StructureCatalogAgentInput): List<StructureBrief>
}

interface StructureDetailAgent {
    suspend fun generate(input: StructureDetailAgentInput): StructureDefinition
}

interface ConsistencyReviewAgent {
    suspend fun review(input: ConsistencyReviewAgentInput): WorldBlueprint
}

object PassthroughConsistencyReviewAgent : ConsistencyReviewAgent {
    override suspend fun review(input: ConsistencyReviewAgentInput): WorldBlueprint = input.blueprint
}
