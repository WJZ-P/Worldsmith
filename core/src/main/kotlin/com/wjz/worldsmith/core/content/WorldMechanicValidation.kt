package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Structural limits are independent of the pack registry; the latter resolves actual references. */
object WorldMechanicValidation {
    const val MAX_MECHANICS = 64
    const val MAX_RULES = 16
    const val MAX_TOTAL_RULES = 256
    const val MAX_PATTERN_CELLS = 128
    const val MAX_CELLS = MAX_PATTERN_CELLS
    const val MAX_TOTAL_CELLS = 4096
    const val MAX_ACTIONS = 16
    const val MAX_STATES = 16
    const val MAX_OFFSET = 8
    const val MAX_PROPERTIES = 16
    const val MAX_BIOMES = 64
    const val MAX_COOLDOWN_TICKS = 72000

    private val ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val RESOURCE = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    private val CREATURE = Regex("[a-z0-9][a-z0-9_./-]{0,95}")
    private val PROPERTY = Regex("[a-z][a-z0-9_]{0,63}")
    private val PROPERTY_VALUE = Regex("[a-z0-9_.-]{1,64}")
    private val AIR = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
    private const val BLOCK_PREFIX = "worldsmith:content/"
    private const val ITEM_PREFIX = "worldsmith:item/"

    @JvmStatic fun validId(id: String): Boolean = ID.matches(id) && ".." !in id && !id.endsWith('.')

    @JvmStatic fun validBlockReference(value: String): Boolean = validResource(value) && when {
        value.startsWith(ITEM_PREFIX) -> false
        value.startsWith(BLOCK_PREFIX) -> validId(value.removePrefix(BLOCK_PREFIX))
        else -> true
    }

    @JvmStatic fun validItemReference(value: String): Boolean = validResource(value) && value !in AIR && when {
        value.startsWith(ITEM_PREFIX) -> validId(value.removePrefix(ITEM_PREFIX))
        value.startsWith(BLOCK_PREFIX) -> validId(value.removePrefix(BLOCK_PREFIX))
        else -> true
    }

    @JvmStatic fun isAir(value: String): Boolean = value in AIR

    private fun validResource(value: String): Boolean = value.length in 3..256 && RESOURCE.matches(value) &&
        value.substringAfter(':').split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        !value.startsWith("worldsmith:content/block/") && !value.startsWith("worldsmith:content/item/")

