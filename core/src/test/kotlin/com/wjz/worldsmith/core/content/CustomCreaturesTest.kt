package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomCreaturesTest {
    private val cube = CreatureCube(CreatureVector(-4f, -8f, -4f), CreatureVector(8f, 8f, 8f))
    private val body = CreatureBone("body", pivot = CreatureVector(0f, 24f, 0f), cubes = listOf(cube))
    private val model = CreatureModel("a".repeat(64), bones = listOf(body))
    private val creature = CreatureDefinition("moon_stag", "Moon Stag", CreatureCategory.PASSIVE, model, themeRole = "Guardian of the moonlit woodland")
    private fun diagnostics(d: CreatureDefinition) = CustomCreatureValidator.validate(CreatureLibrary(creatures = listOf(d)))

    @Test fun `valid custom geometry and theme metadata round trip without loss`() {
        val library = CreatureLibrary(creatures = listOf(creature))
        assertTrue(CustomCreatureValidator.validate(library).isEmpty())
        assertEquals(library, WorldsmithJson.decode<CreatureLibrary>(WorldsmithJson.encode(library)))
    }

    @Test fun `empty library is explicit supported no-creatures module`() {
        assertTrue(CustomCreatureValidator.validate(CreatureLibrary()).isEmpty())
    }

    @Test fun `duplicates unsupported schema and excessive species are rejected`() {
        val diagnostics = CustomCreatureValidator.validate(CreatureLibrary(2, List(129) { creature }))
        assertTrue(diagnostics.any { it.path == "creatures.schemaVersion" })
        assertTrue(diagnostics.any { it.message.contains("At most") })
        assertTrue(diagnostics.any { it.message.contains("Duplicate creature") })
    }

    @Test fun `cyclic and missing bone parents fail before model baking`() {
        val cycle = listOf(body.copy(parent = "head"), CreatureBone("head", parent = "body"))
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = cycle))).any { it.message.contains("cycle") })
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body.copy(parent = "absent"))))).any { it.message.contains("Unknown parent") })
    }

    @Test fun `duplicate empty oversized and deeply nested geometry is rejected`() {
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body, body)))).any { it.message.contains("unique") })
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = emptyList()))).isNotEmpty())
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body.copy(cubes = List(257) { cube }))))).any { it.message.contains("256 cubes") })
        val deep = (0..16).map { i -> body.copy(id = "b$i", parent = if (i == 0) null else "b${i - 1}", pivot = CreatureVector()) }
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = deep))).any { it.message.contains("depth") })
    }

    @Test fun `accumulated parent offsets are bounded even when each bone is locally valid`() {
        val bones = listOf(body.copy(pivot = CreatureVector(120f), parent = "head"), CreatureBone("head", pivot = CreatureVector(120f)))
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = bones))).any { it.message.contains("Accumulated") })
    }

    @Test fun `box UV validates the entire unfolded texture footprint`() {
        val out = cube.copy(uv = CreatureUv(40, 0))
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body.copy(cubes = listOf(out)))))).any { it.path.endsWith(".uv") })
        val fractional = cube.copy(size = CreatureVector(8.5f, 8f, 8f))
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body.copy(cubes = listOf(fractional)))))).any { it.path.endsWith(".size") })
    }

    @Test fun `only content addressed textures and valid dimensions are accepted`() {
        assertTrue(diagnostics(creature.copy(model = model.copy(texture = "https://example.test/skin.png"))).any { it.path.endsWith(".texture") })
        assertTrue(diagnostics(creature.copy(model = model.copy(textureWidth = 63))).any { it.path.endsWith(".model") })
    }

    @Test fun `nonfinite and unbounded attributes and coordinates are rejected`() {
        assertTrue(diagnostics(creature.copy(attributes = CreatureAttributes(health = Double.NaN, speed = 999.0))).size >= 2)
        assertTrue(diagnostics(creature.copy(model = model.copy(bones = listOf(body.copy(rotation = CreatureVector(Float.POSITIVE_INFINITY)))))).any { it.path.endsWith(".rotation") })
    }

    @Test fun `behavior timing and spawn density are bounded`() {
        assertTrue(diagnostics(creature.copy(behavior = CreatureBehavior(windupTicks = 0, recoveryTicks = 0))).size >= 2)
        assertTrue(diagnostics(creature.copy(spawn = CreatureSpawn(weight = 0, minGroup = 5, maxGroup = 2, minLight = 15, maxLight = 0))).size >= 3)
        assertTrue(diagnostics(creature.copy(spawn = CreatureSpawn(biomes = listOf("../escape")))).any { it.path.endsWith(".biomes") })
    }

    @Test fun `quadruped gait phases and arms survive typed serialization`() {
        val bones = listOf(body, CreatureBone("hindLeft", "body", role = CreatureBoneRole.LEG_LEFT, gaitPhase = 180f), CreatureBone("arm", "body", role = CreatureBoneRole.ARM_RIGHT))
        val variant = creature.copy(model = model.copy(bones = bones))
        assertTrue(diagnostics(variant).isEmpty())
        assertEquals(variant, WorldsmithJson.decode<CreatureDefinition>(WorldsmithJson.encode(variant)))
    }

    @Test fun `hostile windup strikes exactly once then waits full recovery`() {
        val behavior = CreatureBehavior(windupTicks = 3, recoveryTicks = 4)
        var frame = CreatureCombatFrame()
        val decisions = (0..10).map {
            CreatureCombat.advance(frame, true, true, true, behavior).also { frame = it.frame }
        }
        assertEquals(listOf(CreatureCombatState.WINDUP, CreatureCombatState.WINDUP, CreatureCombatState.WINDUP, CreatureCombatState.STRIKE, CreatureCombatState.STRIKE, CreatureCombatState.STRIKE,
            CreatureCombatState.RECOVERY, CreatureCombatState.RECOVERY, CreatureCombatState.RECOVERY, CreatureCombatState.RECOVERY, CreatureCombatState.CHASE), decisions.map { it.frame.state })
        assertEquals(1, decisions.count { it.strike })
    }

    @Test fun `damage frame rechecks sight and range independently`() {
        val windup = CreatureCombatFrame(CreatureCombatState.WINDUP, 1)
        assertFalse(CreatureCombat.advance(windup, true, false, true, CreatureBehavior()).strike)
        assertFalse(CreatureCombat.advance(windup, true, true, false, CreatureBehavior()).strike)
        assertTrue(CreatureCombat.advance(windup, true, true, true, CreatureBehavior()).strike)
    }

    @Test fun `invalid target cancels every combat phase without damage`() {
        CreatureCombatState.entries.forEach { state ->
            val decision = CreatureCombat.advance(CreatureCombatFrame(state, 1), false, true, true, CreatureBehavior())
            assertFalse(decision.strike)
            assertEquals(CreatureCombatState.IDLE, decision.frame.state)
        }
    }

    @Test fun `out of range starts chasing rather than striking`() {
        assertEquals(CreatureCombatState.CHASE, CreatureCombat.advance(CreatureCombatFrame(), true, false, true, CreatureBehavior()).frame.state)
    }
}
