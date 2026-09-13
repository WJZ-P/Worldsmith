package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.creatureauthoring.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreatureSoundsTest {
    private val creature = CreatureDefinition("frost_wolf", "霜狼", CreatureCategory.HOSTILE,
        CreatureModel("a".repeat(64), bones=listOf(CreatureBone("body", cubes=listOf(
            CreatureCube(CreatureVector(), CreatureVector(8f,8f,8f)))))))

    @Test fun `all voices resolve every role and expose only vanilla ids`() {
        assertEquals(18, CreatureSounds.voices().size)
        assertEquals(69, CreatureSounds.vocabulary().size) // 68 actual events plus SILENT
        for (voice in CreatureVoice.entries) for (role in CreatureSoundRole.entries) {
            val cue=CreatureSounds.cue(CreatureSoundProfile(voice),role)
            assertTrue(cue.sound == CreatureSound.SILENT || cue.sound.eventId!!.startsWith("minecraft:entity."))
        }
    }

    @Test fun `new sounds require explicit schema and preserve overrides through serialization and freezing`() {
        val profile=CreatureSoundProfile(CreatureVoice.WOLF, pitch=0.7f, volume=0.8f,
            attack=CreatureSoundCue(CreatureSound.RAVAGER_ROAR, pitch=0.6f, volume=1.2f))
        val voiced=creature.copy(sounds=profile)
        for (version in 1..2) assertTrue(CustomCreatureValidator.validate(CreatureLibrary(version,listOf(voiced))).any {it.path.endsWith(".sounds")})
        val library=CreatureLibrary(3,listOf(voiced))
        assertTrue(CustomCreatureValidator.validate(library).isEmpty())
        assertEquals(library,WorldsmithJson.decode<CreatureLibrary>(WorldsmithJson.encode(CustomCreatureValidator.freeze(library))))
        assertEquals(profile.attack,CreatureSounds.cue(profile,CreatureSoundRole.ATTACK))
        assertEquals(0.7f,CreatureSounds.cue(profile,CreatureSoundRole.HURT).pitch)
    }

    @Test fun `legacy fallback never adds a serialized field and explicit voices win`() {
        val before=WorldsmithJson.encode(creature)
        assertFalse(before.contains("\"sounds\""))
        assertEquals(CreatureVoice.WOLF,CreatureSounds.profile(creature).voice)
        assertEquals(before,WorldsmithJson.encode(creature))
        assertEquals(CreatureVoice.FOX,CreatureSounds.profile(creature.copy(sounds=CreatureSoundProfile(CreatureVoice.FOX))).voice)
        assertEquals(CreatureVoice.SPIDER,CreatureSounds.profile(creature.copy(id="bamboo_spider",displayName="竹蛛")).voice)
        assertEquals(CreatureVoice.RABBIT,CreatureSounds.profile(creature.copy(id="rabbit",displayName="玉兔")).voice)
    }

    @Test fun `invalid amplitudes nonfinite values intervals and overrides are rejected`() {
        val invalid=listOf(CreatureSoundProfile(pitch=Float.NaN),CreatureSoundProfile(volume=Float.POSITIVE_INFINITY),
            CreatureSoundProfile(pitch=0.49f),CreatureSoundProfile(volume=2.01f),CreatureSoundProfile(pitchVariation=-0.1f),
            CreatureSoundProfile(ambientIntervalTicks=119),CreatureSoundProfile(ambientIntervalTicks=2401),
            CreatureSoundProfile(death=CreatureSoundCue(CreatureSound.WOLF_DEATH,volume=-1f)))
        invalid.forEach {assertFalse(CreatureSounds.validate(it,"sounds").isEmpty())}
        assertTrue(CreatureSounds.validate(CreatureSoundProfile(volume=0f,attack=CreatureSoundCue(CreatureSound.SILENT)),"sounds").isEmpty())
        assertThrows(Exception::class.java) {WorldsmithJson.decode<CreatureSoundCue>("""{"sound":"MADE_UP_ROAR"}""")}
    }

    @Test fun `pitch modulation respects endpoints and can be disabled`() {
        assertEquals(0.5f,CreatureSounds.playbackPitch(CreatureSoundCue(CreatureSound.WOLF_HURT,pitch=0.5f,pitchVariation=0.2f),0f))
        assertEquals(2f,CreatureSounds.playbackPitch(CreatureSoundCue(CreatureSound.WOLF_HURT,pitch=2f,pitchVariation=0.2f),1f))
        assertEquals(0.8f,CreatureSounds.playbackPitch(CreatureSoundCue(CreatureSound.WOLF_HURT,pitch=0.8f,pitchVariation=0f),0.9f))
    }

    @Test fun `builder and recipe retain voice through real geometry compilation`() {
        val profile=CreatureSoundProfile(CreatureVoice.IRON_GOLEM,pitch=0.75f)
        val builder=CreatureBuilder.create("guardian","守卫",CreatureCategory.HOSTILE).sounds(profile)
        builder.bone("body",null,0f,24f,0f).cube("core",-4f,-8f,-4f,8,8,8).end()
        assertEquals(3,builder.recipe().schemaVersion)
        assertEquals(profile,builder.build("a".repeat(64)).definition.sounds)
        val guide=builder.guide()
        assertTrue(CreaturePreview.sheet(guide.definition,guide.png).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {CreatureAuthoring.compile(builder.recipe().copy(schemaVersion=2),"a".repeat(64))}
    }
}
