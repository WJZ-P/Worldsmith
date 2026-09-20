package com.wjz.worldsmith.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A bounded, event-driven collection of anchor-scoped state machines, never an interpreter. */
@Serializable
data class WorldMechanicLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 1,
    val mechanics: List<WorldMechanicDefinition> = emptyList(),
)

@Serializable
data class WorldMechanicDefinition @JvmOverloads constructor(
    val id: String,
    val displayName: String,
    val initialState: String = "idle",
    val states: List<String> = listOf("idle", "active"),
    val rules: List<WorldMechanicRule>,
    val description: String = "",
)

@Serializable
enum class WorldMechanicEvent { BLOCK_PLACED, USE_BLOCK }

/** Relative cell coordinates; matching helpers also use this immutable triple for world positions. */
@Serializable
data class MechanicOffset @JvmOverloads constructor(val x: Int = 0, val y: Int = 0, val z: Int = 0)

/** Exact logical/native block id and a partial set of native block-state properties. */
@Serializable
data class MechanicBlockPredicate @JvmOverloads constructor(
    val block: String,
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
data class MechanicPatternCell @JvmOverloads constructor(
    val offset: MechanicOffset,
    val block: MechanicBlockPredicate,
    val consume: Boolean = false,
)

@Serializable
data class MechanicItemCost @JvmOverloads constructor(val item: String, val count: Int = 1)

@Serializable
data class WorldMechanicRule @JvmOverloads constructor(
    val id: String,
    val event: WorldMechanicEvent,
    val pattern: List<MechanicPatternCell>,
    val actions: List<MechanicAction>,
    val fromState: String = "idle",
    val toState: String = "active",
    val rotateY: Boolean = true,
    val heldItem: MechanicItemCost? = null,
    val cooldownTicks: Int = 20,
    val biomes: List<String> = emptyList(),
)

@Serializable
sealed interface MechanicAction {
    @Serializable @SerialName("set_block")
    data class SetBlock(val offset: MechanicOffset, val block: MechanicBlockPredicate) : MechanicAction

    @Serializable @SerialName("spawn_creature")
    data class SpawnCreature(val creature: String, val offset: MechanicOffset) : MechanicAction

    @Serializable @SerialName("give_item")
    data class GiveItem @JvmOverloads constructor(val item: String, val count: Int = 1) : MechanicAction

    /** Reserves a shared ability invocation and launches it after this activation commits. */
    @Serializable @SerialName("run_program")
    data class RunProgram(val program: String) : MechanicAction
}
