package com.wjz.worldsmith.core.ability.debug

import com.wjz.worldsmith.core.ability.AbilityValue
import kotlinx.serialization.Serializable

@Serializable
enum class AbilityTraceKind { INSTRUCTION, CAPABILITY_CALL, CAPABILITY_RETURN, CAPABILITY_FAILURE, STATE_WRITE, WAIT }

@Serializable
data class AbilityTraceLocation(
    val tick: Long,
    val operation: Int,
    val event: String,
    val function: String,
    val pc: Int,
    val line: Int,
    val column: Int,
    val callDepth: Int,
    val stateRevision: Long,
)

/** Values are immutable; UI/report consumers should use [AbilityTraceViews] rather than dumping arbitrary values. */
data class AbilityTraceEvent(
    val kind: AbilityTraceKind,
    val location: AbilityTraceLocation,
    val opcode: String? = null,
    val name: String? = null,
    val arguments: List<AbilityValue> = emptyList(),
    val value: AbilityValue? = null,
    val error: String? = null,
    val wakeAt: Long? = null,
)

/** Opt-in only. A faulty observer is detached, not allowed to change program/effect execution. */
fun interface AbilityTraceListener { fun onTrace(event: AbilityTraceEvent) }

@Serializable
data class AbilityTraceValue(val type: String, val preview: String, val truncated: Boolean = false, val size: Int? = null)

@Serializable
data class AbilityTraceEntry(
    val kind: AbilityTraceKind,
    val location: AbilityTraceLocation,
    val opcode: String? = null,
    val name: String? = null,
    val arguments: List<AbilityTraceValue> = emptyList(),
    val value: AbilityTraceValue? = null,
    val error: String? = null,
    val wakeAt: Long? = null,
)

/** Deterministic, allocation-bounded display values shared by dry-run and native opt-in traces. */
object AbilityTraceViews {
    const val MAX_PREVIEW_CHARS = 256

    @JvmStatic fun entry(event: AbilityTraceEvent): AbilityTraceEntry = AbilityTraceEntry(
        event.kind, event.location, event.opcode, event.name,
        event.arguments.take(16).map(::value), event.value?.let(::value), event.error?.take(512), event.wakeAt,
    )

    @JvmStatic fun value(value: AbilityValue): AbilityTraceValue {
        var truncated = false
        var nodes = 0
        val output = StringBuilder()
        fun append(text: String) {
            val remaining = MAX_PREVIEW_CHARS - output.length
            if (text.length > remaining) { output.append(text.take(remaining)); truncated = true }
            else output.append(text)
        }
        fun quoted(text: String) {
            append("\"")
            for (character in text) {
                if (output.length >= MAX_PREVIEW_CHARS) { truncated = true; break }
                append(when (character) { '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '"' -> "\\\""; '\\' -> "\\\\"; else -> if (character.code < 32) "?" else character.toString() })
            }
            append("\"")
        }
        fun visit(current: AbilityValue, depth: Int) {
            if (++nodes > 16 || depth > 3 || output.length >= MAX_PREVIEW_CHARS) { truncated = true; append("..."); return }
            when (current) {
                is AbilityValue.NumberValue -> append(current.value.toString())
                is AbilityValue.BoolValue -> append(current.value.toString())
                is AbilityValue.TextValue -> quoted(current.value)
                is AbilityValue.EntityValue -> { append("entity("); quoted(current.id); append(")") }
                is AbilityValue.VectorValue -> append("vec(${current.x}, ${current.y}, ${current.z})")
                is AbilityValue.ListValue -> {
                    append("[")
                    current.values.take(4).forEachIndexed { index, item -> if (index != 0) append(", "); visit(item, depth + 1) }
                    if (current.values.size > 4) { truncated = true; append(", ...") }
                    append("]")
                }
                is AbilityValue.MapValue -> {
                    append("{")
                    current.values.toSortedMap().entries.take(4).forEachIndexed { index, item ->
                        if (index != 0) append(", "); quoted(item.key); append(": "); visit(item.value, depth + 1)
                    }
                    if (current.values.size > 4) { truncated = true; append(", ...") }
                    append("}")
                }
                AbilityValue.NullValue -> append("null")
            }
        }
        visit(value, 0)
        val type = when (value) {
            is AbilityValue.NumberValue -> "NUMBER"
            is AbilityValue.BoolValue -> "BOOL"
            is AbilityValue.TextValue -> "TEXT"
            is AbilityValue.VectorValue -> "VECTOR"
            is AbilityValue.EntityValue -> "ENTITY"
            is AbilityValue.ListValue -> "LIST"
            is AbilityValue.MapValue -> "MAP"
            AbilityValue.NullValue -> "NULL"
        }
        val size = when (value) {
            is AbilityValue.TextValue -> value.value.length
            is AbilityValue.ListValue -> value.values.size
            is AbilityValue.MapValue -> value.values.size
            else -> null
        }
        return AbilityTraceValue(type, output.toString(), truncated, size)
    }
}
