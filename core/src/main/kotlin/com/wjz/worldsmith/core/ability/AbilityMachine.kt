package com.wjz.worldsmith.core.ability

import com.wjz.worldsmith.core.ability.debug.*
import java.util.ArrayDeque
import java.util.Collections

/**
 * Cooperative, server-authoritative VM. A tick quota can suspend at ANY instruction,
 * including inside a user function. An effect instruction retires before host dispatch.
 * This VM does not promise rollback of host effects; a failed invocation is terminal.
 */
class AbilityMachine @JvmOverloads constructor(
    private val compiled: CompiledAbilityProgram,
    private val host: AbilityHost,
    inputs: Map<String, AbilityValue>,
    initialState: Map<String, AbilityValue> = emptyMap(),
    traceListener: AbilityTraceListener? = null,
) {
    private var traceSink = traceListener
    private val inputs = AbilityValues.stateCopy(inputs)
    private val state = LinkedHashMap(AbilityValues.stateCopy(initialState))
    private val stateMetrics = state.mapValues { (key, value) -> AbilityValues.stateEntryMetrics(key, value) }.toMutableMap()
    private var stateNodes = 1 + stateMetrics.values.sumOf { it.nodes }
    private var stateBytes = 2 + stateMetrics.values.sumOf { it.bytes } + maxOf(0, state.size - 1)
    private var revision = 0L
    private var stateSnapshot: Map<String, AbilityValue>? = null
    private val fibers = ArrayDeque<Fiber>()
    private val pending = ArrayDeque<Event>()
    private var stopped = false
    private var error: String? = null
    private var firstTick: Long? = null
    private var previousTick: Long? = null
    private var operations = 0
    private var ticking = false
    private var activeFiber: Fiber? = null

    private class Frame(val code: AbilityCode, arguments: List<AbilityValue>) {
        var pc = 0
        val locals = MutableList<AbilityValue>(code.localCount) { AbilityValues.none() }.also { values -> arguments.forEachIndexed { i, value -> values[i] = value } }
        val stack = ArrayList<AbilityValue>()
        fun push(value: AbilityValue) {
            require(stack.size < 256) { "Expression stack exceeds 256 values." }
            stack.add(value)
        }
        fun pop(): AbilityValue {
            check(stack.isNotEmpty()) { "Invalid bytecode stack." }
            return stack.removeAt(stack.lastIndex)
        }
    }
    private class Fiber(code: AbilityCode, val event: Map<String, AbilityValue>) {
        val frames = ArrayDeque<Frame>().apply { addLast(Frame(code, emptyList())) }
        var wakeAt = Long.MIN_VALUE
    }
    private data class Event(val name: String, val payload: Map<String, AbilityValue>)

    init { pending.addLast(Event("start", emptyMap())) }

    /** Unknown events return false, and queue/fiber occupancy is bounded at 32. */
    fun emit(event: String, payload: Map<String, AbilityValue>): Boolean {
        if (stopped || event !in compiled.handlers || pending.size + fibers.size + (if (activeFiber == null) 0 else 1) >= 32) return false
        // Event payload never replaces actor inputs; event-only fields are deliberately named.
        if (payload.keys.any { it !in eventFields }) return false
        return try {
            pending.addLast(Event(event, AbilityValues.stateCopy(payload)))
            true
        } catch (_: IllegalArgumentException) { false }
    }

    /** Initial/restored state is revision zero; only a committed structural change advances it. */
    fun stateRevision(): Long = revision

    /** Owning simulation/server thread only. Opt-in observation never restarts a fiber or changes state. */
    fun setTraceListener(listener: AbilityTraceListener?) { traceSink = listener }

    /** Cached per revision. A previously returned snapshot never follows later state writes. */
    fun snapshotState(): Map<String, AbilityValue> = stateSnapshot ?: Collections.unmodifiableMap(LinkedHashMap(state)).also {
        stateSnapshot = it
    }
    fun isIdle(): Boolean = activeFiber == null && fibers.isEmpty() && pending.isEmpty()
    fun isStopped(): Boolean = stopped
    fun failure(): String? = error

    /** Hard cancellation never executes a cleanup handler supplied by an unloaded pack. */
    fun cancel(reason: String) {
        stopped = true
        fibers.clear(); pending.clear(); activeFiber = null
        // The caller owns resource cleanup; cancellation is not a script execution error.
    }

    @JvmOverloads fun tick(now: Long, maxOps: Int = 128): AbilityTickResult {
        require(maxOps in 1..8192) { "Tick operation quota must be 1..8192." }
        check(!ticking) { "AbilityMachine.tick is not reentrant." }
        if (stopped) return AbilityTickResult(0, true, error)
        if (previousTick != null && now < previousTick!!) {
            fail("Ability clock moved backwards."); return AbilityTickResult(0, true, error)
        }
        if (firstTick == null) firstTick = now
        previousTick = now
        if (now >= saturatedAdd(firstTick!!, compiled.definition.maxTicks.toLong())) {
            fail("Ability lifetime budget exhausted (${compiled.definition.maxTicks} ticks).")
            return AbilityTickResult(0, true, error)
        }
        ticking = true
        var spent = 0
        var lastInstruction: AbilityInstruction? = null
        try {
            // A queued hurt/block event gets its turn before a previously waiting continuation.
            admitEvents()
            var deferred = 0
            while (!stopped && spent < maxOps && fibers.isNotEmpty()) {
                val fiber = fibers.removeFirst()
                if (fiber.wakeAt > now) {
                    fibers.addLast(fiber)
                    if (++deferred >= fibers.size) break
                    continue
                }
                deferred = 0
                activeFiber = fiber
                while (!stopped && fiber.frames.isNotEmpty() && fiber.wakeAt <= now && spent < maxOps) {
                    require(operations < compiled.definition.maxOperations) { "Ability operation budget exhausted (${compiled.definition.maxOperations})." }
                    val frame = fiber.frames.peekLast()
                    check(frame.pc in frame.code.instructions.indices) { "Invalid instruction pointer." }
                    val instruction = frame.code.instructions[frame.pc++] // Retired even if a host capability throws.
                    lastInstruction = instruction
                    spent++; operations++
                    trace(fiber, frame, instruction, now, AbilityTraceKind.INSTRUCTION,
                        name = when (val argument = instruction.argument) { is AbilityCall -> argument.name; is String -> argument; else -> null })
                    if (!stopped) execute(fiber, frame, instruction, now)
                }
                activeFiber = null
                if (!stopped && fiber.frames.isNotEmpty()) {
                    // Quota suspension is not a script yield: preserve event-before-continuation
                    // ordering even when a global native budget grants this machine one opcode.
                    if (spent >= maxOps && fiber.wakeAt <= now) fibers.addFirst(fiber) else fibers.addLast(fiber)
                }
                // A signal emitted by host dispatch is processed at a cooperative boundary.
                admitEvents()
            }
            if (!stopped && operations >= compiled.definition.maxOperations && !isIdle()) {
                fail("Ability operation budget exhausted (${compiled.definition.maxOperations}).")
            }
        } catch (e: Exception) {
            val location = lastInstruction?.at
            val prefix = if (location == null) "" else "line ${location.line}, column ${location.column}: "
            fail(prefix + (e.message ?: e.javaClass.simpleName).take(384))
        } finally { activeFiber = null; ticking = false }
        return AbilityTickResult(spent, stopped || isIdle(), error)
    }

    private fun admitEvents() {
        if (pending.isEmpty() || stopped) return
        val incoming = mutableListOf<Fiber>()
        while (pending.isNotEmpty()) {
            val event = pending.removeFirst()
            val code = compiled.handlers[event.name] ?: continue
            incoming += Fiber(code, event.payload + ("event_name" to AbilityValues.text(event.name)))
        }
        for (i in incoming.indices.reversed()) fibers.addFirst(incoming[i])
    }

    private fun fail(message: String) {
        error = message.take(512); stopped = true
        fibers.clear(); pending.clear(); activeFiber = null
    }

    private fun trace(
        fiber: Fiber, frame: Frame, instruction: AbilityInstruction, now: Long, kind: AbilityTraceKind,
        name: String? = null, arguments: List<AbilityValue> = emptyList(), value: AbilityValue? = null,
        error: String? = null, wakeAt: Long? = null,
    ) {
        val sink = traceSink ?: return
        val event = AbilityTraceEvent(kind, AbilityTraceLocation(now, operations,
            (fiber.event["event_name"] as? AbilityValue.TextValue)?.value ?: "start", frame.code.name,
            frame.pc - 1, instruction.at.line, instruction.at.column, fiber.frames.size, revision),
            instruction.op.name, name, if (arguments.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(arguments)),
            value, error?.take(512), wakeAt)
        try { sink.onTrace(event) } catch (_: Exception) { traceSink = null }
    }

    private fun execute(fiber: Fiber, frame: Frame, instruction: AbilityInstruction, now: Long) {
        when (instruction.op) {
            AbilityOp.CONSTANT -> frame.push(instruction.argument as AbilityValue)
            AbilityOp.LOAD_LOCAL -> frame.push(frame.locals[instruction.argument as Int])
            AbilityOp.STORE_LOCAL -> frame.locals[instruction.argument as Int] = frame.pop()
            AbilityOp.LOAD_INPUT -> {
                val name = instruction.argument as String
                frame.push(if (name in eventFields || name == "event_name") fiber.event[name] ?: AbilityValues.none() else inputs[name] ?: AbilityValues.none())
            }
            AbilityOp.LOAD_STATE -> frame.push(state[instruction.argument as String] ?: AbilityValues.none())
            AbilityOp.STORE_STATE -> {
                val key = instruction.argument as String; val value = frame.pop()
                // Values are deeply immutable: structural equality means persistence is unchanged.
                // A missing key is NOT the same as an explicitly stored NullValue.
                if (state[key] == value) return
                val previous = stateMetrics[key]
                require(previous != null || state.size < 64) { "Ability map exceeds 64 entries." }
                val next = AbilityValues.stateEntryMetrics(key, value)
                val nodes = stateNodes - (previous?.nodes ?: 0) + next.nodes
                val bytes = stateBytes - (previous?.bytes ?: 0) + next.bytes + if (previous == null && state.isNotEmpty()) 1 else 0
                require(nodes <= 2048) { "Ability value exceeds 2048 nodes." }
                require(bytes <= 65536) { "Ability state exceeds 64 KiB." }
                // Commit only after ALL limits pass; immutable values make cached metrics stable.
                state[key] = value
                stateMetrics[key] = next
                stateNodes = nodes
                stateBytes = bytes
                revision++
                stateSnapshot = null
                trace(fiber, frame, instruction, now, AbilityTraceKind.STATE_WRITE, name = key, value = value)
            }
            AbilityOp.LIST -> {
                val count = instruction.argument as Int
                val values = (0 until count).map { frame.pop() }.asReversed()
                frame.push(AbilityValues.list(values))
            }
            AbilityOp.POP -> frame.pop()
            AbilityOp.DUP -> { val value = frame.pop(); frame.push(value); frame.push(value) }
            AbilityOp.UNARY -> {
                val value = frame.pop()
                frame.push(when (instruction.argument as String) {
                    "!" -> AbilityValues.bool(!AbilityValues.boolOf(value))
                    "-" -> AbilityValues.number(-AbilityValues.numberOf(value))
                    "+" -> AbilityValues.number(AbilityValues.numberOf(value))
                    else -> error("Invalid unary operator.")
                })
            }
            AbilityOp.BINARY -> {
                val right = frame.pop(); val left = frame.pop()
                frame.push(binary(instruction.argument as String, left, right))
            }
            AbilityOp.JUMP -> frame.pc = instruction.argument as Int
            AbilityOp.JUMP_FALSE -> if (!AbilityValues.boolOf(frame.pop())) frame.pc = instruction.argument as Int
            AbilityOp.JUMP_TRUE -> if (AbilityValues.boolOf(frame.pop())) frame.pc = instruction.argument as Int
            AbilityOp.COUNT -> frame.push(AbilityValues.number(AbilityValues.integerOf(frame.pop(), 0, 1000000000).toDouble()))
            AbilityOp.CHECK -> {
                val value = frame.pop()
                compiled.registry.checkType(value, instruction.argument as AbilityType, "Expression")
                frame.push(value)
            }
            AbilityOp.CALL -> {
                val call = instruction.argument as AbilityCall
                val arguments = (0 until call.count).map { frame.pop() }.asReversed()
                if (call.userFunction) {
                    require(fiber.frames.size < 32) { "Ability call depth exceeds 32." }
                    fiber.frames.addLast(Frame(compiled.functions.getValue(call.name), arguments))
                } else {
                    trace(fiber, frame, instruction, now, AbilityTraceKind.CAPABILITY_CALL, name = call.name, arguments = arguments)
                    val result = try { compiled.registry.invoke(call.name, arguments, host) }
                    catch (e: Exception) {
                        trace(fiber, frame, instruction, now, AbilityTraceKind.CAPABILITY_FAILURE, name = call.name, arguments = arguments,
                            error = e.message ?: e.javaClass.simpleName)
                        throw e
                    }
                    trace(fiber, frame, instruction, now, AbilityTraceKind.CAPABILITY_RETURN, name = call.name, value = result)
                    if (!stopped) frame.push(result)
                }
            }
            AbilityOp.WAIT -> {
                val ticks = AbilityValues.integerOf(frame.pop())
                fiber.wakeAt = saturatedAdd(now, maxOf(1, ticks).toLong())
                trace(fiber, frame, instruction, now, AbilityTraceKind.WAIT, wakeAt = fiber.wakeAt)
            }
            AbilityOp.RETURN -> {
                val value = frame.pop(); fiber.frames.removeLast()
                fiber.frames.peekLast()?.push(value)
            }
        }
    }

    private fun binary(operator: String, left: AbilityValue, right: AbilityValue): AbilityValue {
        if (operator == "==" || operator == "!=") {
            val same = if (left is AbilityValue.NumberValue && right is AbilityValue.NumberValue) left.value == right.value else left == right
            return AbilityValues.bool(if (operator == "==") same else !same)
        }
        val a = AbilityValues.numberOf(left); val b = AbilityValues.numberOf(right)
        return when (operator) {
            "+" -> AbilityValues.number(a+b)
            "-" -> AbilityValues.number(a-b)
            "*" -> AbilityValues.number(a*b)
            "/" -> { require(b != 0.0) { "Division by zero." }; AbilityValues.number(a/b) }
            "%" -> { require(b != 0.0) { "Division by zero." }; AbilityValues.number(a%b) }
            "<" -> AbilityValues.bool(a < b)
            "<=" -> AbilityValues.bool(a <= b)
            ">" -> AbilityValues.bool(a > b)
            ">=" -> AbilityValues.bool(a >= b)
            else -> error("Invalid binary operator.")
        }
    }

    companion object {
        private val eventFields = setOf("event_entity", "event_position", "event_amount", "event_tag", "event_data")
        private fun saturatedAdd(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE-increment) Long.MAX_VALUE else value+increment
    }
}