    @JvmStatic fun validate(library: WorldMechanicLibrary): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) {
            add(Diagnostic(path, "mechanics.$code", DiagnosticSeverity.ERROR, message))
        }
        fun offset(value: MechanicOffset, path: String) {
            if (value.x !in -MAX_OFFSET..MAX_OFFSET || value.y !in -MAX_OFFSET..MAX_OFFSET || value.z !in -MAX_OFFSET..MAX_OFFSET)
                error(path, "offset", "Each relative coordinate must be -$MAX_OFFSET..$MAX_OFFSET")
        }
        fun predicate(value: MechanicBlockPredicate, path: String) {
            if (!validBlockReference(value.block)) error("$path.block", "block_reference", "Use an exact native block or logical world block id, without tags, inline states or raw hosts")
            if (value.properties.size > MAX_PROPERTIES) error("$path.properties", "property_count", "At most $MAX_PROPERTIES block-state properties")
            if (value.block.startsWith(BLOCK_PREFIX) && value.properties.isNotEmpty())
                error("$path.properties", "custom_block_properties", "Logical custom blocks use their immutable definition and have no authored state overrides")
            value.properties.entries.take(MAX_PROPERTIES).forEach { (key, entry) ->
                if (!PROPERTY.matches(key) || !PROPERTY_VALUE.matches(entry))
                    error("$path.properties", "property", "Property names and values must be bounded lowercase state tokens (1..64 characters)")
            }
        }

        if (library.schemaVersion != 1) error("schemaVersion", "schema", "Interaction schema must be 1")
        if (library.mechanics.size > MAX_MECHANICS) {
            error("mechanics", "capacity", "At most $MAX_MECHANICS mechanics per library")
            return@buildList
        }
        val ruleCount = library.mechanics.sumOf { it.rules.size.toLong() }
        if (ruleCount > MAX_TOTAL_RULES) error("mechanics", "total_rules", "At most $MAX_TOTAL_RULES rules across the library")
        // Inspect at most the per-mechanic rule budget even for malformed in-memory DTOs.
        val cellCount = library.mechanics.sumOf { mechanic -> mechanic.rules.take(MAX_RULES).sumOf { it.pattern.size.toLong() } }
        if (cellCount > MAX_TOTAL_CELLS) error("mechanics", "total_cells", "At most $MAX_TOTAL_CELLS pattern cells across the library")
        val ids = hashSetOf<String>()
        library.mechanics.forEachIndexed { i, mechanic ->
            val path = "mechanics[$i]"
            if (!validId(mechanic.id)) error("$path.id", "id", "Use a normalized lowercase local id of 1..64 characters")
            if (!ids.add(mechanic.id)) error("$path.id", "duplicate", "Duplicate mechanic id '${mechanic.id}'")
            if (mechanic.displayName.isBlank() || mechanic.displayName.length > 128 || mechanic.displayName.any(Char::isISOControl))
                error("$path.displayName", "name", "Display names need 1..128 printable characters")
            if (mechanic.description.length > 2048 || mechanic.description.any { it.isISOControl() && it != '\n' })
                error("$path.description", "description", "Descriptions allow at most 2048 printable characters and line breaks")
            if (mechanic.states.size !in 1..MAX_STATES) error("$path.states", "state_count", "Each mechanic needs 1..$MAX_STATES states")
            val states = hashSetOf<String>()
            mechanic.states.take(MAX_STATES).forEachIndexed { j, state ->
                if (!validId(state)) error("$path.states[$j]", "state_id", "Use a normalized local state id")
                if (!states.add(state)) error("$path.states[$j]", "duplicate_state", "State '$state' is declared more than once")
            }
            if (mechanic.initialState !in states) error("$path.initialState", "initial_state", "The initial state must be declared")
            if (mechanic.rules.size !in 1..MAX_RULES) error("$path.rules", "rule_count", "Each mechanic needs 1..$MAX_RULES rules")
            val ruleIds = hashSetOf<String>()
            val boundedRules = mechanic.rules.take(MAX_RULES)
            boundedRules.forEachIndexed { j, rule ->
                val at = "$path.rules[$j]"
                if (!validId(rule.id)) error("$at.id", "rule_id", "Use a normalized local rule id")
                if (!ruleIds.add(rule.id)) error("$at.id", "duplicate_rule", "Rule '${rule.id}' is declared more than once in this mechanic")
                if (rule.fromState !in states) error("$at.fromState", "state_reference", "The source state must be declared")
                if (rule.toState !in states) error("$at.toState", "state_reference", "The destination state must be declared")
                if (rule.cooldownTicks !in 1..MAX_COOLDOWN_TICKS) error("$at.cooldownTicks", "cooldown", "Cooldown must be 1..$MAX_COOLDOWN_TICKS ticks")
                if (rule.pattern.size !in 1..MAX_CELLS) error("$at.pattern", "cell_count", "Each pattern needs 1..$MAX_CELLS cells")
                val cells = linkedMapOf<MechanicOffset, MechanicPatternCell>()
                rule.pattern.take(MAX_CELLS).forEachIndexed { k, cell ->
                    val cellPath = "$at.pattern[$k]"
                    offset(cell.offset, "$cellPath.offset")
                    predicate(cell.block, "$cellPath.block")
                    if (cells.putIfAbsent(cell.offset, cell) != null) error("$cellPath.offset", "duplicate_cell", "Pattern positions must be unique")
                    if (cell.consume && isAir(cell.block.block)) error("$cellPath.consume", "consume_air", "A consumed pattern cell must contain a non-air block")
                }
                val anchor = cells[MechanicOffset()]
                if (anchor == null || isAir(anchor.block.block)) error("$at.pattern", "anchor", "Every rule needs its own non-air cell at offset (0,0,0)")
                rule.heldItem?.let { held ->
                    if (rule.event != WorldMechanicEvent.USE_BLOCK) error("$at.heldItem", "held_event", "A hand offering is supported only by USE_BLOCK")
                    if (!validItemReference(held.item)) error("$at.heldItem.item", "item_reference", "Use a non-air native item or logical item/block-item alias, never raw hosts")
                    if (held.count !in 1..64) error("$at.heldItem.count", "item_count", "Hand offerings need 1..64 items and must fit their native stack limit")
                }
                if (rule.biomes.size > MAX_BIOMES) error("$at.biomes", "biome_count", "At most $MAX_BIOMES biome filters per rule")
                val biomes = hashSetOf<String>()
                rule.biomes.take(MAX_BIOMES).forEachIndexed { k, biome ->
                    if (!validId(biome)) error("$at.biomes[$k]", "biome_reference", "Use a normalized local biome id")
                    if (!biomes.add(biome)) error("$at.biomes[$k]", "duplicate_biome", "Biome filters must be unique")
                }
                if (rule.actions.size !in 1..MAX_ACTIONS) error("$at.actions", "action_count", "Each rule needs 1..$MAX_ACTIONS actions")
                val writes = hashSetOf<MechanicOffset>()
                var spawns = 0
                rule.actions.take(MAX_ACTIONS).forEachIndexed { k, action ->
                    val actionPath = "$at.actions[$k]"
                    when (action) {
                        is MechanicAction.SetBlock -> {
                            offset(action.offset, "$actionPath.offset")
                            predicate(action.block, "$actionPath.block")
                            val cell = cells[action.offset]
                            if (cell == null) error("$actionPath.offset", "write_outside_pattern", "Set-block targets must be explicit pattern cells")
                            if (cell?.consume == true) error("$actionPath.offset", "consume_write", "A consumed cell has one write already and must not also be set")
                            if (!writes.add(action.offset)) error("$actionPath.offset", "duplicate_write", "Each target cell may be written at most once")
                        }
                        is MechanicAction.SpawnCreature -> {
                            spawns++
                            offset(action.offset, "$actionPath.offset")
                            if (!CREATURE.matches(action.creature) || action.creature.split('/').any { it == "." || it == ".." })
                                error("$actionPath.creature", "creature_reference", "Use a normalized local creature id, never a native entity host")
                        }
                        is MechanicAction.GiveItem -> {
                            if (!validItemReference(action.item)) error("$actionPath.item", "item_reference", "Use a non-air native item or logical item/block-item alias, never raw hosts")
                            if (action.count !in 1..64) error("$actionPath.count", "item_count", "Each reward is one stack of 1..64 items and must fit its native stack limit")
                        }
                    }
                }
                if (spawns > 1) error("$at.actions", "spawn_count", "At most one creature may be spawned per activation")
                if (spawns > 0 && rule.fromState == rule.toState) error("$at.toState", "spawn_state", "A creature spawn must advance to a different state")
                if (rule.fromState == rule.toState && !hasCost(rule) && rule.actions.take(MAX_ACTIONS).any(::isReward))
                    error(at, "free_reward_cycle", "A same-state reward or spawn needs an actual hand or non-air consumed-cell cost")
            }

            val edges = boundedRules.filter { it.fromState in states && it.toState in states }
            val reachable = reachableFrom(mechanic.initialState, edges)
            val freeEdges = edges.filterNot(::hasCost)
            boundedRules.forEachIndexed graphRule@ { j, rule ->
                if (rule.fromState !in reachable || rule.toState !in states) return@graphRule
                if (rule.actions.take(MAX_ACTIONS).any { it is MechanicAction.SpawnCreature } &&
                    rule.fromState != rule.toState && rule.fromState in reachableFrom(rule.toState, edges))
                    error("$path.rules[$j]", "spawn_cycle", "A reachable spawn transition must not belong to a state cycle, even if another transition has a cost")
                if (rule.fromState != rule.toState && !hasCost(rule) && rule.actions.take(MAX_ACTIONS).any(::isReward) &&
                    rule.fromState in reachableFrom(rule.toState, freeEdges))
                    error("$path.rules[$j]", "free_reward_cycle", "A reachable reward cycle must include an actual hand or non-air consumed-cell cost")
            }
        }
    }

    private fun isReward(action: MechanicAction): Boolean = action is MechanicAction.GiveItem || action is MechanicAction.SpawnCreature
    private fun hasCost(rule: WorldMechanicRule): Boolean =
        (rule.event == WorldMechanicEvent.USE_BLOCK && rule.heldItem?.let { it.count > 0 && validItemReference(it.item) } == true) ||
            rule.pattern.take(MAX_CELLS).any { it.consume && !isAir(it.block.block) && validBlockReference(it.block.block) }

    private fun reachableFrom(start: String, rules: List<WorldMechanicRule>): Set<String> {
        val edges = rules.groupBy { it.fromState }
        val found = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val state = pending.removeFirst()
            if (found.add(state)) edges[state].orEmpty().forEach { pending.addLast(it.toState) }
        }
        return found
    }

    /** Copy every collection, including maps inside pattern predicates and set-block actions. */
    @JvmStatic fun freeze(library: WorldMechanicLibrary): WorldMechanicLibrary = library.copy(
        mechanics = java.util.List.copyOf(library.mechanics.map { mechanic ->
            mechanic.copy(states = java.util.List.copyOf(mechanic.states), rules = java.util.List.copyOf(mechanic.rules.map { rule ->
                rule.copy(
                    pattern = java.util.List.copyOf(rule.pattern.map { it.copy(block = freezePredicate(it.block)) }),
                    actions = java.util.List.copyOf(rule.actions.map { action ->
                        when (action) {
                            is MechanicAction.SetBlock -> action.copy(block = freezePredicate(action.block))
                            is MechanicAction.SpawnCreature -> action.copy()
                            is MechanicAction.GiveItem -> action.copy()
                        }
                    }),
                    biomes = java.util.List.copyOf(rule.biomes),
                )
            }))
        }),
    )

    private fun freezePredicate(value: MechanicBlockPredicate): MechanicBlockPredicate =
        value.copy(properties = java.util.Collections.unmodifiableMap(LinkedHashMap(value.properties)))
}
