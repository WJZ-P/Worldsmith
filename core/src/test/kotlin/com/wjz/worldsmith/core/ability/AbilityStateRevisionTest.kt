package com.wjz.worldsmith.core.ability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.IdentityHashMap

class AbilityStateRevisionTest {
    private fun machine(
        source: String,
        initialState: Map<String, AbilityValue> = emptyMap(),
        maxOperations: Int = 32768,
        host: AbilityHost = AbilityHost { _, _ -> fail("Unexpected host call") },
    ) = AbilityMachine(
        AbilityCompiler.compile(AbilityProgramDefinition("state_revision", "State revision", source, maxOperations = maxOperations)),
        host,
        mapOf("self" to AbilityValues.entity("actor"), "target" to AbilityValues.entity("victim")),
        initialState,
    )

    @Test fun `restored state starts at revision zero and snapshots are cached deeply immutable copies`() {
        val list = AbilityValues.list(listOf(AbilityValues.number(1.0)))
        val initial = mutableMapOf<String, AbilityValue>("list" to list)
        val machine = machine("on start { wait 5; }", initial)
        val snapshot = machine.snapshotState()
        initial.clear()
        assertEquals(0L, machine.stateRevision())
        assertEquals(mapOf("list" to list), snapshot)
        assertSame(snapshot, machine.snapshotState())
        assertThrows(UnsupportedOperationException::class.java) { (snapshot as MutableMap)["extra"] = AbilityValues.none() }
        assertThrows(UnsupportedOperationException::class.java) { ((snapshot.getValue("list") as AbilityValue.ListValue).values as MutableList).clear() }
        assertNull(machine.tick(0).error)
        assertSame(snapshot, machine.snapshotState())
        assertEquals(0L, machine.stateRevision())
    }

    @Test fun `only changed commits advance revision and old snapshots do not follow later writes`() {
        val machine = machine("on start { state.count = 1; wait 1; state.count = 1; wait 1; state.count = 2; wait 1; state.count = 1; }")
        val empty = machine.snapshotState()
        assertNull(machine.tick(0).error)
        val first = machine.snapshotState()
        assertEquals(1L, machine.stateRevision())
        assertNotSame(empty, first)
        assertTrue(empty.isEmpty())
        assertNull(machine.tick(1).error)
        assertEquals(1L, machine.stateRevision())
        assertSame(first, machine.snapshotState())
        assertNull(machine.tick(2).error)
        val second = machine.snapshotState()
        assertEquals(2L, machine.stateRevision())
        assertNotSame(first, second)
        assertEquals(AbilityValues.number(1.0), first["count"])
        assertEquals(AbilityValues.number(2.0), second["count"])
        assertNull(machine.tick(3).error)
        val third = machine.snapshotState()
        assertEquals(3L, machine.stateRevision())
        assertNotSame(first, third, "Returning to an earlier value is still a new committed revision")
        assertEquals(first, third)
        assertSame(third, machine.snapshotState())
        assertEquals(AbilityValues.number(2.0), second["count"])
    }

    @Test fun `fresh structurally equal number vector list and region writes preserve revision and identity`() {
        val initial = mapOf(
            "number" to AbilityValues.number(7.0),
            "vector" to AbilityValues.vector(1.0, 2.0, 3.0),
            "list" to AbilityValues.list(listOf(AbilityValues.number(7.0), AbilityValues.text("same"))),
            "region" to AbilityRegions.sphere(AbilityValues.vector(1.0, 2.0, 3.0), 2.0),
        )
        val machine = machine("""
            on start {
                state.number = 3 + 4;
                state.vector = vec(1, 2, 3);
                state.list = [7, "same"];
                state.region = shape.sphere(vec(1, 2, 3), 2);
            }
        """.trimIndent(), initial)
        val snapshot = machine.snapshotState()
        assertNull(machine.tick(0).error)
        assertTrue(machine.isIdle())
        assertEquals(0L, machine.stateRevision())
        assertSame(snapshot, machine.snapshotState())
        initial.forEach { (key, value) -> assertSame(value, snapshot[key], "No-op must keep the already validated value for $key") }
    }

    @Test fun `first explicit null is a state change while repeating null is not`() {
        val machine = machine("on start { state.explicit = null; wait 1; state.explicit = null; }")
        val before = machine.snapshotState()
        assertNull(machine.tick(0).error)
        assertEquals(1L, machine.stateRevision())
        val after = machine.snapshotState()
        assertTrue(after.containsKey("explicit"))
        assertFalse(before.containsKey("explicit"))
        assertNull(machine.tick(1).error)
        assertEquals(1L, machine.stateRevision())
        assertSame(after, machine.snapshotState())
    }

