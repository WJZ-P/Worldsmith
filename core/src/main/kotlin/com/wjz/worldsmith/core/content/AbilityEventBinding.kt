package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.ability.AbilityPrograms
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable

/** A native event subscription and invocation policy; all gameplay decisions remain in source. */
@Serializable
data class AbilityEventBinding @JvmOverloads constructor(
    val id: String,
    val program: String,
    val startOn: List<String>,
    val listenTo: List<String> = emptyList(),
    val cancelOn: List<String> = emptyList(),
    val cooldownTicks: Int = 20,
    val range: Double = 8.0,
    /** Creature tick observations can be spread across native ticks instead of retaining an always-active invocation. */
    val intervalTicks: Int = 1,
)

/** Open string event names are checked against actually installed host adapters, not invented effects. */
object AbilityEventBindings {
    const val MAX_BINDINGS = 16
    const val MAX_EVENTS = 8
    private val EVENT = Regex("[a-z][a-z0-9_]{0,47}")
    private val ITEMS = java.util.Set.copyOf(setOf("use_start", "use_tick", "use_release", "use_cancel", "melee_hit", "equip", "unequip", "interact_entity"))
    private val CREATURES = java.util.Set.copyOf(setOf("spawn", "tick", "enter", "exit", "interact_entity", "hurt"))

    @JvmStatic fun itemEvents(): Set<String> = ITEMS
    @JvmStatic fun creatureEvents(): Set<String> = CREATURES
    @JvmStatic fun events(binding: AbilityEventBinding): Set<String> = (binding.startOn + binding.listenTo + binding.cancelOn).toSet()

    @JvmStatic fun freeze(bindings: List<AbilityEventBinding>): List<AbilityEventBinding> = java.util.List.copyOf(bindings.map {
        it.copy(startOn = java.util.List.copyOf(it.startOn), listenTo = java.util.List.copyOf(it.listenTo), cancelOn = java.util.List.copyOf(it.cancelOn))
    })

    @JvmStatic fun validate(bindings: List<AbilityEventBinding>, host: String, path: String): List<Diagnostic> = buildList {
        val supported = when (host) { "item" -> ITEMS; "creature" -> CREATURES; else -> error("Unknown ability binding host '$host'") }
        fun problem(field: String, code: String, message: String) { add(Diagnostic(field, "ability.binding.$code", DiagnosticSeverity.ERROR, message)) }
        if (bindings.size > MAX_BINDINGS) problem(path, "capacity", "At most $MAX_BINDINGS event bindings per host definition")
        val ids = hashSetOf<String>()
        bindings.take(MAX_BINDINGS + 1).forEachIndexed { index, binding ->
            val at = "$path[$index]"
            if (!CustomItemValidation.validId(binding.id)) problem("$at.id", "id", "Use a normalized local binding id")
            if (!ids.add(binding.id)) problem("$at.id", "duplicate", "Binding ids are unique within a host definition")
            if (!AbilityPrograms.validId(binding.program)) problem("$at.program", "program", "Use a normalized local ability program id")
            if (binding.startOn.isEmpty()) problem("$at.startOn", "start_required", "A binding needs at least one real start event")
            for ((name, values) in listOf("startOn" to binding.startOn, "listenTo" to binding.listenTo, "cancelOn" to binding.cancelOn)) {
                if (values.size > MAX_EVENTS || values.distinct().size != values.size) problem("$at.$name", "events", "Use at most $MAX_EVENTS distinct event names")
                values.take(MAX_EVENTS + 1).forEachIndexed { i, event ->
                    if (!EVENT.matches(event) || event !in supported) problem("$at.$name[$i]", "event", "Unsupported $host event '$event'; installed hooks: ${supported.sorted()}")
                }
            }
            if (binding.startOn.any { it in binding.listenTo || it in binding.cancelOn } || binding.listenTo.any { it in binding.cancelOn })
                problem(at, "overlap", "Start, listen and hard-cancel event sets are disjoint")
            if (binding.cooldownTicks !in 1..72000) problem("$at.cooldownTicks", "cooldown", "Cooldown must be 1..72000 ticks")
            if (!binding.range.isFinite() || binding.range !in 0.5..24.0) problem("$at.range", "range", "Event observation range must be finite and 0.5..24 blocks")
            if (binding.intervalTicks !in 1..1200 || binding.intervalTicks != 1 && (host != "creature" || "tick" !in events(binding)))
                problem("$at.intervalTicks", "interval", "A 1..1200 tick interval applies only to a creature tick subscription; other bindings use 1")
        }
    }
}
