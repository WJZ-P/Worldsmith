package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import java.util.Locale

/** A cue is one existing sound event plus bounded playback parameters, not a new audio asset. */
@Serializable
data class CreatureSoundCue @JvmOverloads constructor(
    val sound: CreatureSound,
    val pitch: Float = 1f,
    val volume: Float = 0.7f,
    val pitchVariation: Float = 0.08f,
)

/** Null per-event overrides inherit the voice and its modulation. SILENT or volume=0 deliberately mutes a cue. */
@Serializable
data class CreatureSoundProfile @JvmOverloads constructor(
    val voice: CreatureVoice = CreatureVoice.WOLF,
    val pitch: Float = 1f,
    val volume: Float = 0.7f,
    val pitchVariation: Float = 0.08f,
    val ambientIntervalTicks: Int = 200,
    val ambient: CreatureSoundCue? = null,
    val hurt: CreatureSoundCue? = null,
    val death: CreatureSoundCue? = null,
    val attack: CreatureSoundCue? = null,
)

object CreatureSounds {
    @JvmStatic fun requiredSchema(c: CreatureDefinition): Int = when {
        c.sounds != null -> 3
        c.boss != null -> 2
        else -> 1
    }

    /** Compatibility fallback only; never materialized into an old bundle, hash or save. Explicit authoring wins. */
    @JvmStatic fun profile(c: CreatureDefinition): CreatureSoundProfile = c.sounds ?: run {
        val name = (c.id + " " + c.displayName).lowercase(Locale.ROOT)
        fun mentions(vararg words: String) = words.any { name.contains(it) }
        val voice = when {
            mentions("spider", "蜘蛛") -> CreatureVoice.SPIDER
            mentions("wolf", "hound", "狼", "犬") -> CreatureVoice.WOLF
            mentions("fox", "狐狸", "狐") -> CreatureVoice.FOX
            mentions("cat", "tiger", "lion", "猫", "虎", "狮") -> CreatureVoice.CAT
            mentions("rabbit", "hare", "兔") -> CreatureVoice.RABBIT
            mentions("crane", "bird", "chicken", "鹤", "鸟", "鸡", "雀") -> CreatureVoice.CHICKEN
            mentions("sheep", "goat", "deer", "羊", "鹿") -> CreatureVoice.SHEEP
            mentions("boar", "pig", "猪") -> CreatureVoice.PIG
            mentions("bear", "熊") -> CreatureVoice.POLAR_BEAR
            mentions("slime", "史莱姆", "黏液") -> CreatureVoice.SLIME
            mentions("skeleton", "骨", "骷髅") -> CreatureVoice.SKELETON
            mentions("golem", "construct", "guard", "tortoise", "傀儡", "守卫", "龟") -> CreatureVoice.IRON_GOLEM
            mentions("wraith", "ghost", "幽", "鬼", "魂") -> CreatureVoice.GHAST
            mentions("blaze", "ember", "flame", "炎", "焰") -> CreatureVoice.BLAZE
            c.boss != null -> CreatureVoice.RAVAGER
            c.category == CreatureCategory.HOSTILE -> CreatureVoice.WOLF
            c.attributes.height < 0.8f -> CreatureVoice.RABBIT
            else -> CreatureVoice.COW
        }
        CreatureSoundProfile(voice, pitch = (1.25f - c.attributes.height * 0.15f).coerceIn(0.65f, 1.3f),
            volume = if (c.boss != null) 1f else 0.65f, ambientIntervalTicks = if (c.boss != null) 300 else 200)
    }

