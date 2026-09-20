package com.wjz.worldsmith.core.story

import com.wjz.worldsmith.core.ability.AbilityValue
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import java.util.function.Function

/** Shared pure condition semantics. An unresolved fact remains unknown even under NOT/NE. */
object StoryConditions {
    const val MAX_NODES = 64
    const val MAX_DEPTH = 8
    @JvmStatic fun test(condition: StoryCondition, values: Function<StoryFactRef, AbilityValue>): Boolean {
        if (validate(condition).isNotEmpty()) return false
        fun evaluate(c: StoryCondition): Boolean? = when (c) {
            StoryCondition.Always -> true
            is StoryCondition.All -> c.conditions.map(::evaluate).let { if (false in it) false else if (null in it) null else true }
            is StoryCondition.Any -> c.conditions.map(::evaluate).let { if (true in it) true else if (null in it) null else false }
            is StoryCondition.Not -> evaluate(c.condition)?.not()
            is StoryCondition.Compare -> {
                val actual = values.apply(c.fact)
                val expected = c.value
                when {
                    actual is AbilityValue.NumberValue && expected is AbilityValue.NumberValue -> when (c.comparison) {
                        StoryComparison.EQ -> actual.value == expected.value; StoryComparison.NE -> actual.value != expected.value
                        StoryComparison.LT -> actual.value < expected.value; StoryComparison.LE -> actual.value <= expected.value
                        StoryComparison.GT -> actual.value > expected.value; StoryComparison.GE -> actual.value >= expected.value
                    }
                    actual is AbilityValue.BoolValue && expected is AbilityValue.BoolValue || actual is AbilityValue.TextValue && expected is AbilityValue.TextValue -> when (c.comparison) {
                        StoryComparison.EQ -> actual == expected; StoryComparison.NE -> actual != expected; else -> null
                    }
                    else -> null
                }
            }
        }
        return evaluate(condition) == true
    }
    @JvmStatic @JvmOverloads fun validate(condition: StoryCondition, path: String = "condition"): List<Diagnostic> {
        val errors = mutableListOf<Diagnostic>(); var nodes = 0
        fun error(at: String, message: String) { errors += Diagnostic(at, "story.condition", DiagnosticSeverity.ERROR, message) }
        fun visit(c: StoryCondition, at: String, depth: Int) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) { if (errors.none { it.code == "story.condition_budget" }) errors += Diagnostic(at,"story.condition_budget",DiagnosticSeverity.ERROR,"Conditions allow at most 64 nodes and depth 8"); return }
            when (c) {
                StoryCondition.Always -> Unit
                is StoryCondition.All -> { if(c.conditions.size > MAX_NODES) error(at,"At most 64 children"); c.conditions.take(MAX_NODES).forEachIndexed { i,v -> visit(v,"$at.conditions[$i]",depth+1) } }
                is StoryCondition.Any -> { if(c.conditions.size > MAX_NODES) error(at,"At most 64 children"); c.conditions.take(MAX_NODES).forEachIndexed { i,v -> visit(v,"$at.conditions[$i]",depth+1) } }
                is StoryCondition.Not -> visit(c.condition,"$at.condition",depth+1)
                is StoryCondition.Compare -> {
                    if(!StoryValidation.validId(c.fact.id) || c.fact.subject?.let { !StoryValidation.validId(it) } == true) error("$at.fact","Use stable local fact and subject IDs")
                    if(!StoryValidation.primitive(c.value)) error("$at.value","Compare only primitive fact values")
                    if(c.comparison !in setOf(StoryComparison.EQ,StoryComparison.NE) && c.value !is AbilityValue.NumberValue) error(at,"Ordered comparisons require numbers")
                }
            }
        }
        visit(condition,path,0); return errors
    }
    @JvmStatic fun references(condition: StoryCondition): List<StoryFactRef> {
        if(validate(condition).isNotEmpty()) return emptyList()
        fun refs(c: StoryCondition): List<StoryFactRef> = when(c) {
            StoryCondition.Always -> emptyList(); is StoryCondition.Compare -> listOf(c.fact)
            is StoryCondition.All -> c.conditions.flatMap(::refs); is StoryCondition.Any -> c.conditions.flatMap(::refs)
            is StoryCondition.Not -> refs(c.condition)
        }
        return java.util.List.copyOf(refs(condition))
    }
    @JvmStatic fun freeze(condition: StoryCondition): StoryCondition {
        require(validate(condition).isEmpty()) { "Invalid story condition" }
        fun copy(c: StoryCondition): StoryCondition = when(c) {
            is StoryCondition.All -> c.copy(conditions=java.util.List.copyOf(c.conditions.map(::copy)))
            is StoryCondition.Any -> c.copy(conditions=java.util.List.copyOf(c.conditions.map(::copy)))
            is StoryCondition.Not -> c.copy(condition=copy(c.condition)); else -> c
        }
        return copy(condition)
    }
}
