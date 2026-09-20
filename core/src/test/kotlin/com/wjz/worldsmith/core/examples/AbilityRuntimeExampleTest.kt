package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbilityRuntimeExampleTest {
    @Test fun `source programs survive an immutable deterministic archive`(@TempDir output: Path) {
        val pack = AbilityRuntimeExample.create()
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { "${it.path}: ${it.message}" })
        assertEquals(pack.computedId, AbilityRuntimeExample.create().computedId)
        assertTrue(AbilityPrograms.validate(pack.abilities).isEmpty())
        val archive = output.resolve("programmed-trial.wspack")
        WorldsmithResourceArchive.write(pack, archive)
        val restored = WorldsmithResourceArchive.read(archive).pack
        assertEquals(10, restored.manifest.formatVersion)
        assertEquals(pack.computedId, restored.computedId)
        assertEquals(pack.abilities, restored.abilities)
        assertEquals(pack.creatures, restored.creatures)
        assertEquals(pack.items, restored.items)
        assertEquals(pack.mechanics, restored.mechanics)
        restored.abilities.programs.forEach { assertEquals(AbilityRuntimeExample.source(it.id), it.source) }
    }

    @Test fun `one actual source program is wired to creature item and mechanic`() {
        val pack = AbilityRuntimeExample.create()
        val guardian = pack.creatures.creatures.single()
        assertEquals(AbilityRuntimeExample.ECHO, guardian.ability?.program)
        assertTrue(guardian.boss!!.phases.isEmpty(), "This Boss does not need a fixed stat-phase table")
        val wand = pack.items.items.single { it.id == AbilityRuntimeExample.ECHO_WAND }
        assertEquals(ItemEffect.RunProgram(AbilityRuntimeExample.ECHO), wand.actions.single().effects.single())
        assertEquals(ItemActionTrigger.USE, wand.actions.single().trigger)
        val pedestal = pack.mechanics.mechanics.single { it.id == AbilityRuntimeExample.PEDESTAL }.rules.single()
        assertEquals(MechanicAction.RunProgram(AbilityRuntimeExample.ECHO), pedestal.actions.single())
        assertEquals(pedestal.fromState, pedestal.toState)
        assertEquals(1, pack.abilities.programs.count { it.id == AbilityRuntimeExample.ECHO })
    }

    @Test fun `the physical key route is preserved and all source wands are obtainable`() {
        val base = MechanicDiscoveryExample.create()
        val pack = AbilityRuntimeExample.create()
        val blueprint = pack.structures.structures.single().blueprint
        StructureGeometryCompiler.compile(blueprint)
        assertEquals(base.creatures.creatures.single().drops, pack.creatures.creatures.single().drops)
        assertEquals(base.mechanics.mechanics.single { it.id == MechanicDiscoveryExample.GATE },
            pack.mechanics.mechanics.single { it.id == MechanicDiscoveryExample.GATE })
        assertEquals(base.quests.quests.single().objectives, pack.quests.quests.single().objectives)
        val supply = blueprint.interactions.filterIsInstance<StructureInteraction.Container>().single()
        assertTrue(supply.items.map { it.item }.containsAll(listOf("worldsmith:item/${AbilityRuntimeExample.ECHO_WAND}",
            "worldsmith:item/${AbilityRuntimeExample.CHANNEL_WAND}", "worldsmith:item/${AbilityRuntimeExample.ORB_WAND}")))
        assertFalse(supply.items.any { it.item == "worldsmith:item/${MechanicDiscoveryExample.KEY}" })
    }

    @Test fun `echo captures three different coordinates then damages only those stored regions`() {
        val host = RecordingHost()
        val machine = machine(AbilityRuntimeExample.ECHO, host)
        for (tick in 0L..200L) {
            host.tick = tick
            machine.tick(tick, 128)
            assertNull(machine.failure())
        }
        assertEquals(listOf(0.0, 3.0, 6.0), host.marked.take(3).map { AbilityRegions.bounds(it).minX + 1.8 })
        assertEquals(host.marked.take(3), host.queried, "Effects query the stored region values, not fresh target coordinates")
        assertEquals(3, host.damage.size)
        assertTrue(host.damage.all { it.first >= 36 }, "A captured mark must not damage during the warning")
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["completed"])
        assertEquals(AbilityValues.number(1.0), machine.snapshotState()["starts"])
        assertEquals(3, host.positionSamples)
    }

    @Test fun `changing only source changes behavior and content hash without a new engine operation`() {
        val pack = AbilityRuntimeExample.create()
        val original = pack.abilities.programs.single { it.id == AbilityRuntimeExample.ECHO }
        val edited = original.copy(source = original.source.replace("repeat 3 {", "repeat 2 {"))
        val before = AbilityCompiler.compile(original)
        val after = AbilityCompiler.compile(edited)
        assertEquals(before.usedCapabilities, after.usedCapabilities)
        val host = RecordingHost()
        val machine = AbilityMachine(after, host, mapOf("self" to AbilityValues.entity("caster"),
            "target" to AbilityValues.entity("victim"), "origin" to AbilityValues.vector(0.0, 0.0, 0.0)))
        for (tick in 0L..200L) { machine.tick(tick, 128); assertNull(machine.failure()) }
        assertEquals(2, host.positionSamples)
        assertEquals(2, host.damage.size)
        val changed = pack.copy(abilities = pack.abilities.copy(programs = pack.abilities.programs.map { if (it.id == edited.id) edited else it }))
        assertNotEquals(pack.computedId, WorldContentBundleIO.encode(changed).manifest.id)
        assertEquals(original.source, pack.abilities.programs.single { it.id == edited.id }.source)
    }

    @Test fun `shared sources claim creature control without rejecting the player host`() {
        for (id in listOf(AbilityRuntimeExample.ECHO, AbilityRuntimeExample.CHANNEL)) {
            val creature = RecordingHost()
            val creatureMachine = machine(id, creature)
            for (tick in 0L..160L) { creature.tick = tick; creatureMachine.tick(tick, 128); assertNull(creatureMachine.failure()) }
            assertEquals(1, creature.claims)
            assertEquals(1, creature.releases)
            assertFalse(creature.ownsMovement)
            val player = RecordingHost("player")
            val playerMachine = machine(id, player)
            for (tick in 0L..160L) { player.tick = tick; playerMachine.tick(tick, 128); assertNull(playerMachine.failure()) }
            assertEquals(0, player.claims, "Player self-motion does not need an NPC token")
            assertTrue(player.damage.isNotEmpty(), "Shared source exited merely because its host was a player")
        }
    }

    @Test fun `preempted creature control stops a waiting attack before its first damage frame`() {
        for (id in listOf(AbilityRuntimeExample.ECHO, AbilityRuntimeExample.CHANNEL)) {
            val host = RecordingHost()
            val machine = machine(id, host)
            machine.tick(0, 128)
            assertTrue(host.ownsMovement)
            host.ownsMovement = false
            for (tick in 1L..160L) { host.tick = tick; machine.tick(tick, 128); assertNull(machine.failure()) }
            assertTrue(host.damage.isEmpty(), "Source continued delayed damage after losing control")
        }
    }

    @Test fun `hurt and block break select weakness branch rather than delayed damage`() {
        for (event in listOf("hurt", "block_break")) {
            val host = RecordingHost()
            val machine = machine(AbilityRuntimeExample.CHANNEL, host)
            for (tick in 0L..150L) {
                if (tick == 12L) assertTrue(machine.emit(event, mapOf("event_position" to AbilityValues.vector(1.0, 0.0, 0.0))))
                machine.tick(tick, 128)
                assertNull(machine.failure())
            }
            assertEquals(AbilityValues.text("weakened"), machine.snapshotState()["branch"])
            assertEquals(AbilityValues.text(event), machine.snapshotState()["reason"])
            assertTrue(host.damage.isEmpty())
            assertEquals(1, host.statuses.size)
            assertEquals(AbilityValues.text("minecraft:weakness"), host.statuses.single()[1])
            assertEquals(1, host.cleared)
        }
    }

    @Test fun `uninterrupted channel releases and a distant block break does not interrupt`() {
        val host = RecordingHost()
        val machine = machine(AbilityRuntimeExample.CHANNEL, host)
        for (tick in 0L..150L) {
            host.tick = tick
            if (tick == 12L) machine.emit("block_break", mapOf("event_position" to AbilityValues.vector(7.0, 0.0, 0.0)))
            machine.tick(tick, 128)
            assertNull(machine.failure())
        }
        assertEquals(AbilityValues.text("released"), machine.snapshotState()["branch"])
        assertEquals(1, host.damage.size)
        assertTrue(host.damage.single().first >= 60)
        assertTrue(host.statuses.isEmpty())
    }

    @Test fun `projectile callback splits seed but sparks do not recurse`() {
        val host = RecordingHost()
        val machine = machine(AbilityRuntimeExample.ORB, host)
        machine.tick(0, 128)
        assertNull(machine.failure())
        assertEquals(listOf("seed"), host.projectiles.map { (it[4] as AbilityValue.TextValue).value })
        assertTrue(machine.emit("projectile_hit", mapOf("event_tag" to AbilityValues.text("seed"),
            "event_position" to AbilityValues.vector(3.0, 0.0, 0.0), "event_entity" to AbilityValues.entity("victim"))))
        for (tick in 1L..8L) machine.tick(tick, 128)
        assertNull(machine.failure())
        assertEquals(listOf("seed", "spark", "spark"), host.projectiles.map { (it[4] as AbilityValue.TextValue).value })
        assertTrue(machine.emit("projectile_hit", mapOf("event_tag" to AbilityValues.text("spark"),
            "event_position" to AbilityValues.vector(4.0, 0.0, 0.0), "event_entity" to AbilityValues.none())))
        for (tick in 9L..16L) machine.tick(tick, 128)
        assertNull(machine.failure())
        assertEquals(3, host.projectiles.size)
        assertEquals(AbilityValues.number(2.0), machine.snapshotState()["hits"])
        assertEquals(AbilityValues.text("spark_finished"), machine.snapshotState()["branch"])
    }

    @Test fun `new registered capability runs through an unchanged compiler and VM`() {
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("test.mark", arguments = listOf(AbilityType.NUMBER),
            result = AbilityType.BOOL, effect = true, description = "Recording-only example extension"))
        val source = AbilityProgramDefinition("extension_demo", "Extension without VM branches", """
            fn twice(x) { return x * 2; }
            on start { let i = 0; repeat 3 { test.mark(twice(i)); i = i + 1; wait 2; } }
        """.trimIndent(), requires = mapOf("test.mark" to 1))
        val values = mutableListOf<Double>()
        val machine = AbilityMachine(AbilityCompiler.compile(source, registry), AbilityHost { name, args ->
            assertEquals("test.mark", name)
            values += (args.single() as AbilityValue.NumberValue).value
            AbilityValues.bool(true)
        }, emptyMap())
        for (tick in 0L..30L) { machine.tick(tick, 128); assertNull(machine.failure()) }
        assertEquals(listOf(0.0, 2.0, 4.0), values)
        assertThrows(IllegalArgumentException::class.java) { AbilityCompiler.compile(source) }
    }

    private fun machine(id: String, host: RecordingHost) = AbilityMachine(
        AbilityCompiler.compile(AbilityRuntimeExample.programs().programs.single { it.id == id }), host,
        mapOf("self" to AbilityValues.entity("caster"), "target" to AbilityValues.entity("victim"), "origin" to AbilityValues.vector(0.0, 0.0, 0.0)))

    private class RecordingHost(private val kind: String = "creature") : AbilityHost {
        var tick = 0L
        var positionSamples = 0
        var cleared = 0
        var claims = 0
        var releases = 0
        var ownsMovement = false
        val marked = mutableListOf<AbilityValue>()
        val queried = mutableListOf<AbilityValue>()
        val damage = mutableListOf<Pair<Long, List<AbilityValue>>>()
        val statuses = mutableListOf<List<AbilityValue>>()
        val projectiles = mutableListOf<List<AbilityValue>>()
        override fun call(name: String, arguments: List<AbilityValue>): AbilityValue = when (name) {
            "entity.kind" -> AbilityValues.text(kind)
            "control.claim" -> { claims++; ownsMovement = kind == "creature"; AbilityValues.number(if (ownsMovement) 1.0 else 0.0) }
            "control.held" -> AbilityValues.bool(ownsMovement && arguments.single() == AbilityValues.number(1.0))
            "control.release" -> { releases++; val owned = ownsMovement; ownsMovement = false; AbilityValues.bool(owned) }
            "entity.alive" -> AbilityValues.bool(arguments.single() is AbilityValue.EntityValue)
            "entity.position" -> if ((arguments.single() as AbilityValue.EntityValue).id == "victim")
                AbilityValues.vector(positionSamples++ * 3.0, 0.0, 0.0) else AbilityValues.vector(0.0, 0.0, 0.0)
            "world.aim" -> AbilityValues.vector(0.0, 0.0, 6.0)
            "world.entities" -> { queried += arguments[0]; AbilityValues.list(listOf(AbilityValues.entity("victim"))) }
            "world.visible" -> AbilityValues.bool(true)
            "fx.telegraph" -> { marked += arguments[0]; AbilityValues.number(marked.size.toDouble()) }
            "fx.clear" -> { cleared++; AbilityValues.bool(true) }
            "combat.damage" -> { damage += tick to arguments; AbilityValues.bool(true) }
            "status.apply" -> { statuses += arguments; AbilityValues.bool(true) }
            "projectile.emit" -> { projectiles += arguments; AbilityValues.entity("projectile_${projectiles.size}") }
            "motion.stop" -> { assertTrue(kind == "player" || ownsMovement, "Creature motion must follow an accepted claim"); AbilityValues.bool(true) }
            "fx.pose", "fx.caption", "fx.sound", "fx.message" -> AbilityValues.bool(true)
            else -> error("Unexpected example capability: $name")
        }
    }
}