    @JvmStatic fun sound(voice: CreatureVoice, role: CreatureSoundRole): CreatureSound = when (role) {
        CreatureSoundRole.AMBIENT -> when (voice) {
            CreatureVoice.IRON_GOLEM -> CreatureSound.SILENT
            CreatureVoice.SLIME -> CreatureSound.SLIME_SQUISH
            else -> CreatureSound.valueOf("${voice.name}_AMBIENT")
        }
        CreatureSoundRole.HURT -> CreatureSound.valueOf("${voice.name}_HURT")
        CreatureSoundRole.DEATH -> CreatureSound.valueOf("${voice.name}_DEATH")
        CreatureSoundRole.ATTACK -> when (voice) {
            CreatureVoice.WOLF -> CreatureSound.WOLF_GROWL
            CreatureVoice.FOX -> CreatureSound.FOX_BITE
            CreatureVoice.CAT -> CreatureSound.CAT_HISS
            CreatureVoice.RABBIT -> CreatureSound.RABBIT_ATTACK
            CreatureVoice.SPIDER -> CreatureSound.SPIDER_HURT
            CreatureVoice.ZOMBIE -> CreatureSound.ZOMBIE_AMBIENT
            CreatureVoice.SKELETON -> CreatureSound.SKELETON_SHOOT
            CreatureVoice.ENDERMAN -> CreatureSound.ENDERMAN_SCREAM
            CreatureVoice.BLAZE -> CreatureSound.BLAZE_SHOOT
            CreatureVoice.GHAST -> CreatureSound.GHAST_WARN
            CreatureVoice.SLIME -> CreatureSound.SLIME_ATTACK
            CreatureVoice.IRON_GOLEM -> CreatureSound.IRON_GOLEM_ATTACK
            CreatureVoice.RAVAGER -> CreatureSound.RAVAGER_ATTACK
            CreatureVoice.POLAR_BEAR -> CreatureSound.POLAR_BEAR_WARNING
            else -> CreatureSound.SILENT
        }
    }

    @JvmStatic fun cue(profile: CreatureSoundProfile, role: CreatureSoundRole): CreatureSoundCue =
        (when (role) {
            CreatureSoundRole.AMBIENT -> profile.ambient
            CreatureSoundRole.HURT -> profile.hurt
            CreatureSoundRole.DEATH -> profile.death
            CreatureSoundRole.ATTACK -> profile.attack
        }) ?: CreatureSoundCue(sound(profile.voice, role), profile.pitch, profile.volume, profile.pitchVariation)

    /** Caller provides one server random sample in [0,1]; clamp to Minecraft's effective pitch range. */
    @JvmStatic fun playbackPitch(cue: CreatureSoundCue, sample: Float): Float =
        (cue.pitch + (sample.coerceIn(0f, 1f) * 2f - 1f) * cue.pitchVariation).coerceIn(0.5f, 2f)

    @JvmStatic fun vocabulary(): Map<String, String> = CreatureSound.entries.associate { it.name to (it.eventId ?: "silent") }
    @JvmStatic fun voices(): Map<String, Map<String, String>> = CreatureVoice.entries.associate { voice ->
        voice.name to CreatureSoundRole.entries.associate { it.name.lowercase(Locale.ROOT) to sound(voice, it).name }
    }

    @JvmStatic fun validate(profile: CreatureSoundProfile, path: String): List<Diagnostic> = buildList {
        fun error(field: String, message: String) { add(Diagnostic("$path.$field", "creature.sound.invalid", DiagnosticSeverity.ERROR, message)) }
        fun modulation(pitch: Float, volume: Float, variation: Float, prefix: String = "") {
            if (!pitch.isFinite() || pitch !in 0.5f..2f) error(prefix + "pitch", "Pitch must be finite, 0.5..2.0")
            if (!volume.isFinite() || volume !in 0f..2f) error(prefix + "volume", "Volume must be finite, 0..2.0; zero mutes the cue")
            if (!variation.isFinite() || variation !in 0f..0.2f) error(prefix + "pitchVariation", "Pitch variation must be finite, 0..0.2")
        }
        modulation(profile.pitch, profile.volume, profile.pitchVariation)
        if (profile.ambientIntervalTicks !in 120..2400) error("ambientIntervalTicks", "Ambient cooldown must be 120..2400 ticks; vanilla adds its random delay")
        mapOf("ambient" to profile.ambient, "hurt" to profile.hurt, "death" to profile.death, "attack" to profile.attack).forEach { (role, cue) ->
            cue?.let { modulation(it.pitch, it.volume, it.pitchVariation, "$role.") }
        }
    }
}
