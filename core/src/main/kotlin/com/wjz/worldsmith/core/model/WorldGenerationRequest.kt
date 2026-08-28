package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WorldGenerationRequest(
    val playerPrompt: String,
    val seed: Long,
    val requestedStructureCount: Int = 20,
    val locale: String = "zh-CN",
)
