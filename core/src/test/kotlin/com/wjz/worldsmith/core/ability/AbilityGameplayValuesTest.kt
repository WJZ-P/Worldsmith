package com.wjz.worldsmith.core.ability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityGameplayValuesTest {
    private fun run(source: String): AbilityMachine {
        val machine = AbilityMachine(AbilityCompiler.compile(AbilityProgramDefinition("map_test", "Maps", source)), AbilityHost { _, _ -> error("Pure map must not invoke a native host") }, emptyMap())
        machine.tick(0,8192)
        return machine
    }
    @Test fun `map composition supports costs guards and rich event data without mutation`() {
        val machine=run("on start { let original = map.of([[\"mana\", 5], [\"focus\", 2]]); let changed = map.put(original, \"mana\", 9); state.old = map.get(original, \"mana\"); state.changed = map.get(changed, \"mana\"); state.keys = map.keys(changed); }")
        assertNull(machine.failure()); assertEquals(AbilityValues.number(5.0),machine.snapshotState()["old"]); assertEquals(AbilityValues.number(9.0),machine.snapshotState()["changed"])
        assertEquals(AbilityValues.list(listOf(AbilityValues.text("mana"),AbilityValues.text("focus"))),machine.snapshotState()["keys"])
    }
    @Test fun `present null differs from an absent key`() {
        val machine=run("on start { let value = map.of([[\"x\", null]]); state.present = map.has(value, \"x\"); state.absent = map.has(value, \"y\"); state.value = map.get(value, \"y\"); }")
        assertNull(machine.failure()); assertEquals(AbilityValues.bool(true),machine.snapshotState()["present"]); assertEquals(AbilityValues.bool(false),machine.snapshotState()["absent"])
    }
    @Test fun `duplicate malformed and non-map inputs are explicit source failures`() {
        for (source in listOf("map.of([[\"x\", 1], [\"x\", 2]])", "map.of([[\"x\"]])", "map.get(3, \"x\")")) assertNotNull(run("on start { state.result = $source; }").failure())
    }
    @Test fun `args input is available to compiled child source`() {
        val program=AbilityCompiler.compile(AbilityProgramDefinition("args_test","Args","on start { state.payload = map.get(args, \"count\"); }"))
        val machine=AbilityMachine(program,AbilityHost { _, _ -> error("No native calls") },mapOf("args" to AbilityValues.map(mapOf("count" to AbilityValues.number(3.0)))))
        machine.tick(0); assertNull(machine.failure()); assertEquals(AbilityValues.number(3.0),machine.snapshotState()["payload"])
    }
}
