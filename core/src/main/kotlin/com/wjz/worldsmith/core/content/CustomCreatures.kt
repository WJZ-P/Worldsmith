package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable

/** Ground creatures are data, never generated executable tick code. Model units are 1/16 block. */
@Serializable
data class CreatureLibrary(val schemaVersion: Int = 1, val creatures: List<CreatureDefinition> = emptyList())

@Serializable
data class CreatureDefinition(
    val id: String,
    val displayName: String,
    val category: CreatureCategory,
    val model: CreatureModel,
    val attributes: CreatureAttributes = CreatureAttributes(),
    val behavior: CreatureBehavior = CreatureBehavior(),
    val spawn: CreatureSpawn = CreatureSpawn(),
    val themeRole: String = "",
)

@Serializable enum class CreatureCategory { PASSIVE, HOSTILE }
@Serializable enum class CreaturePassiveMode { WANDER, FLEE_PLAYERS }
@Serializable enum class CreatureBoneRole { NONE, HEAD, LEG_LEFT, LEG_RIGHT, ARM_LEFT, ARM_RIGHT, TAIL }
@Serializable data class CreatureVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)
@Serializable data class CreatureUv(val u: Int = 0, val v: Int = 0)

/** Parent-relative pivots; X right, Y down, Z toward the back. Root Y=24 is ground level. */
@Serializable
data class CreatureBone(
    val id: String,
    val parent: String? = null,
    val pivot: CreatureVector = CreatureVector(),
    val rotation: CreatureVector = CreatureVector(),
    val role: CreatureBoneRole = CreatureBoneRole.NONE,
    val cubes: List<CreatureCube> = emptyList(),
    /** Degrees added to gait phase; use 180 on hind legs for a diagonal quadruped gait. */
    val gaitPhase: Float = 0f,
)

/** Vanilla box-UV layout, integer dimensions: footprint [2*(x+z), y+z]. Origin is bone-local. */
@Serializable
data class CreatureCube(val origin: CreatureVector, val size: CreatureVector, val uv: CreatureUv = CreatureUv(), val mirror: Boolean = false)

@Serializable
data class CreatureModel(val texture: String, val textureWidth: Int = 64, val textureHeight: Int = 64, val bones: List<CreatureBone>)

@Serializable
data class CreatureAttributes(
    val health: Double = 20.0,
    val speed: Double = 0.25,
    val followRange: Double = 24.0,
    val attackDamage: Double = 3.0,
    val knockbackResistance: Double = 0.0,
    val width: Float = 0.8f,
    val height: Float = 1.4f,
)

@Serializable
data class CreatureBehavior(
    val passiveMode: CreaturePassiveMode = CreaturePassiveMode.WANDER,
    val territoryRadius: Int = 32,
    val attackReach: Double = 2.0,
    val windupTicks: Int = 12,
    val recoveryTicks: Int = 20,
)

@Serializable
data class CreatureSpawn(
    val biomes: List<String> = emptyList(),
    val weight: Int = 10,
    val minGroup: Int = 1,
    val maxGroup: Int = 3,
    val minLight: Int = 0,
    val maxLight: Int = 15,
)

/** Limits are part of schema 1, not suggestions; reject unsupported assets before activation. */
object CustomCreatureValidator {
    const val MAX_CREATURES = 128
    const val MAX_BONES = 64
    const val MAX_CUBES = 256
    private val idPattern = Regex("[a-z0-9][a-z0-9_./-]{0,95}")
    private val shaPattern = Regex("[0-9a-f]{64}")

    /** Kotlin read-only List alone is not immutable to Java callers; freeze every nested collection. */
    @JvmStatic fun freeze(library: CreatureLibrary): CreatureLibrary = library.copy(creatures = java.util.List.copyOf(library.creatures.map { c ->
        c.copy(model = c.model.copy(bones = java.util.List.copyOf(c.model.bones.map { b -> b.copy(cubes = java.util.List.copyOf(b.cubes)) })),
            spawn = c.spawn.copy(biomes = java.util.List.copyOf(c.spawn.biomes)))
    }))

