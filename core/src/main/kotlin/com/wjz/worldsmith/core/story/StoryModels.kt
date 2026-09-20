package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class StoryLibrary @JvmOverloads constructor(
    val schemaVersion: Int = 2, val facts: List<StoryFact> = emptyList(), val places: List<StoryPlace> = emptyList(),
    val characters: List<StoryCharacter> = emptyList(), val dialogues: List<StoryDialogue> = emptyList(),
    val knowledge: List<StoryKnowledge> = emptyList(), val trades: List<StoryTrade> = emptyList(),
    val soundscapes: List<StorySoundscape> = emptyList(), val projections: List<StoryProjection> = emptyList(),
)
@Serializable enum class StoryFactScope { WORLD, PLAYER, CHARACTER, PLACE }
@Serializable enum class StoryFactType { BOOL, NUMBER, TEXT }
@Serializable enum class StoryComparison { EQ, NE, LT, LE, GT, GE }
@Serializable enum class StoryChangeMode { SET, ADD }
@Serializable enum class StoryTruth { FACT, LEGEND, BELIEF }
@Serializable data class StoryFact @JvmOverloads constructor(val id: String, val scope: StoryFactScope,
    val type: StoryFactType, @Serializable(with=StoryPrimitiveSerializer::class) val initial: AbilityValue, val min: Double = -1e6, val max: Double = 1e6)
@Serializable data class StoryFactRef @JvmOverloads constructor(val id: String, val subject: String? = null)
@Serializable(with=StoryConditionSerializer::class) sealed interface StoryCondition {
    @Serializable @SerialName("always") data object Always : StoryCondition
    @Serializable @SerialName("all") data class All(val conditions: List<StoryCondition>) : StoryCondition
    @Serializable @SerialName("any") data class Any(val conditions: List<StoryCondition>) : StoryCondition
    @Serializable @SerialName("not") data class Not(val condition: StoryCondition) : StoryCondition
    @Serializable @SerialName("compare") data class Compare(val fact: StoryFactRef, val comparison: StoryComparison, @Serializable(with=StoryPrimitiveSerializer::class) val value: AbilityValue) : StoryCondition
}
@Serializable data class StoryFactChange @JvmOverloads constructor(val fact: StoryFactRef, @Serializable(with=StoryPrimitiveSerializer::class) val value: AbilityValue, val mode: StoryChangeMode = StoryChangeMode.SET)
@Serializable data class StoryOffset @JvmOverloads constructor(val x: Int = 0, val y: Int = 0, val z: Int = 0)
/** A full resolved block state, not a partial matching predicate. Omitted properties use native defaults. */
@Serializable data class StoryBlockState @JvmOverloads constructor(val block: String, val properties: Map<String, String> = emptyMap())
@Serializable data class StoryBlockChange(val offset: StoryOffset, val expected: StoryBlockState, val desired: StoryBlockState)
/** Once per actual place instance. Rewards and script continuations deliberately do not belong here. */
@Serializable data class StoryProjection @JvmOverloads constructor(val id: String, val place: String,
    val condition: StoryCondition, val blocks: List<StoryBlockChange>, val onApplied: List<StoryFactChange> = emptyList())
@Serializable data class StoryPlace @JvmOverloads constructor(val id: String, val name: String, val description: String,
    val structure: String, val discoveryRadius: Int = 24, val discoverWhen: StoryCondition = StoryCondition.Always,
    val onDiscover: List<StoryFactChange> = emptyList(), val clue: String = "", val soundscape: String? = null, val required: Boolean = false)
@Serializable data class StoryCharacter @JvmOverloads constructor(val id: String, val name: String, val creature: String,
    val place: String, val dialogue: String? = null, val spawnWhen: StoryCondition = StoryCondition.Always,
    val onDeath: List<StoryFactChange> = emptyList(), val respawnTicks: Int? = null, val routines: List<StoryRoutine> = emptyList())
@Serializable data class StoryRoutine @JvmOverloads constructor(val id: String, val startTick: Int, val endTick: Int,
    val offset: StoryOffset, val activity: String, val condition: StoryCondition = StoryCondition.Always, val speed: Double = 0.6)
@Serializable data class StoryDialogue(val id: String, val start: String, val nodes: List<StoryDialogueNode>)
@Serializable data class StoryDialogueNode @JvmOverloads constructor(val id: String, val text: String,
    val options: List<StoryDialogueOption>, val condition: StoryCondition = StoryCondition.Always)
@Serializable data class StoryDialogueOption @JvmOverloads constructor(val id: String, val text: String,
    val next: String? = null, val condition: StoryCondition = StoryCondition.Always, val changes: List<StoryFactChange> = emptyList(),
    val trade: String? = null, val program: String? = null)
@Serializable data class StoryKnowledge @JvmOverloads constructor(val id: String, val title: String, val text: String,
    val truth: StoryTruth = StoryTruth.FACT, val discoverWhen: StoryCondition)
@Serializable data class StoryItemAmount @JvmOverloads constructor(val item: String, val count: Int = 1)
@Serializable data class StoryTrade @JvmOverloads constructor(val id: String, val name: String, val inputs: List<StoryItemAmount>,
    val outputs: List<StoryItemAmount>, val condition: StoryCondition = StoryCondition.Always, val changes: List<StoryFactChange> = emptyList(),
    val cooldownTicks: Int = 20, val maxUsesPerPlayer: Int = 0)
@Serializable data class StorySoundscape(val id: String, val layers: List<StorySoundLayer>)
@Serializable data class StorySoundLayer @JvmOverloads constructor(val id: String, val sound: String,
    val condition: StoryCondition = StoryCondition.Always, val volume: Float = 0.4f, val pitch: Float = 1f,
    val periodTicks: Int = 200, val fadeTicks: Int = 40, val priority: Int = 0, val music: Boolean = false, val subtitle: String = "")
