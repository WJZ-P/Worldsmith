package com.wjz.worldsmith.core.content

/** One orientation at one anchor. World coordinates use the same immutable integer triple as offsets. */
data class MechanicMatchCandidate(val anchor: MechanicOffset, val quarterTurns: Int)

data class MechanicRuleReference(val mechanic: WorldMechanicDefinition, val rule: WorldMechanicRule)

/** The implementation returns null for an unloaded position; it must never load a chunk. */
fun interface MechanicLoadedBlockLookup {
    fun getIfLoaded(position: MechanicOffset): MechanicBlockPredicate?
}

/**
 * Native callers compare partial properties after native BlockState.rotate, not a hand-maintained
 * approximation of every block's rotation semantics. The pure helper owns geometry, not block types.
 */
fun interface MechanicPredicateMatcher {
    fun matches(expected: MechanicBlockPredicate, actual: MechanicBlockPredicate, quarterTurns: Int): Boolean
}

/** Shared by all candidate attempts for one event. An exhausted budget always fails closed. */
class MechanicMatchBudget @JvmOverloads constructor(
    val maxCandidates: Int = MAX_CANDIDATES,
    val maxBlockReads: Int = MAX_BLOCK_READS,
) {
    var candidatesChecked: Int = 0
        private set
    var blockReads: Int = 0
        private set
    var exhausted: Boolean = false
        private set

    init {
        require(maxCandidates in 1..MAX_CANDIDATES && maxBlockReads in 1..MAX_BLOCK_READS) { "Matching budgets must be positive and within event hard limits" }
    }

    internal fun candidate(): Boolean {
        if (exhausted || candidatesChecked >= maxCandidates) { exhausted = true; return false }
        candidatesChecked++
        return true
    }

    internal fun blockRead(): Boolean {
        if (exhausted || blockReads >= maxBlockReads) { exhausted = true; return false }
        blockReads++
        return true
    }

    companion object {
        const val MAX_CANDIDATES = 512
        const val MAX_BLOCK_READS = 8192
    }
}

/** Pure, bounded geometry utilities shared by offline tooling and runtime tests. */
object WorldMechanicMatching {
    /**
     * Property-free predicates rotate directly. Unrotated partial properties are matched exactly.
     * Nonempty rotated predicates require a native-aware matcher and otherwise fail closed.
     */
    @JvmField val DEFAULT_PREDICATE_MATCHER = MechanicPredicateMatcher { expected, actual, turns ->
        expected.block == actual.block && (turns == 0 || expected.properties.isEmpty()) &&
            expected.properties.all { (key, value) -> actual.properties[key] == value }
    }

    /** Same quarter-turn convention as native CLOCKWISE_90: (x, y, z) -> (-z, y, x). */
    @JvmStatic fun rotateY(offset: MechanicOffset, quarterTurns: Int): MechanicOffset = when (Math.floorMod(quarterTurns, 4)) {
        0 -> offset
        1 -> MechanicOffset(Math.negateExact(offset.z), offset.y, offset.x)
        2 -> MechanicOffset(Math.negateExact(offset.x), offset.y, Math.negateExact(offset.z))
        else -> MechanicOffset(offset.z, offset.y, Math.negateExact(offset.x))
    }

    /**
     * USE_BLOCK only targets the clicked anchor. BLOCK_PLACED may complete any declared pattern cell;
     * each possible anchor is eventPosition - rotatedCellOffset. No world reads or chunk scans occur.
     * Stable order is quarter-turn, then cell x/y/z; duplicate anchor/orientation pairs are removed.
     */
    @JvmStatic fun candidateAnchors(rule: WorldMechanicRule, eventPosition: MechanicOffset): List<MechanicMatchCandidate> {
        requireBoundedPattern(rule)
        val result = linkedSetOf<MechanicMatchCandidate>()
        val offsets = if (rule.event == WorldMechanicEvent.USE_BLOCK) listOf(MechanicOffset()) else
            rule.pattern.map { it.offset }.sortedWith(compareBy<MechanicOffset> { it.x }.thenBy { it.y }.thenBy { it.z })
        for (turns in 0 until if (rule.rotateY) 4 else 1) {
            for (offset in offsets) {
                val rotated = rotateY(offset, turns)
                val anchor = translate(eventPosition, rotated, subtract = true) ?: continue
                result += MechanicMatchCandidate(anchor, turns)
            }
        }
        return java.util.List.copyOf(result)
    }

    /** Stable mechanic-id/rule-id order is independent of incidental authoring array order. */
    @JvmStatic @JvmOverloads fun orderedRules(library: WorldMechanicLibrary, event: WorldMechanicEvent? = null): List<MechanicRuleReference> {
        val diagnostics = WorldMechanicValidation.validate(library)
        require(diagnostics.isEmpty()) { diagnostics.joinToString("; ") { "${it.path}: ${it.message}" } }
        val frozen = WorldMechanicValidation.freeze(library)
        return java.util.List.copyOf(frozen.mechanics.sortedBy { it.id }.flatMap { mechanic ->
            mechanic.rules.filter { event == null || it.event == event }.sortedBy { it.id }.map { MechanicRuleReference(mechanic, it) }
        })
    }

    /**
     * Geometry only: state, biome, hand, cooldown, inventory and transactional preflight belong to the
     * event runtime. A null lookup, overflow, predicate mismatch or shared budget exhaustion is false.
     * Calls are bounded before the lookup is invoked; a failed candidate never consumes game content.
     */
    @JvmStatic @JvmOverloads fun matches(
        rule: WorldMechanicRule,
        candidate: MechanicMatchCandidate,
        blocks: MechanicLoadedBlockLookup,
        budget: MechanicMatchBudget = MechanicMatchBudget(),
        predicateMatcher: MechanicPredicateMatcher = DEFAULT_PREDICATE_MATCHER,
    ): Boolean {
        requireBoundedPattern(rule)
        if (candidate.quarterTurns !in 0..3 || (!rule.rotateY && candidate.quarterTurns != 0) || !budget.candidate()) return false
        for (cell in rule.pattern) {
            val position = translate(candidate.anchor, rotateY(cell.offset, candidate.quarterTurns), subtract = false) ?: return false
            if (!budget.blockRead()) return false
            val actual = blocks.getIfLoaded(position) ?: return false
            if (!predicateMatcher.matches(cell.block, actual, candidate.quarterTurns)) return false
        }
        return true
    }

    private fun requireBoundedPattern(rule: WorldMechanicRule) {
        require(rule.pattern.size in 1..WorldMechanicValidation.MAX_PATTERN_CELLS) { "Expected a validated, bounded pattern" }
        require(rule.pattern.all { cell ->
            cell.offset.x in -WorldMechanicValidation.MAX_OFFSET..WorldMechanicValidation.MAX_OFFSET &&
                cell.offset.y in -WorldMechanicValidation.MAX_OFFSET..WorldMechanicValidation.MAX_OFFSET &&
                cell.offset.z in -WorldMechanicValidation.MAX_OFFSET..WorldMechanicValidation.MAX_OFFSET
        }) { "Expected bounded relative offsets" }
    }

    private fun translate(position: MechanicOffset, offset: MechanicOffset, subtract: Boolean): MechanicOffset? {
        val sign = if (subtract) -1L else 1L
        val x = position.x.toLong() + sign * offset.x
        val y = position.y.toLong() + sign * offset.y
        val z = position.z.toLong() + sign * offset.z
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return MechanicOffset(x.toInt(), y.toInt(), z.toInt())
    }
}
