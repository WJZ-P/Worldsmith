package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WorldGenerationRequest(
    val playerPrompt: String,
    val seed: Long? = null,
    /** Null lets the prompt decide; zero explicitly asks for no structures. */
    val requestedStructureCount: Int? = null,
    val locale: String = "zh-CN",
)
