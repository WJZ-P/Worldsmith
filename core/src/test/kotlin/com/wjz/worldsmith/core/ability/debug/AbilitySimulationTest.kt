package com.wjz.worldsmith.core.ability.debug

import com.wjz.worldsmith.core.ability.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilitySimulationTest {
    private val inputs = mapOf("self" to AbilityValues.entity("caster"), "target" to AbilityValues.entity("victim"), "origin" to AbilityValues.vector(0.0, 64.0, 0.0))
    private fun request(source: String, responses: List<AbilitySimulationResponse> = emptyList(), ticks: Int = 20) = AbilitySimulationRequest(
        inputs, program = AbilityProgramDefinition("debug_test", "Debug test", source), responses = responses, ticks = ticks,
    )

    @Test fun `real source functions waits and capabilities produce deterministic located traces`() {
        val source = """
            fn strike(center) {
                for victim in world.entities(shape.sphere(center, 2)) {
                    if (world.visible(self, victim)) {
                        combat.damage(victim, 3);
                    }
                }
            }
            on start { wait 2; strike(origin); state.done = true; }
        """.trimIndent()
        val fixtures = listOf(
            AbilitySimulationResponse("world.entities", AbilityValues.list(listOf(inputs.getValue("target"))), tick = 2),
            AbilitySimulationResponse("world.visible", AbilityValues.bool(true), arguments = listOf(inputs.getValue("self"), inputs.getValue("target")), tick = 2),
            AbilitySimulationResponse("combat.damage", AbilityValues.bool(true), arguments = listOf(inputs.getValue("target"), AbilityValues.number(3.0)), tick = 2),
        )
        val result = AbilitySimulator.run(request(source, fixtures))
        assertEquals(AbilitySimulationStatus.COMPLETED, result.status, result.diagnostics.toString())
        assertEquals(result, AbilitySimulator.run(request(source, fixtures)), "No wall clock or random native state enters simulation")
        assertTrue(result.simulationOnly)
        assertFalse(result.minecraftExecuted)
        assertTrue(result.explanation.contains("not Minecraft"))
        assertEquals(AbilityValues.bool(true), result.finalState["done"])
        assertEquals(3, result.hostCalls.size)
        val call = result.hostCalls.single { it.capability == "combat.damage" }
        assertEquals(2, call.tick)
        assertEquals(4, call.location!!.line)
        assertEquals("strike", call.location!!.function)
        assertEquals(2, call.location!!.callDepth)
        assertEquals(1, result.capabilityCalls["combat.damage"])
        assertEquals(1, result.capabilityCalls["shape.sphere"])
        assertTrue(result.trace.any { it.kind == AbilityTraceKind.WAIT && it.wakeAt == 2L })
        assertTrue(result.trace.any { it.kind == AbilityTraceKind.STATE_WRITE && it.name == "done" && it.location.stateRevision == 1L })
        assertTrue(result.trace.any { it.kind == AbilityTraceKind.CAPABILITY_RETURN && it.name == "combat.damage" && it.location.line == 4 })
    }

    @Test fun `missing native query or effect is reported and never receives default success`() {
        for (source in listOf("on start { state.position = entity.position(target); }", "on start { combat.damage(target, 8); state.hit = true; }")) {
            val result = AbilitySimulator.run(request(source))
            assertEquals(AbilitySimulationStatus.HOST_INPUT_REQUIRED, result.status)
            assertEquals(1, result.hostCalls.size)
            assertEquals("MISSING_RESPONSE", result.hostExpectations.single().code)
            assertTrue(result.finalState.isEmpty())
            assertTrue(result.stopped)
            assertTrue(result.trace.any { it.kind == AbilityTraceKind.CAPABILITY_FAILURE })
            assertEquals(1, result.diagnostics.single().line)
        }
    }

    @Test fun `wrong response ordering arguments or tick stops without consuming the fixture`() {
        val correctArgs = listOf(inputs.getValue("target"), AbilityValues.number(8.0))
        val wrong = listOf(
            AbilitySimulationResponse("combat.heal", AbilityValues.bool(true)),
            AbilitySimulationResponse("combat.damage", AbilityValues.bool(true), arguments = listOf(inputs.getValue("target"), AbilityValues.number(9.0))),
            AbilitySimulationResponse("combat.damage", AbilityValues.bool(true), arguments = correctArgs, tick = 1),
        )
        for (response in wrong) {
            val result = AbilitySimulator.run(request("on start { combat.damage(target, 8); }", listOf(response)))
            assertEquals(AbilitySimulationStatus.HOST_INPUT_REQUIRED, result.status)
            assertEquals(listOf("RESPONSE_MISMATCH", "UNUSED_RESPONSE"), result.hostExpectations.map { it.code })
            assertNull(result.hostCalls.single().responseIndex)
        }
    }

    @Test fun `fixture host exceptions and wrong result types are terminal without replay`() {
        val cases = listOf(
            AbilitySimulationResponse("combat.damage", error = "effect fixture failed"),
            AbilitySimulationResponse("combat.damage", result = AbilityValues.number(1.0)),
        )
        for (response in cases) {
            val result = AbilitySimulator.run(request("on start { state.before = true; combat.damage(target, 8); combat.damage(target, 9); }", listOf(response)))
            assertEquals(AbilitySimulationStatus.FAILED, result.status)
            assertEquals(1, result.hostCalls.size)
            assertEquals(1, result.capabilityCalls["combat.damage"])
            assertEquals(0, result.hostCalls.single().responseIndex)
            assertEquals(AbilityValues.bool(true), result.finalState["before"])
            assertTrue(result.hostExpectations.isEmpty())
            assertTrue(result.trace.any { it.kind == AbilityTraceKind.CAPABILITY_FAILURE })
        }
    }

    @Test fun `scheduled hurt preempts an exact-wake continuation with source evidence`() {
        val source = "on start { state.interrupted = false; wait 5; if (!state.interrupted) { combat.damage(target, 8); } } on hurt { state.interrupted = true; }"
        val result = AbilitySimulator.run(request(source).copy(events = listOf(AbilitySimulationEvent(5, "hurt", mapOf("event_amount" to AbilityValues.number(2.0))))))
        assertEquals(AbilitySimulationStatus.COMPLETED, result.status)
        assertEquals(AbilityValues.bool(true), result.finalState["interrupted"])
        assertTrue(result.hostCalls.isEmpty())
        assertTrue(result.events.single().accepted)
        val tickFive = result.trace.filter { it.kind == AbilityTraceKind.INSTRUCTION && it.location.tick == 5L }
        assertEquals("hurt", tickFive.first().location.event)
        assertTrue(tickFive.any { it.location.event == "start" })
    }

    @Test fun `explicit events preserve declaration order without inventing native follow-ups`() {
        val source = "on start { state.values = []; signal.emit(\"ignored-unless-scheduled\", 1); } on signal { state.values = list.append(state.values, event_data); }"
        val request = request(source, listOf(AbilitySimulationResponse("signal.emit", AbilityValues.bool(true)))).copy(events = listOf(
            AbilitySimulationEvent(2, "signal", mapOf("event_data" to AbilityValues.number(1.0))),
            AbilitySimulationEvent(2, "signal", mapOf("event_data" to AbilityValues.number(2.0))),
        ))
        val result = AbilitySimulator.run(request)
        assertEquals(AbilitySimulationStatus.COMPLETED, result.status)
        assertEquals(AbilityValues.list(listOf(AbilityValues.number(1.0), AbilityValues.number(2.0))), result.finalState["values"])
        assertEquals(listOf(0, 1), result.events.map { it.index })
        val unscheduled = AbilitySimulator.run(request.copy(events = emptyList()))
        assertEquals(AbilityValues.list(emptyList()), unscheduled.finalState["values"])
    }

    @Test fun `unused responses rejected handlers and queue overflow remain visible`() {
        val result = AbilitySimulator.run(request("on start { wait 10; } on signal { wait 10; }",
            listOf(AbilitySimulationResponse("fx.message", AbilityValues.bool(true)))).copy(events =
            listOf(AbilitySimulationEvent(1, "missing")) + List(33) { AbilitySimulationEvent(1, "signal") }))
        assertEquals(AbilitySimulationStatus.EXPECTATIONS_UNMET, result.status)
        assertEquals("UNUSED_RESPONSE", result.hostExpectations.single().code)
        assertFalse(result.events.first().accepted)
        assertEquals(3, result.events.count { !it.accepted }, "One missing handler plus two entries beyond active start + 31 event fibers")
    }

    @Test fun `operation lifetime and requested tick limits are distinguished from completion`() {
        val hotLoop = request("on start { while (true) { } }").let { it.copy(program = it.program!!.copy(maxOperations = 128), operationsPerTick = 7, ticks = 100) }
        val operation = AbilitySimulator.run(hotLoop)
        assertEquals(AbilitySimulationStatus.FAILED, operation.status)
        assertEquals(128, operation.budget.operationsExecuted)
        assertTrue(operation.diagnostics.single().message.contains("operation budget"))
        val lifetime = AbilitySimulator.run(request("on start { wait 100; }").let { it.copy(program = it.program!!.copy(maxTicks = 3)) })
        assertEquals(AbilitySimulationStatus.FAILED, lifetime.status)
        assertTrue(lifetime.diagnostics.single().message.contains("lifetime budget"))
        val limited = AbilitySimulator.run(request("on start { wait 100; }", ticks = 3))
        assertEquals(AbilitySimulationStatus.TICK_LIMIT, limited.status)
        assertTrue(limited.budget.tickLimitReached)
        assertEquals(3, limited.budget.ticksExecuted)
        assertFalse(limited.stopped)
    }

    @Test fun `trace clipping never changes execution and capability totals remain complete`() {
        val fullRequest = request("on start { repeat 80 { state.counter = math.max(3, 4); } }").copy(ticks = 100)
        val full = AbilitySimulator.run(fullRequest)
        val clipped = AbilitySimulator.run(fullRequest.copy(traceLimit = 2))
        val noInstructions = AbilitySimulator.run(fullRequest.copy(traceLimit = 0))
        assertEquals(AbilitySimulationStatus.COMPLETED, full.status)
        assertEquals(full.finalState, clipped.finalState)
        assertEquals(full.finalState, noInstructions.finalState)
        assertEquals(full.budget.operationsExecuted, clipped.budget.operationsExecuted)
        assertEquals(2, clipped.trace.size)
        assertTrue(clipped.budget.droppedTraceEntries > 0)
        assertTrue(noInstructions.trace.isEmpty())
        assertEquals(80, clipped.capabilityCalls["math.max"])
        assertEquals(full.capabilityCalls, noInstructions.capabilityCalls)
    }

    @Test fun `shared trace-byte cap bounds verbose host reports while keeping exact totals`() {
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("test.verbose", arguments = List(16) { AbilityType.TEXT }, result = AbilityType.BOOL))
        val source = "on start { let text = \"${"x".repeat(4096)}\"; repeat 100 { test.verbose(${List(16) { "text" }.joinToString(",")}); } }"
        val response = AbilitySimulationResponse("test.verbose", AbilityValues.bool(true))
        val result = AbilitySimulator.run(request(source, List(100) { response }, ticks = 100).copy(traceLimit = 0), registry)
        assertEquals(AbilitySimulationStatus.COMPLETED, result.status)
        assertEquals(100, result.budget.hostCallsExecuted)
        assertEquals(100, result.capabilityCalls["test.verbose"])
        assertTrue(result.budget.droppedHostCallReports > 0)
        assertEquals(100, result.hostCalls.size + result.budget.droppedHostCallReports)
        assertTrue(result.budget.traceBytes + result.budget.hostCallReportBytes <= AbilitySimulator.MAX_TRACE_BYTES)
        assertTrue(result.hostCalls.flatMap { it.arguments }.all { it.preview.length <= 256 && it.truncated })
    }

    @Test fun `library selection and custom host signatures do not need VM branches`() {
        val spec = AbilityCapabilitySpec("plugin.sample", version = 2, arguments = emptyList(), result = AbilityType.NUMBER)
        val registry = AbilityCapabilities.standard().extend(spec)
        val selected = AbilityProgramDefinition("selected", "Selected", "on start { state.sample = plugin.sample(); }", requires = mapOf("plugin.sample" to 2))
        val library = AbilityLibrary(programs = listOf(AbilityProgramDefinition("unused", "Unused", "not compiled in this selected-program test"), selected))
        val result = AbilitySimulator.run(AbilitySimulationRequest(inputs, library = library, programId = "selected", responses = listOf(AbilitySimulationResponse("plugin.sample", AbilityValues.number(8.0)))), registry)
        assertEquals(AbilitySimulationStatus.COMPLETED, result.status)
        assertEquals(mapOf("plugin.sample" to 2), result.usedCapabilities)
        assertEquals(AbilityValues.number(8.0), result.finalState["sample"])
    }

    @Test fun `compile diagnostics carry actual source position instead of claiming execution`() {
        val result = AbilitySimulator.run(request("on start {\n  missing.capability();\n}"))
        assertEquals(AbilitySimulationStatus.COMPILE_ERROR, result.status)
        assertEquals(2, result.diagnostics.single().line)
        assertEquals(0, result.budget.operationsExecuted)
        assertTrue(result.trace.isEmpty())
        assertFalse(result.minecraftExecuted)
    }

    @Test fun `simulation validates fixture bounds source choice and exact input types`() {
        val base = request("on start {}")
        val bad = listOf(
            base.copy(program = null), base.copy(library = AbilityLibrary()), base.copy(inputs = emptyMap()),
            base.copy(inputs = inputs + ("self" to AbilityValues.none())), base.copy(ticks = 12001), base.copy(operationsPerTick = 0),
            base.copy(traceLimit = 2049), base.copy(events = List(129) { AbilitySimulationEvent(0, "hurt") }),
            base.copy(events = listOf(AbilitySimulationEvent(20, "hurt"))),
            base.copy(responses = listOf(AbilitySimulationResponse("math.abs", AbilityValues.number(1.0)))),
            base.copy(responses = List(257) { AbilitySimulationResponse("fx.message", AbilityValues.bool(true)) }),
            base.copy(responses = listOf(AbilitySimulationResponse("fx.message", AbilityValues.bool(true), error = "both error and result"))),
            base.copy(responses = List(64) { AbilitySimulationResponse("entity.kind", AbilityValues.text("x".repeat(4096))) }),
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { AbilitySimulator.run(it) } }
    }

    @Test fun `trace views are bounded for deeply nested large values and preserve simple coordinates`() {
        val leaf = AbilityValues.text("x".repeat(4096))
        val many = AbilityValues.list(List(32) { AbilityValues.list(List(32) { leaf }) })
        val preview = AbilityTraceViews.value(many)
        assertEquals("LIST", preview.type)
        assertEquals(32, preview.size)
        assertTrue(preview.truncated)
        assertTrue(preview.preview.length <= 256)
        val vector = AbilityTraceViews.value(AbilityValues.vector(1.0, 64.0, 3.0))
        assertEquals("vec(1.0, 64.0, 3.0)", vector.preview)
        assertFalse(vector.truncated)
    }

    @Test fun `throwing observer is detached without stopping effects or changing instruction budget`() {
        val compiled = AbilityCompiler.compile(AbilityProgramDefinition("observer", "Observer", "on start { combat.damage(target, 1); state.done = true; }"))
        var traceCalls = 0
        var effects = 0
        val traced = AbilityMachine(compiled, AbilityHost { _, _ -> effects++; AbilityValues.bool(true) }, inputs, emptyMap(), AbilityTraceListener {
            traceCalls++; throw IllegalStateException("broken observer")
        })
        val baseline = AbilityMachine(compiled, AbilityHost { _, _ -> AbilityValues.bool(true) }, inputs)
        val tracedTick = traced.tick(0)
        val baselineTick = baseline.tick(0)
        assertNull(tracedTick.error)
        assertEquals(1, traceCalls)
        assertEquals(1, effects)
        assertEquals(baselineTick, tracedTick)
        assertEquals(baseline.snapshotState(), traced.snapshotState())
    }

    @Test fun `owning thread can opt into and stop observation of a waiting live instance`() {
        val compiled = AbilityCompiler.compile(AbilityProgramDefinition("live_trace", "Live trace", "on start { state.stage = 1; wait 2; combat.damage(target, 1); state.stage = 2; } on signal { combat.damage(target, 1); }"))
        var effects = 0
        val machine = AbilityMachine(compiled, AbilityHost { _, _ -> effects++; AbilityValues.bool(true) }, inputs)
        assertNull(machine.tick(0).error)
        val events = mutableListOf<AbilityTraceEvent>()
        machine.setTraceListener(AbilityTraceListener { events += it })
        assertNull(machine.tick(1).error)
        assertTrue(events.isEmpty())
        assertNull(machine.tick(2).error)
        assertEquals(1, effects)
        assertEquals(2L, machine.stateRevision())
        assertTrue(events.any { it.kind == AbilityTraceKind.CAPABILITY_CALL && it.name == "combat.damage" })
        assertTrue(events.all { it.location.tick == 2L })
        val observed = events.size
        machine.setTraceListener(null)
        assertTrue(machine.emit("signal", emptyMap()))
        assertNull(machine.tick(3).error)
        assertEquals(2, effects)
        assertEquals(observed, events.size)
    }
}
