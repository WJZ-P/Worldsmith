package com.wjz.worldsmith.core.pipeline

import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.model.WorldBlueprint
import com.wjz.worldsmith.core.model.WorldGenerationRequest
import com.wjz.worldsmith.core.validation.WorldBlueprintValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class WorldGenerationPipeline(
    private val worldBibleAgent: WorldBibleAgent,
    private val structureCatalogAgent: StructureCatalogAgent,
    private val structureDetailAgent: StructureDetailAgent,
    private val consistencyReviewAgent: ConsistencyReviewAgent = PassthroughConsistencyReviewAgent,
    private val structureParallelism: Int = 4,
) {
    init {
        require(structureParallelism > 0) { "structureParallelism must be positive" }
    }

    suspend fun generate(
        request: WorldGenerationRequest,
        promptSet: PromptSet = PromptSet.DEFAULT,
        onProgress: suspend (GenerationProgress) -> Unit = {},
    ): GenerationResult {
        WorldBlueprintValidator.validateRequest(request).takeIf { it.isNotEmpty() }?.let {
            return GenerationResult.Rejected(it)
        }

        onProgress(GenerationProgress(GenerationStage.INTENT_EXPANSION, detail = request.playerPrompt))
        val bible = worldBibleAgent.expand(WorldBibleAgentInput(request, promptSet))

        onProgress(GenerationProgress(GenerationStage.STRUCTURE_CATALOG_PLANNING))
        val briefs = structureCatalogAgent.plan(StructureCatalogAgentInput(request, promptSet, bible))
        WorldBlueprintValidator.validateBriefs(request, briefs).takeIf { it.isNotEmpty() }?.let {
            return GenerationResult.Rejected(it)
        }

        onProgress(GenerationProgress(GenerationStage.STRUCTURE_DETAIL_GENERATION, completed = 0, total = briefs.size))
        val semaphore = Semaphore(structureParallelism)
        val structures = coroutineScope {
            briefs.map { brief ->
                async {
                    semaphore.withPermit {
                        structureDetailAgent.generate(StructureDetailAgentInput(request, promptSet, bible, brief))
                    }
                }
            }.awaitAll()
        }
        onProgress(GenerationProgress(GenerationStage.STRUCTURE_DETAIL_GENERATION, completed = structures.size, total = briefs.size))

        val draft = WorldBlueprint(
            request = request,
            promptSet = promptSet,
            bible = bible,
            structureBriefs = briefs,
            structures = structures,
        )

        onProgress(GenerationProgress(GenerationStage.CONSISTENCY_REVIEW))
        val reviewed = consistencyReviewAgent.review(ConsistencyReviewAgentInput(promptSet, draft))

        onProgress(GenerationProgress(GenerationStage.ASSEMBLY))
        WorldBlueprintValidator.validateBlueprint(reviewed).takeIf { it.isNotEmpty() }?.let {
            return GenerationResult.Rejected(it)
        }

        onProgress(GenerationProgress(GenerationStage.COMPLETE))
        return GenerationResult.Success(reviewed)
    }
}