    @JvmStatic fun validate(library: CreatureLibrary): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        fun error(path: String, message: String) { result += Diagnostic(path, "creature.invalid", DiagnosticSeverity.ERROR, message) }
        if (library.schemaVersion != 1) error("creatures.schemaVersion", "Supported creature schemaVersion is 1")
        if (library.creatures.size > MAX_CREATURES) error("creatures.creatures", "At most $MAX_CREATURES creature definitions are supported")
        val ids = mutableSetOf<String>()
        library.creatures.forEachIndexed { i, c ->
            val p = "creatures.creatures[$i]"
            if (!idPattern.matches(c.id) || c.id.split('/').any { it == "." || it == ".." }) error("$p.id", "Expected a normalized logical content id")
            if (!ids.add(c.id)) error("$p.id", "Duplicate creature id '${c.id}'")
            if (c.displayName.isBlank() || c.displayName.length > 128) error("$p.displayName", "Display name must contain 1 to 128 characters")
            if (c.themeRole.length > 2048) error("$p.themeRole", "Theme role is limited to 2048 characters")
            if (!shaPattern.matches(c.model.texture)) error("$p.model.texture", "Texture must reference a lowercase SHA-256 PNG asset id")
            if (c.model.textureWidth !in 16..512 || c.model.textureWidth.countOneBits() != 1 ||
                c.model.textureHeight !in 16..512 || c.model.textureHeight.countOneBits() != 1) error("$p.model", "Texture dimensions must be powers of two between 16 and 512")
            val bones = c.model.bones
            if (bones.isEmpty() || bones.size > MAX_BONES) error("$p.model.bones", "Model needs 1 to $MAX_BONES bones")
            if (bones.sumOf { it.cubes.size } !in 1..MAX_CUBES) error("$p.model.bones", "Model needs 1 to $MAX_CUBES cubes total")
            val byId = bones.associateBy { it.id }
            if (byId.size != bones.size) error("$p.model.bones", "Bone ids must be unique")
            bones.forEachIndexed { j, b ->
                val q = "$p.model.bones[$j]"
                if (!Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(b.id)) error("$q.id", "Bone ids use letters, digits and underscores, starting with a letter")
                fun vector(v: CreatureVector, path: String, bound: Float) {
                    if (listOf(v.x, v.y, v.z).any { !it.isFinite() || it !in -bound..bound }) error(path, "Coordinates must be finite and within +/-$bound")
                }
                vector(b.pivot, "$q.pivot", 128f)
                vector(b.rotation, "$q.rotation", 360f)
                if (!b.gaitPhase.isFinite() || b.gaitPhase !in -360f..360f) error("$q.gaitPhase", "Gait phase must be finite and within +/-360 degrees")
                if (b.parent != null && b.parent !in byId) error("$q.parent", "Unknown parent bone '${b.parent}'")
                val visited = mutableSetOf<String>()
                var current: CreatureBone? = b
                var pivotBudget = 0.0
                while (current != null) {
                    if (!visited.add(current.id)) { error("$q.parent", "Bone hierarchy contains a cycle"); break }
                    if (visited.size > 16) { error("$q.parent", "Bone hierarchy depth exceeds 16"); break }
                    pivotBudget += kotlin.math.sqrt((current.pivot.x.toDouble() * current.pivot.x) + (current.pivot.y.toDouble() * current.pivot.y) + (current.pivot.z.toDouble() * current.pivot.z))
                    current = current.parent?.let(byId::get)
                }
                b.cubes.forEachIndexed { k, cube ->
                    val r = "$q.cubes[$k]"
                    vector(cube.origin, "$r.origin", 128f)
                    val size = listOf(cube.size.x, cube.size.y, cube.size.z)
                    val extentBudget = listOf(cube.origin.x to cube.size.x, cube.origin.y to cube.size.y, cube.origin.z to cube.size.z)
                        .sumOf { (origin, size) -> val extent = kotlin.math.abs(origin.toDouble()) + size; extent * extent }
                    if (pivotBudget + kotlin.math.sqrt(extentBudget) > 256) error("$r", "Accumulated bone/cube extent must fit within 256 model units of the model origin")
                    if (size.any { !it.isFinite() || it !in 1f..64f || it != it.toInt().toFloat() }) error("$r.size", "Cube dimensions must be integers from 1 to 64 model units")
                    if (cube.uv.u < 0 || cube.uv.v < 0 || cube.uv.u.toLong() + 2 * (cube.size.x.toDouble() + cube.size.z) > c.model.textureWidth ||
                        cube.uv.v.toLong() + cube.size.y.toDouble() + cube.size.z > c.model.textureHeight) error("$r.uv", "Unfolded box UV must fit inside the declared texture dimensions")
                }
            }
            fun range(value: Double, min: Double, max: Double, field: String) {
                if (!value.isFinite() || value !in min..max) error("$p.$field", "Value must be finite and between $min and $max")
            }
            with(c.attributes) {
                range(health, 1.0, 2048.0, "attributes.health"); range(speed, 0.01, 1.0, "attributes.speed")
                range(followRange, 4.0, 64.0, "attributes.followRange"); range(attackDamage, 0.0, 100.0, "attributes.attackDamage")
                range(knockbackResistance, 0.0, 1.0, "attributes.knockbackResistance")
                range(width.toDouble(), 0.2, 4.0, "attributes.width"); range(height.toDouble(), 0.2, 6.0, "attributes.height")
            }
            with(c.behavior) {
                if (territoryRadius !in 8..128) error("$p.behavior.territoryRadius", "Territory radius must be 8 to 128 blocks")
                range(attackReach, 0.5, 5.0, "behavior.attackReach")
                if (windupTicks !in 1..100) error("$p.behavior.windupTicks", "Windup must be 1 to 100 ticks")
                if (recoveryTicks !in 4..200) error("$p.behavior.recoveryTicks", "Recovery must be 4 to 200 ticks")
            }
            with(c.spawn) {
                if (biomes.size > 128 || biomes.distinct().size != biomes.size || biomes.any { !idPattern.matches(it) || it.split('/').any { s -> s == "." || s == ".." } }) error("$p.spawn.biomes", "Use up to 128 unique logical biome ids; an empty list disables natural spawning")
                if (weight !in 1..1000) error("$p.spawn.weight", "Weight must be 1 to 1000")
                if (minGroup !in 1..8 || maxGroup !in minGroup..8) error("$p.spawn", "Group sizes must satisfy 1 <= minGroup <= maxGroup <= 8")
                if (minLight !in 0..15 || maxLight !in minLight..15) error("$p.spawn", "Light limits must satisfy 0 <= minLight <= maxLight <= 15")
            }
        }
        return result
    }
}

