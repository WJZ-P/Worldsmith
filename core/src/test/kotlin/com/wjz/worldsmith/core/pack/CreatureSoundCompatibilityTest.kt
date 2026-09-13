package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.creatureauthoring.CreatureBuilder
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.model.WorldsmithModuleFile
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreatureSoundCompatibilityTest {
    private fun voicedPack(pitch: Float): WorldsmithPack {
        val base=WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val builder=CreatureBuilder.create("voice_test","Voice test",CreatureCategory.PASSIVE)
            .sounds(CreatureSoundProfile(CreatureVoice.SHEEP,pitch=pitch))
        builder.bone("body",null,0f,24f,0f).cube("body",-4f,-8f,-4f,8,8,8).end()
        val guide=builder.guide()
        return WorldContentBundleIO.create("Voice sample","A meadow creature",base.terrain,base.biomes,base.features,
            base.structures,base.theme,base.blocks,CreatureLibrary(3,listOf(guide.definition)),
            mapOf(guide.asset.id to guide.png),base.items,base.quests)
    }

    @Test fun `format six carries schema three and modulation participates in immutable identity`() {
        val first=voicedPack(0.7f)
        assertEquals(6,first.manifest.formatVersion)
        assertEquals(3,first.manifest.modules.getValue("creatures").schemaVersion)
        val encoded=WorldContentBundleIO.encode(first)
        assertEquals(first.computedId,WorldsmithHashUtil.computeGenerationId(encoded.manifest,encoded.texts,encoded.binaries))
        assertNotEquals(first.computedId,voicedPack(1.2f).computedId)
    }

    @Test fun `sounds are never silently stripped into a legacy schema or format`() {
        val pack=voicedPack(0.8f)
        val downgraded=pack.copy(creatures=pack.creatures.copy(schemaVersion=2),manifest=pack.manifest.copy(
            modules=pack.manifest.modules + ("creatures" to WorldsmithModuleFile(2,"creatures.json"))))
        assertThrows(IllegalArgumentException::class.java) {WorldContentBundleIO.encode(downgraded)}
        assertTrue(WorldsmithPackValidator.validate(downgraded).any { it.path.endsWith(".sounds") })
        assertThrows(IllegalArgumentException::class.java) {WorldContentBundleIO.encode(pack.copy(manifest=pack.manifest.copy(formatVersion=5)))}
        assertThrows(IllegalArgumentException::class.java) {LegacyCreaturesV3.encode(pack.creatures)}
    }
}
