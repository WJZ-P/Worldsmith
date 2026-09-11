package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable

@Serializable enum class CreatureBossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

/** Ground-melee boss mechanics, not an arbitrary action/script interpreter. Enabled by creature schema 2. */
@Serializable
data class CreatureBossProfile @JvmOverloads constructor(
    val phases: List<CreatureBossPhase>,
    val barTitle: String = "",
    val barColor: CreatureBossBarColor = CreatureBossBarColor.PURPLE,
    /** Independent acceptance roll after species selection, so a boss-only biome is still genuinely rare. */
    val naturalSpawnChance: Double = 0.01,
    /** Only suppresses a nearby live, loaded same-species boss; this is deliberately not world-unique persistence. */
    val naturalSpacingBlocks: Int = 128,
)

@Serializable
data class CreatureBossPhase @JvmOverloads constructor(
    val name: String,
    /** Inclusive remaining-health ratio at which this phase becomes eligible. The first phase starts at 1.0. */
    val healthThreshold: Double,
    val speedMultiplier: Double = 1.0,
    val damageMultiplier: Double = 1.0,
    val windupTicks: Int = 12,
    val recoveryTicks: Int = 20,
    val poseIntensity: Float = 1.0f,
)

object CreatureBosses {
    @JvmStatic fun validate(definition: CreatureDefinition, path: String): List<Diagnostic> = buildList {
        val boss = definition.boss ?: return@buildList
        fun error(field: String, message: String) { add(Diagnostic("$path.boss$field", "creature.boss_invalid", DiagnosticSeverity.ERROR, message)) }
        if (definition.category != CreatureCategory.HOSTILE) error("", "Bosses require the hostile native host")
        if (definition.attributes.health > 1024.0) error("", "Boss health must fit the native max_health attribute limit of 1024")
        if (definition.attributes.attackDamage <= 0.0) error("", "A melee boss needs positive base attack damage")
        if (boss.barTitle.length > 128 || boss.barTitle.any(Char::isISOControl)) error(".barTitle", "Boss bar title is optional printable text, at most 128 characters")
        if (boss.barTitle.isBlank() && definition.displayName.any(Char::isISOControl)) error(".barTitle", "The fallback creature name must be printable boss-bar text")
        if (boss.phases.size !in 2..3) error(".phases", "A boss requires two or three health-threshold combat phases")
        if (!boss.naturalSpawnChance.isFinite() || boss.naturalSpawnChance !in 0.001..0.05)
            error(".naturalSpawnChance", "Boss natural acceptance probability must be between 0.001 and 0.05")
        if (boss.naturalSpacingBlocks !in 32..256) error(".naturalSpacingBlocks", "Loaded live-boss spacing must be 32..256 blocks")
        if (definition.spawn.biomes.isNotEmpty() && (definition.spawn.weight !in 1..3 || definition.spawn.minGroup != 1 || definition.spawn.maxGroup != 1))
            error("", "Naturally distributed bosses require weight 1..3 and group size exactly one")
        val names = mutableSetOf<String>()
        boss.phases.forEachIndexed { index, phase ->
            val p = ".phases[$index]"
            if (phase.name.isBlank() || phase.name.length > 48 || phase.name.any(Char::isISOControl) || !names.add(phase.name))
                error("$p.name", "Each phase needs distinct printable display text of 1..48 characters")
            if (!phase.healthThreshold.isFinite() || phase.healthThreshold <= 0 || phase.healthThreshold > 1
                || index == 0 && phase.healthThreshold != 1.0
                || index > 0 && phase.healthThreshold >= boss.phases[index - 1].healthThreshold)
                error("$p.healthThreshold", "The first threshold is 1.0; later thresholds strictly decrease and stay above zero")
            if (!phase.speedMultiplier.isFinite() || phase.speedMultiplier !in 0.25..2.5
                || definition.attributes.speed * phase.speedMultiplier !in 0.01..1.0)
                error("$p.speedMultiplier", "Use multiplier 0.25..2.5 while keeping derived movement speed within 0.01..1.0")
            if (!phase.damageMultiplier.isFinite() || phase.damageMultiplier !in 0.25..3.0
                || definition.attributes.attackDamage * phase.damageMultiplier !in 0.0..100.0)
                error("$p.damageMultiplier", "Use multiplier 0.25..3 while keeping derived attack damage at most 100")
            if (phase.windupTicks !in 1..100 || phase.recoveryTicks !in 4..200)
                error(p, "Phase windup is 1..100 ticks and recovery is 4..200 ticks")
            if (!phase.poseIntensity.isFinite() || phase.poseIntensity !in 0.5f..2f)
                error("$p.poseIntensity", "Pose intensity must be finite, 0.5..2.0")
            if (index > 0) {
                val previous = boss.phases[index - 1]
                if (phase.speedMultiplier == previous.speedMultiplier && phase.damageMultiplier == previous.damageMultiplier
                    && phase.windupTicks == previous.windupTicks && phase.recoveryTicks == previous.recoveryTicks)
                    error(p, "Each phase must change at least one actual combat parameter, not only its name or pose")
            }
        }
    }

    /** Monotonic transitions survive healing and save/reload; rendering consumes the server-selected index. */
    @JvmStatic fun phaseIndex(profile: CreatureBossProfile, healthRatio: Double, previous: Int): Int {
        require(profile.phases.size in 2..3 && healthRatio.isFinite())
        var index = previous.coerceIn(0, profile.phases.lastIndex)
        profile.phases.forEachIndexed { candidate, phase -> if (healthRatio <= phase.healthThreshold) index = maxOf(index, candidate) }
        return index
    }

    @JvmStatic fun phase(profile: CreatureBossProfile, index: Int): CreatureBossPhase = profile.phases[index.coerceIn(0, profile.phases.lastIndex)]
}
