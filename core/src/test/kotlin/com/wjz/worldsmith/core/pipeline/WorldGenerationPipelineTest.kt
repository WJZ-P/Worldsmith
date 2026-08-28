package com.wjz.worldsmith.core.pipeline

import com.wjz.worldsmith.core.model.ArchitectureBible
import com.wjz.worldsmith.core.model.AtmosphereBible
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.RoomDefinition
import com.wjz.worldsmith.core.model.StructureBrief
import com.wjz.worldsmith.core.model.StructureCategory
import com.wjz.worldsmith.core.model.StructureDefinition
import com.wjz.worldsmith.core.model.StructureFootprint
import com.wjz.worldsmith.core.model.SurfacePalette
import com.wjz.worldsmith.core.model.TerrainBible
import com.wjz.worldsmith.core.model.TerrainProfile
import com.wjz.worldsmith.core.model.WorldBible
import com.wjz.worldsmith.core.model.WorldBlueprint
import com.wjz.worldsmith.core.model.WorldGenerationRequest
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldGenerationPipelineTest {
    @Test
    fun `pipeline preserves catalog order while structure agents run concurrently`() = runBlocking {
        val request = WorldGenerationRequest(
            playerPrompt = "我想要一个废土风世界",
            seed = 42L,
            requestedStructureCount = 6,
        )
        val stages = mutableListOf<GenerationStage>()
        val pipeline = WorldGenerationPipeline(
            worldBibleAgent = FakeWorldBibleAgent,
            structureCatalogAgent = FakeStructureCatalogAgent,
            structureDetailAgent = FakeStructureDetailAgent,
            structureParallelism = 3,
        )

        val result = pipeline.generate(request) { stages += it.stage }
        val success = assertInstanceOf(GenerationResult.Success::class.java, result)

        assertEquals((0 until 6).map { "structure-$it" }, success.blueprint.structures.map { it.id })
        assertEquals(GenerationStage.INTENT_EXPANSION, stages.first())
        assertEquals(GenerationStage.COMPLETE, stages.last())
    }

    @Test
    fun `assembled blueprint has a stable JSON round trip`() = runBlocking {
        val pipeline = WorldGenerationPipeline(FakeWorldBibleAgent, FakeStructureCatalogAgent, FakeStructureDetailAgent)
        val result = pipeline.generate(WorldGenerationRequest("废土", 7L, requestedStructureCount = 2))
        val blueprint = assertInstanceOf(GenerationResult.Success::class.java, result).blueprint

        val encoded = WorldsmithJson.encode(blueprint)
        val decoded = WorldsmithJson.decode<WorldBlueprint>(encoded)

        assertEquals(blueprint, decoded)
        assertTrue(encoded.contains("\"playerPrompt\": \"废土\""))
    }

    @Test
    fun `blank prompt is rejected before any agent runs`() = runBlocking {
        val pipeline = WorldGenerationPipeline(FakeWorldBibleAgent, FakeStructureCatalogAgent, FakeStructureDetailAgent)
        val result = pipeline.generate(WorldGenerationRequest("  ", 1L))
        val rejected = assertInstanceOf(GenerationResult.Rejected::class.java, result)

        assertEquals(listOf("EMPTY_PROMPT"), rejected.diagnostics.map { it.code })
    }

    private object FakeWorldBibleAgent : WorldBibleAgent {
        override suspend fun expand(input: WorldBibleAgentInput) = WorldBible(
            id = "ashlands",
            title = "Ashlands",
            summary = "A wind-scoured industrial wasteland.",
            themeTags = listOf("wasteland", "industrial", "decayed"),
            biomeThemes = listOf("ash_desert", "toxic_basin"),
            terrain = TerrainBible(TerrainProfile.WASTELAND, "Broken plateaus", "eroded", "collapsed", 24),
            surfacePalette = SurfacePalette(
                surface = listOf(MaterialSelector("dust", listOf("minecraft:gravel"))),
                subsurface = listOf(MaterialSelector("dead_stone", listOf("minecraft:tuff"))),
            ),
            architecture = ArchitectureBible(
                styleTags = listOf("brutalist", "salvaged"),
                primaryMaterials = listOf(MaterialSelector("rusted_metal")),
                shapeLanguage = listOf("low silhouettes", "exposed supports"),
                decayLevel = 0.8,
            ),
            atmosphere = AtmosphereBible("pale", "dusty", "dry storms", "desolate"),
            globalRules = listOf("Water is rare", "Most buildings are partially ruined"),
        )
    }

    private object FakeStructureCatalogAgent : StructureCatalogAgent {
        override suspend fun plan(input: StructureCatalogAgentInput): List<StructureBrief> =
            (0 until input.request.requestedStructureCount).map { index ->
                StructureBrief(
                    id = "structure-$index",
                    name = "Structure $index",
                    category = StructureCategory.RUIN,
                    worldRole = "Environmental storytelling",
                    descriptionPrompt = "A ruined wasteland structure numbered $index",
                    styleTags = input.bible.architecture.styleTags,
                    allowedBiomeThemes = input.bible.biomeThemes,
                    rarityWeight = 1.0,
                    footprint = StructureFootprint(16, 16, 4, 12),
                )
            }
    }

    private object FakeStructureDetailAgent : StructureDetailAgent {
        override suspend fun generate(input: StructureDetailAgentInput): StructureDefinition {
            val index = input.brief.id.substringAfterLast('-').toInt()
            delay((20L - index).coerceAtLeast(1L))
            return StructureDefinition(
                id = input.brief.id,
                briefId = input.brief.id,
                summary = input.brief.descriptionPrompt,
                palette = input.bible.architecture.primaryMaterials,
                rooms = listOf(RoomDefinition("main", "shelter", "A weathered central room")),
                exteriorFeatures = listOf("collapsed antenna"),
                generationRules = listOf("align to terrain"),
            )
        }
    }
}
