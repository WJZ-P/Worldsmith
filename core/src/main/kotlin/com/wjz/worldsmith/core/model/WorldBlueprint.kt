package com.wjz.worldsmith.core.model

import com.wjz.worldsmith.core.WorldsmithCore
import kotlinx.serialization.Serializable

@Serializable
data class WorldBlueprint(
    val schemaVersion: Int = WorldsmithCore.BLUEPRINT_SCHEMA_VERSION,
    val request: WorldGenerationRequest,
    val promptSet: PromptSet,
    val bible: WorldBible,
    val structureBriefs: List<StructureBrief>,
    val structures: List<StructureDefinition>,
    val reviewNotes: List<String> = emptyList(),
)
