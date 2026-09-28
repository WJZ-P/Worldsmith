package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives are the relationships a creature has to the rest of the world. The
 * validator can only check their shape - a native id may still name nothing,
 * which the Minecraft side reports and skips - but it can refuse the ones that
 * contradict themselves or point at a local creature that does not exist.
 */
class CreatureDrivesTest {
    private val cube = CreatureCube(CreatureVector(-4f, -8f, -4f), CreatureVector(8f, 8f, 8f))
    private val model = CreatureModel("a".repeat(64), bones = listOf(CreatureBone("body", pivot = CreatureVector(0f, 24f, 0f), cubes = listOf(cube))))

    private fun creature(id: String, drives: CreatureDrives = CreatureDrives()) =
        CreatureDefinition(id, id, CreatureCategory.PASSIVE, model, behavior = CreatureBehavior(drives = drives))

    private fun codes(schema: Int, vararg creatures: CreatureDefinition) =
        CustomCreatureValidator.validate(CreatureLibrary(schema, creatures.toList())).map { it.path + " " + it.message }

    @Test
    fun `a small food web validates`() {
        val grazer = creature("fawn", CreatureDrives(eats = listOf("minecraft:short_grass"), fears = listOf("wolfkin"), herds = true))
        val predator = creature("wolfkin", CreatureDrives(hunts = listOf("fawn", "minecraft:rabbit"), activity = CreatureActivity.NIGHT))
        val moth = creature("lantern_moth", CreatureDrives(movement = CreatureMovement.FLY, temptedBy = listOf("minecraft:torch")))

        assertEquals(emptyList<String>(), codes(7, grazer, predator, moth))
    }

    @Test
    fun `drives need the schema that introduced them`() {
        val grazer = creature("fawn", CreatureDrives(eats = listOf("minecraft:short_grass")))

        assertTrue(codes(6, grazer).any { "schemaVersion 7" in it })
        // An unchanged creature stays valid at every older schema.
        assertEquals(emptyList<String>(), codes(6, creature("plain")))
    }

    @Test
    fun `contradictions and dangling local creatures are refused`() {
        val self = creature("loner", CreatureDrives(hunts = listOf("loner")))
        val torn = creature("torn", CreatureDrives(hunts = listOf("minecraft:sheep"), fears = listOf("minecraft:sheep")))
        val ghost = creature("seeker", CreatureDrives(hunts = listOf("no_such_creature")))

        val problems = codes(7, self, torn, ghost)
        assertTrue(problems.any { "own kind" in it }, problems.toString())
        assertTrue(problems.any { "both hunted and feared" in it }, problems.toString())
        assertTrue(problems.any { "hunts[0]" in it }, problems.toString())
    }

    @Test
    fun `an unchanged creature does not grow a drives field on disk`() {
        // Older packs round-trip byte-for-byte: the default is never encoded.
        val json = WorldsmithJson.encode(creature("plain"))
        assertFalse("drives" in json, json)
        val lively = creature("fawn", CreatureDrives(herds = true))
        assertEquals(lively, WorldsmithJson.decode<CreatureDefinition>(WorldsmithJson.encode(lively)))
    }

    @Test
    fun `verbs name what a creature does and links count what happens unwatched`() {
        val drives = CreatureDrives(movement = CreatureMovement.FLY, eats = listOf("minecraft:short_grass"),
            hunts = listOf("minecraft:rabbit"), temptedBy = listOf("minecraft:wheat"), activity = CreatureActivity.DAY)

        assertTrue("flies" in drives.verbs())
        // A lure needs a player, so it is a verb but not an unwatched link; flight is neither a link nor free.
        assertEquals(3, drives.links())
        assertEquals(0, CreatureDrives().links())
        assertTrue(CreatureDrives().verbs().isEmpty())
    }
}