enum class CreatureCombatState { IDLE, CHASE, WINDUP, STRIKE, RECOVERY }
data class CreatureCombatFrame(val state: CreatureCombatState = CreatureCombatState.IDLE, val ticks: Int = 0)
data class CreatureCombatDecision(val frame: CreatureCombatFrame, val strike: Boolean = false)

/** Deterministic bounded server state machine; re-check reach and sight at the actual damage frame. */
object CreatureCombat {
    @JvmStatic fun advance(frame: CreatureCombatFrame, targetValid: Boolean, inReach: Boolean, hasSight: Boolean, behavior: CreatureBehavior): CreatureCombatDecision {
        if (!targetValid) return CreatureCombatDecision(CreatureCombatFrame())
        fun next(state: CreatureCombatState, ticks: Int = 0, strike: Boolean = false) = CreatureCombatDecision(CreatureCombatFrame(state, ticks), strike)
        return when (frame.state) {
            CreatureCombatState.IDLE, CreatureCombatState.CHASE -> if (inReach && hasSight) next(CreatureCombatState.WINDUP, behavior.windupTicks) else next(CreatureCombatState.CHASE)
            CreatureCombatState.WINDUP -> if (frame.ticks > 1) next(CreatureCombatState.WINDUP, frame.ticks - 1) else next(CreatureCombatState.STRIKE, 3, strike = inReach && hasSight)
            CreatureCombatState.STRIKE -> if (frame.ticks > 1) next(CreatureCombatState.STRIKE, frame.ticks - 1) else next(CreatureCombatState.RECOVERY, behavior.recoveryTicks)
            CreatureCombatState.RECOVERY -> if (frame.ticks > 1) next(CreatureCombatState.RECOVERY, frame.ticks - 1) else next(CreatureCombatState.CHASE)
        }
    }
}
