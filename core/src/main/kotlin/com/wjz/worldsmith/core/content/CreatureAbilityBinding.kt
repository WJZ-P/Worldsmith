package com.wjz.worldsmith.core.content

import kotlinx.serialization.Serializable

/** A host invocation policy, not a skill catalogue. The referenced source owns all combat logic. */
@Serializable
data class CreatureAbilityBinding @JvmOverloads constructor(
    val program: String,
    val range: Double = 8.0,
    val cooldownTicks: Int = 20,
    val cancelOnTargetLoss: Boolean = true,
)
