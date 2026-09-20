package com.wjz.worldsmith.core.ability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityRuntimeTest {
    private val inputs = mapOf("self" to AbilityValues.entity("actor"), "target" to AbilityValues.entity("victim"), "origin" to AbilityValues.vector(0.0, 64.0, 0.0))
    private fun definition(source: String, maxOperations: Int = 32768, maxTicks: Int = 1200) = AbilityProgramDefinition("test_program", "Test program", source, maxTicks, maxOperations)
    private fun compile(source: String) = AbilityCompiler.compile(definition(source))
    private fun machine(source: String, host: AbilityHost = AbilityHost { _, _ -> AbilityValues.bool(true) }) = AbilityMachine(compile(source), host, inputs)
    private fun finish(machine: AbilityMachine, quota: Int = 128, lastTick: Long = 1200) {
        for (tick in 0..lastTick) {
            val result = machine.tick(tick, quota)
            assertNull(result.error, result.error)
            if (machine.isIdle()) return
        }
        fail<Unit>("Program did not finish.")
    }

    @Test fun `constructor only queues start and does not dispatch effects`() {
        val calls = mutableListOf<String>()
        val machine = machine("on start { combat.damage(target, 6); }", AbilityHost { name, _ -> calls += name; AbilityValues.bool(true) })
        assertTrue(calls.isEmpty())
        assertFalse(machine.isIdle())
        assertNull(machine.tick(0).error)
        assertEquals(listOf("combat.damage"), calls)
        assertTrue(machine.isIdle())
    }

    @Test fun `source functions variables branches loops and list iteration compose`() {
        val machine = machine("""
            fn multiply(a, b) { return a * b; }
            on start {
                let sum = 0;
                let numbers = [];
                repeat 3 { numbers = list.append(numbers, 2); }
                for n in numbers { sum = sum + multiply(n, 3); }
                let counter = 0;
                while (counter < 4) { counter = counter + 1; }
                if (sum == 18 && counter == 4) { state.result = sum; }
                else { state.result = -1; }
            }
        """.trimIndent())
        finish(machine, 7)
        assertEquals(AbilityValues.number(18.0), machine.snapshotState()["result"])
    }

    @Test fun `function frames and expression stack survive wait and tiny quota`() {
        val machine = machine("""
            fn delayed(n) { wait 3; return n * 2; }
            fn outer(n) { return 1 + delayed(n); }
            on start { state.result = 5 + outer(4); }
        """.trimIndent())
        finish(machine, 1)
        assertEquals(AbilityValues.number(14.0), machine.snapshotState()["result"])
    }

    @Test fun `recursive functions are valid and bounded by runtime frame depth`() {
        val machine = machine("fn factorial(n) { if (n <= 1) { return 1; } return n * factorial(n - 1); } on start { state.result = factorial(5); }")
        finish(machine)
        assertEquals(AbilityValues.number(120.0), machine.snapshotState()["result"])
        val recursive = machine("fn recurse() { return recurse(); } on start { recurse(); }")
        assertTrue(recursive.tick(0, 1024).error!!.contains("call depth"))
        assertTrue(recursive.isStopped())
    }

    @Test fun `arithmetic precedence comparison unary and comments are preserved`() {
        val machine = machine("""
            /* functions and arithmetic are source, not move presets */
            on start {
                let result = 2 + 3 * 4 - 8 / 2 + 7 % 4;
                // Unary and short circuit share normal precedence.
                if (!(result != 13) && -2 < +3) { state.result = result; }
                state.zero = -0 == 0;
                state.literal = "line\\name\nnext";
            }
        """.trimIndent())
        finish(machine)
        assertEquals(AbilityValues.number(13.0), machine.snapshotState()["result"])
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["zero"])
        assertEquals(AbilityValues.text("line\\name\nnext"), machine.snapshotState()["literal"])
    }

    @Test fun `logical operators skip unchosen effectful operands`() {
        var effects = 0
        val machine = machine("on start { let a = false && combat.damage(target, 9); let b = true || combat.damage(target, 9); state.result = a == false && b == true; }", AbilityHost { _, _ -> effects++; AbilityValues.bool(true) })
        finish(machine, 1)
        assertEquals(0, effects)
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["result"])
    }

    @Test fun `executed dynamic logical right operands must be boolean`() {
        for (expression in listOf("true && value", "false || value")) {
            val machine = machine("on start { let value = 2; state.invalid = $expression; }")
            assertTrue(machine.tick(0).error!!.contains("expects BOOL"))
            assertFalse("invalid" in machine.snapshotState())
        }
        val shortCircuit = machine("on start { let value = 2; state.a = false && value; state.b = true || value; }")
        finish(shortCircuit)
        assertEquals(AbilityValues.bool(false), shortCircuit.snapshotState()["a"])
        assertEquals(AbilityValues.bool(true), shortCircuit.snapshotState()["b"])
    }

    @Test fun `event handler interrupts a waiting continuation at exact wake tick`() {
        var effects = 0
        val machine = machine("""
            on start { state.interrupted = false; wait 10; if (!state.interrupted) { combat.damage(target, 20); } }
            on hurt { state.interrupted = true; state.amount = event_amount; }
        """.trimIndent(), AbilityHost { _, _ -> effects++; AbilityValues.bool(true) })
        machine.tick(0)
        assertTrue(machine.emit("hurt", mapOf("event_amount" to AbilityValues.number(3.0))))
        machine.tick(10)
        assertEquals(0, effects)
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["interrupted"])
        assertEquals(AbilityValues.number(3.0), machine.snapshotState()["amount"])
    }

    @Test fun `queued event fibers have separate locals and preserve fifo order`() {
        val machine = machine("on start { state.events = []; } on signal { let payload = event_data; wait 1; state.events = list.append(state.events, payload); }")
        machine.tick(0)
        machine.emit("signal", mapOf("event_data" to AbilityValues.number(1.0)))
        machine.emit("signal", mapOf("event_data" to AbilityValues.number(2.0)))
        machine.tick(1)
        machine.tick(2)
        assertEquals(AbilityValues.list(listOf(AbilityValues.number(1.0), AbilityValues.number(2.0))), machine.snapshotState()["events"])
    }

    @Test fun `event priority survives quota suspension between the load and state write`() {
        var effects = 0
        val machine = machine("on start { state.interrupted = false; wait 10; if (!state.interrupted) { combat.damage(target, 9); } } on hurt { state.interrupted = true; }", AbilityHost { _, _ -> effects++; AbilityValues.bool(true) })
        machine.tick(0)
        machine.emit("hurt", emptyMap())
        for (tick in 10L..40L) machine.tick(tick, 1)
        assertEquals(0, effects)
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["interrupted"])
    }

    @Test fun `null inputs unknown state and omitted event values are explicit null`() {
        val machine = AbilityMachine(compile("on start { state.empty = state.missing == null; } on signal { state.payload = event_entity == null && event_name == \"signal\"; }"), AbilityHost { _, _ -> fail("Unexpected host call") }, emptyMap())
        machine.tick(0)
        machine.emit("signal", emptyMap())
        machine.tick(1)
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["empty"])
        assertEquals(AbilityValues.bool(true), machine.snapshotState()["payload"])
        assertFalse(machine.emit("signal", mapOf("self" to AbilityValues.entity("spoof"))))
    }

    @Test fun `host exception terminates without repeating a retired effect`() {
        var effects = 0
        val machine = machine("on start { combat.damage(target, 6); combat.damage(target, 7); }", AbilityHost { _, _ -> effects++; throw IllegalStateException("dispatch broke after applying effect") })
        val first = machine.tick(0)
        assertTrue(first.error!!.contains("dispatch broke"))
        assertTrue(first.error!!.contains("line 1, column"))
        repeat(3) { assertEquals(0, machine.tick(it.toLong() + 1).operations) }
        assertEquals(1, effects)
    }

    @Test fun `type or arithmetic failure does not undo an earlier effect`() {
        var effects = 0
        val machine = machine("on start { combat.damage(target, 6); let zero = 0; state.value = 1 / zero; combat.damage(target, 7); }", AbilityHost { _, _ -> effects++; AbilityValues.bool(true) })
        assertTrue(machine.tick(0).error!!.contains("Division by zero"))
        assertEquals(1, effects)
        assertFalse(machine.snapshotState().containsKey("value"))
    }

    @Test fun `dynamic argument types and callback result types are checked`() {
        val invalidArgument = machine("on start { let value = \"wrong\"; combat.damage(target, value); }")
        assertTrue(invalidArgument.tick(0).error!!.contains("expects NUMBER"))
        val invalidResult = machine("on start { combat.damage(target, 6); }", AbilityHost { _, _ -> AbilityValues.text("wrong") })
        assertTrue(invalidResult.tick(0).error!!.contains("Result of 'combat.damage' expects BOOL"))
    }

    @Test fun `unbounded loops yield per tick then stop at total operation limit`() {
        val program = AbilityCompiler.compile(definition("on start { while (true) { } }", maxOperations = 128))
        val machine = AbilityMachine(program, AbilityHost { _, _ -> fail("Unexpected host call") }, inputs)
        assertEquals(7, machine.tick(0, 7).operations)
        assertFalse(machine.isStopped())
        var total = 7
        for (tick in 1L..30L) {
            total += machine.tick(tick, 7).operations
            if (machine.isStopped()) break
        }
        assertEquals(128, total)
        assertTrue(machine.failure()!!.contains("operation budget"))
    }

    @Test fun `wait zero yields and lifetime budget includes sleeping time`() {
        val machine = machine("on start { state.stage = 1; wait 0; state.stage = 2; }")
        machine.tick(0)
        assertEquals(AbilityValues.number(1.0), machine.snapshotState()["stage"])
        machine.tick(1)
        assertEquals(AbilityValues.number(2.0), machine.snapshotState()["stage"])
        val limited = AbilityMachine(AbilityCompiler.compile(definition("on start { wait 100; }", maxTicks = 3)), AbilityHost { _, _ -> AbilityValues.none() }, inputs)
        limited.tick(10)
        assertTrue(limited.tick(13).error!!.contains("lifetime budget"))
    }

    @Test fun `cancel discards callbacks and prevents late effects`() {
        var effects = 0
        val machine = machine("on start { wait 5; combat.damage(target, 6); } on signal { combat.damage(target, 7); }", AbilityHost { _, _ -> effects++; AbilityValues.bool(true) })
        machine.tick(0)
        machine.emit("signal", emptyMap())
        machine.cancel("owner unloaded")
        assertTrue(machine.isStopped())
        assertFalse(machine.emit("signal", emptyMap()))
        assertEquals(0, machine.tick(10).operations)
        assertEquals(0, effects)
    }

    @Test fun `idle machine can accept callbacks without rerunning start`() {
        val machine = machine("on start { state.count = 0; } on signal { state.count = state.count + 1; }")
        machine.tick(0)
        assertTrue(machine.isIdle())
        assertTrue(machine.emit("signal", emptyMap()))
        machine.tick(1)
        assertEquals(AbilityValues.number(1.0), machine.snapshotState()["count"])
    }

    @Test fun `pending events and active sleeping fibers share bounded occupancy`() {
        val machine = machine("on start { wait 100; } on signal { wait 100; }")
        machine.tick(0)
        repeat(31) { assertTrue(machine.emit("signal", emptyMap())) }
        assertFalse(machine.emit("signal", emptyMap()))
        machine.tick(1, 8192)
        assertFalse(machine.emit("signal", emptyMap()))
    }

    @Test fun `host can emit a bounded signal without reentrant ticking`() {
        lateinit var machine: AbilityMachine
        machine = machine("on start { signal.emit(\"pulse\", 4); } on signal { state.payload = event_data; }", AbilityHost { _, args ->
            AbilityValues.bool(machine.emit("signal", mapOf("event_tag" to args[0], "event_data" to args[1])))
        })
        finish(machine)
        assertEquals(AbilityValues.number(4.0), machine.snapshotState()["payload"])
    }

    @Test fun `state writes obey cumulative byte and key limits without partially committing`() {
        val initial = (0 until 64).associate { "k$it" to AbilityValues.number(it.toDouble()) }
        val machine = AbilityMachine(compile("on start { state.extra = 1; }"), AbilityHost { _, _ -> AbilityValues.none() }, inputs, initial)
        assertTrue(machine.tick(0).error!!.contains("64 entries"))
        assertEquals(initial, machine.snapshotState())
    }

    @Test fun `incremental state replacement releases old byte cost before accepting another entry`() {
        val text = "x".repeat(4096)
        val initial = (0 until 15).associate { "k$it" to AbilityValues.text(text) }
        val program = compile("on start { state.k0 = \"\"; state.extra = \"$text\"; state.count = 0; repeat 20 { state.count = state.count + 1; } }")
        val machine = AbilityMachine(program, AbilityHost { _, _ -> fail("Unexpected host call") }, inputs, initial)
        finish(machine)
        val expected = initial + mapOf("k0" to AbilityValues.text(""), "extra" to AbilityValues.text(text), "count" to AbilityValues.number(20.0))
        assertEquals(expected, machine.snapshotState())
        assertEquals(AbilityValues.encodeState(expected), AbilityValues.encodeState(machine.snapshotState()))
    }

    @Test fun `incremental byte failure and aggregate node failure leave prior state unchanged`() {
        val text = "x".repeat(4096)
        val initial = (0 until 15).associate { "k$it" to AbilityValues.text(text) }
        val overflow = AbilityMachine(compile("on start { state.extra = \"$text\"; }"), AbilityHost { _, _ -> fail("Unexpected host call") }, inputs, initial)
        assertTrue(overflow.tick(0).error!!.contains("64 KiB"))
        assertEquals(initial, overflow.snapshotState())
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.encodeState(initial + ("extra" to AbilityValues.text(text))) }

        val subtree = AbilityValues.list(List(32) { AbilityValues.list(List(30) { AbilityValues.none() }) })
        val nearNodeLimit = mapOf("first" to subtree, "second" to subtree) // 1 + 993 + 993 nodes.
        val added = AbilityValues.list(List(64) { AbilityValues.none() })
        val nodeOverflow = AbilityMachine(compile("on start { state.extra = [${List(64) { "null" }.joinToString(",")}]; }"), AbilityHost { _, _ -> fail("Unexpected host call") }, inputs, nearNodeLimit)
        assertTrue(nodeOverflow.tick(0).error!!.contains("2048 nodes"))
        assertEquals(nearNodeLimit, nodeOverflow.snapshotState())
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.encodeState(nearNodeLimit + ("extra" to added)) }
    }

    @Test fun `incremental entry depth matches full state-map depth`() {
        var nested = AbilityValues.none()
        repeat(8) { nested = AbilityValues.list(listOf(nested)) } // Value depth 8, state depth 9.
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("test.deep", arguments = emptyList(), result = AbilityType.ANY))
        val compiled = AbilityCompiler.compile(definition("on start { state.deep = test.deep(); }"), registry)
        val machine = AbilityMachine(compiled, AbilityHost { _, _ -> nested }, inputs)
        assertTrue(machine.tick(0).error!!.contains("nesting exceeds 8"))
        assertTrue(machine.snapshotState().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { AbilityValues.encodeState(mapOf("deep" to nested)) }
    }

    @Test fun `extensions dispatch through registry and version requirements without VM changes`() {
        val spec = AbilityCapabilitySpec("mod.weather", version = 2, arguments = listOf(AbilityType.VECTOR), result = AbilityType.NUMBER)
        val registry = AbilityCapabilities.standard().extend(spec)
        val program = definition("on start { state.weather = mod.weather(origin); }").copy(requires = mapOf("mod.weather" to 2))
        assertThrows(IllegalArgumentException::class.java) { AbilityCompiler.compile(program) }
        assertThrows(IllegalArgumentException::class.java) { AbilityCompiler.compile(program.copy(requires = emptyMap()), registry) }
        val compiled = AbilityCompiler.compile(program, registry)
        assertEquals(mapOf("mod.weather" to 2), compiled.usedCapabilities)
        val machine = AbilityMachine(compiled, AbilityHost { name, args ->
            assertEquals("mod.weather", name); assertEquals(inputs["origin"], args[0]); AbilityValues.number(7.0)
        }, inputs)
        finish(machine)
        assertEquals(AbilityValues.number(7.0), machine.snapshotState()["weather"])
        assertThrows(IllegalArgumentException::class.java) { registry.extend(spec) }
    }

    @Test fun `pure extension implementation and default functions do not reach the host`() {
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("custom.double", arguments = listOf(AbilityType.NUMBER), result = AbilityType.NUMBER), AbilityPureFunction { AbilityValues.number((it[0] as AbilityValue.NumberValue).value * 2) })
        assertTrue(registry.isPure("custom.double"))
        val program = AbilityCompiler.compile(definition("on start { state.result = custom.double(math.max(2, 3)); state.distance = vector.distance(vec(0, 0, 0), vec(3, 4, 0)); }"), registry)
        val machine = AbilityMachine(program, AbilityHost { _, _ -> fail("Pure call reached host") }, inputs)
        finish(machine)
        assertEquals(AbilityValues.number(6.0), machine.snapshotState()["result"])
        assertEquals(AbilityValues.number(5.0), machine.snapshotState()["distance"])
    }

    @Test fun `compiler reports bounded source locations for malformed or unsupported source`() {
        val bad = listOf(
            "on start { missing(); }", "on start { combat.damage(target); }", "on start { combat.damage(target, \"bad\"); }",
            "on start { state.a = unknown; }", "on start { state.a.b = 1; }", "on start { let a = ; }",
            "on start { wait 1;", "on hurt { return; }", "on start { let x = 1; let x = 2; }",
            "on start { while (5) { } }", "on start { let self = 1; }", "on start { /* unclosed",
            "on start { state.x = 1e999; }", "on start {} on start {}",
        )
        bad.forEach { source ->
            val error = assertThrows(IllegalArgumentException::class.java) { compile(source) }
            assertTrue(error.message!!.contains("line "), error.message)
            assertTrue(error.message!!.contains("column "), error.message)
            assertTrue(error.message!!.length <= 560)
        }
    }

    @Test fun `compiler validates definitions and freezes source requirements`() {
        val requires = mutableMapOf("combat.damage" to 1)
        val program = AbilityCompiler.compile(definition("on start { combat.damage(target, 1); }").copy(requires = requires))
        requires["combat.damage"] = 2
        assertEquals(1, program.definition.requires["combat.damage"])
        assertThrows(UnsupportedOperationException::class.java) { (program.usedCapabilities as MutableMap)["evil"] = 1 }
        assertTrue(AbilityPrograms.validId("world.echo-v2"))
        assertFalse(AbilityPrograms.validId("bad..path"))
        assertFalse(AbilityPrograms.validId("MixedCase"))
        assertTrue(AbilityPrograms.validate(AbilityLibrary(programs = listOf(program.definition))).isEmpty())
        assertTrue(AbilityPrograms.validate(AbilityLibrary(programs = listOf(program.definition, program.definition))).any { it.code == "ability.duplicate" })
    }
}