    @Test fun `rejected writes preserve revision and cached snapshot for every state budget`() {
        val cases = mutableListOf<Pair<Map<String, AbilityValue>, String>>()
        cases += (0 until 64).associate { "k$it" to AbilityValues.number(it.toDouble()) } to "state.extra = 1;"
        val text = "x".repeat(4096)
        cases += (0 until 15).associate { "k$it" to AbilityValues.text(text) } to "state.extra = \"$text\";"
        val subtree = AbilityValues.list(List(32) { AbilityValues.list(List(30) { AbilityValues.none() }) })
        cases += mapOf("first" to subtree, "second" to subtree) to "state.extra = [${List(64) { "null" }.joinToString(",")}];"
        val depthNineInState = (0 until 8).fold("null") { value, _ -> "[$value]" }
        cases += emptyMap<String, AbilityValue>() to "state.extra = $depthNineInState;"
        for ((initial, statement) in cases) {
            val machine = machine("on start { $statement }", initial)
            val snapshot = machine.snapshotState()
            assertNotNull(machine.tick(0).error)
            assertTrue(machine.isStopped())
            assertEquals(0L, machine.stateRevision())
            assertSame(snapshot, machine.snapshotState())
            assertEquals(initial, snapshot)
        }
    }

    @Test fun `quota suspension exposes a new revision only after the store instruction retires`() {
        val machine = machine("on start { state.count = 1; }")
        val before = machine.snapshotState()
        assertEquals(1, machine.tick(0, 1).operations) // CONSTANT, not STORE_STATE.
        assertEquals(0L, machine.stateRevision())
        assertSame(before, machine.snapshotState())
        assertEquals(1, machine.tick(1, 1).operations)
        assertEquals(1L, machine.stateRevision())
        val committed = machine.snapshotState()
        assertNotSame(before, committed)
        assertEquals(AbilityValues.number(1.0), committed["count"])
        for (tick in 2L..4L) assertNull(machine.tick(tick, 1).error)
        assertSame(committed, machine.snapshotState())
        assertEquals(1L, machine.stateRevision())
    }

    @Test fun `no-op stores still consume operation budget and do not suppress effects`() {
        var effects = 0
        val machine = machine(
            "on start { while (true) { state.value = 3 + 4; combat.damage(target, 1); } }",
            mapOf("value" to AbilityValues.number(7.0)),
            maxOperations = 128,
            host = AbilityHost { name, _ -> assertEquals("combat.damage", name); effects++; AbilityValues.bool(true) },
        )
        val snapshot = machine.snapshotState()
        var operations = 0
        for (tick in 0L..31L) {
            operations += machine.tick(tick, 8).operations
            if (machine.isStopped()) break
        }
        assertEquals(128, operations)
        assertTrue(machine.failure()!!.contains("operation budget"))
        assertTrue(effects > 0)
        assertEquals(0L, machine.stateRevision())
        assertSame(snapshot, machine.snapshotState())
        val stoppedEffects = effects
        assertEquals(0, machine.tick(32).operations)
        assertEquals(stoppedEffects, effects)
    }

    @Test fun `events cancellation and failed effects do not advance revision by themselves`() {
        var effects = 0
        val machine = machine(
            "on start { state.count = 1; } on signal { state.count = 1; combat.damage(target, 1); }",
            host = AbilityHost { _, _ -> effects++; throw IllegalStateException("effect already retired") },
        )
        assertNull(machine.tick(0).error)
        val snapshot = machine.snapshotState()
        assertEquals(1L, machine.stateRevision())
        assertTrue(machine.emit("signal", emptyMap()))
        assertEquals(1L, machine.stateRevision())
        assertNotNull(machine.tick(1).error)
        assertEquals(1, effects)
        assertEquals(1L, machine.stateRevision())
        assertSame(snapshot, machine.snapshotState())
        machine.cancel("owner unloaded")
        assertEquals(1L, machine.stateRevision())
        assertSame(snapshot, machine.snapshotState())
        assertEquals(0, machine.tick(2).operations)
        assertEquals(1, effects)
    }

    @Test fun `repeatable snapshot workload avoids allocation and persistence on unchanged large state`() {
        val text = "x".repeat(4096)
        val initial = (0 until 15).associate { "k$it" to AbilityValues.text(text) } + ("count" to AbilityValues.number(7.0))
        var effects = 0
        val machine = machine(
            "on start { repeat 256 { state.count = 3 + 4; fx.message(\"tick\"); wait 1; } }",
            initial,
            host = AbilityHost { name, _ -> assertEquals("fx.message", name); effects++; AbilityValues.bool(true) },
        )
        val uniqueSnapshots = Collections.newSetFromMap(IdentityHashMap<Map<String, AbilityValue>, Boolean>())
        var persistedRevision = -1L
        var serializedWrites = 0
        var reads = 0
        var operations = 0
        for (tick in 0L..256L) {
            val result = machine.tick(tick)
            assertNull(result.error)
            operations += result.operations
            repeat(128) { uniqueSnapshots += machine.snapshotState(); reads++ }
            if (machine.stateRevision() != persistedRevision) {
                assertEquals(initial, AbilityValues.decodeState(AbilityValues.encodeState(machine.snapshotState())))
                persistedRevision = machine.stateRevision()
                serializedWrites++
            }
        }
        assertTrue(machine.isIdle())
        assertEquals(256, effects)
        assertTrue(operations > effects, "No-op STORE_STATE instructions still participate in execution budgeting")
        assertEquals(32896, reads)
        assertEquals(1, uniqueSnapshots.size)
        assertEquals(1, serializedWrites)
        assertEquals(0L, machine.stateRevision())
        println("Ability snapshot workload: ticks=257, snapshotReads=$reads, distinctSnapshots=${uniqueSnapshots.size}, serializedWrites=$serializedWrites, effects=$effects, operations=$operations, revision=${machine.stateRevision()}")
    }
}
