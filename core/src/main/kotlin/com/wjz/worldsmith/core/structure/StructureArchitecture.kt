package com.wjz.worldsmith.core.structure

import kotlinx.serialization.Serializable

/** World-specific authoring intent, never a shared stock-building catalog or a Draw SDK concern. */
@Serializable
data class StructureArchitecture(
    val policyVersion: Int = 1,
    val worldTheme: String,
    val groups: List<StructureGroupDesign>,
    val standalone: List<StandaloneStructureDesign>,
)

@Serializable enum class StructureGroupRole { LANDMARK, REGULAR }

@Serializable
data class StructureGroupDesign(
    val structure: String,
    val title: String,
    val role: StructureGroupRole = StructureGroupRole.REGULAR,
    /** The definition's root blueprint; it may represent a street, courtyard or main building. */
    val centerpiece: String,
    val themeFit: String,
    val layoutIntent: String,
    val distinction: String,
    val discovery: String,
    val required: List<StructureMemberRule>,
    val optional: List<StructureMemberRule> = emptyList(),
)

@Serializable
data class StructureMemberRule(
    val role: String,
    val blueprints: List<String>,
    val minCount: Int = 1,
    val maxCount: Int = 1,
)

@Serializable
data class StandaloneStructureDesign(val structure: String, val purpose: String, val themeFit: String)

@Serializable enum class StructureLightingMode { READABLE, EXTERIOR_ONLY }
@Serializable data class StructureLightSource(val at: BuildPos, val level: Int)

/** Author-declared light sources are verified against native block states at MC export. */
@Serializable
data class StructureLighting(
    val mode: StructureLightingMode,
    val spaces: List<BuildBox> = emptyList(),
    val sources: List<StructureLightSource> = emptyList(),
    val minimum: Int = 8,
)

data class StructureLightingReport(val sampledFeet: Int, val minimumEstimatedLevel: Int?)
