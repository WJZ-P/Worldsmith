package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

// Descriptive planning context only. Executable portable construction lives in
// core.structure.StructureBlueprint and is governed by contract/structure.

@Serializable
enum class StructureCategory {
    SHELTER,
    SETTLEMENT,
    INDUSTRIAL,
    RUIN,
    LANDMARK,
    INFRASTRUCTURE,
    DUNGEON,
    NATURAL,
}

@Serializable
data class StructureFootprint(
    val width: Int,
    val depth: Int,
    val minHeight: Int,
    val maxHeight: Int,
)

@Serializable
data class StructureBrief(
    val id: String,
    val name: String,
    val category: StructureCategory,
    val worldRole: String,
    val descriptionPrompt: String,
    val styleTags: List<String>,
    val allowedBiomeThemes: List<String>,
    val rarityWeight: Double,
    val footprint: StructureFootprint,
)

@Serializable
data class RoomDefinition(
    val id: String,
    val purpose: String,
    val description: String,
)

@Serializable
data class StructureDefinition(
    val id: String,
    val briefId: String,
    val summary: String,
    val palette: List<MaterialSelector>,
    val rooms: List<RoomDefinition>,
    val exteriorFeatures: List<String>,
    val generationRules: List<String>,
    val lootThemes: List<String> = emptyList(),
)
