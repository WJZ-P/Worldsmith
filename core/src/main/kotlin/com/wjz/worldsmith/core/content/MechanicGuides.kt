package com.wjz.worldsmith.core.content

import java.util.Locale

/** Read-only player-facing projection of the exact frozen rules, not separately authored instructions. */
data class MechanicGuide(
    val mechanicId: String,
    val title: String,
    val description: String,
    val initialState: String,
    val rules: List<MechanicRuleGuide>,
)

data class MechanicRuleGuide(
    val rule: WorldMechanicRule,
    val palette: List<MechanicGuideMaterial>,
    val layers: List<MechanicGuideLayer>,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
)

data class MechanicGuideMaterial(
    val symbol: String,
    val block: MechanicBlockPredicate,
    val count: Int,
    val consumeCount: Int,
)

data class MechanicGuideLayer(val y: Int, val cells: List<MechanicPatternCell>)

object MechanicGuides {
    /** No game world, inventory, server ledger, authoring session or network is needed to read a plan. */
    @JvmStatic fun describe(definition: WorldMechanicDefinition): MechanicGuide {
        val library = WorldMechanicLibrary(mechanics = listOf(definition))
        val errors = WorldMechanicValidation.validate(library)
        require(errors.isEmpty()) { "Invalid mechanic guide source: ${errors.joinToString { it.message }}" }
        val frozen = WorldMechanicValidation.freeze(library).mechanics.single()
        val rules = frozen.rules.sortedBy { it.id }.map { rule ->
            val palette = rule.pattern.filterNot { WorldMechanicValidation.isAir(it.block.block) }
                .groupBy { it.block }
                .toList().sortedWith(compareBy({ it.first.block }, { predicateKey(it.first) }))
                .mapIndexed { index, (predicate, cells) ->
                    MechanicGuideMaterial((index + 1).toString(36).uppercase(Locale.ROOT), predicate,
                        cells.size, cells.count { it.consume })
                }
            // Every layer uses the same X/Z frame; omitted cells and explicitly required air remain distinct.
            val layers = rule.pattern.groupBy { it.offset.y }.toSortedMap().map { (y, cells) ->
                MechanicGuideLayer(y, immutable(cells.sortedWith(compareBy({ it.offset.z }, { it.offset.x }))))
            }
            MechanicRuleGuide(rule, immutable(palette), immutable(layers),
                rule.pattern.minOf { it.offset.x }, rule.pattern.maxOf { it.offset.x },
                rule.pattern.minOf { it.offset.z }, rule.pattern.maxOf { it.offset.z })
        }
        return MechanicGuide(frozen.id, frozen.displayName, frozen.description, frozen.initialState, immutable(rules))
    }

    /** null means unspecified/ignored, dot means required air; a symbol means an exact block predicate. */
    @JvmStatic fun symbolAt(guide: MechanicRuleGuide, x: Int, y: Int, z: Int): String? {
        val cell = guide.layers.firstOrNull { it.y == y }?.cells?.firstOrNull { it.offset.x == x && it.offset.z == z } ?: return null
        if (WorldMechanicValidation.isAir(cell.block.block)) return "."
        return guide.palette.first { it.block == cell.block }.symbol
    }

    private fun predicateKey(predicate: MechanicBlockPredicate) = predicate.properties.toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" }
    private fun <T> immutable(values: Collection<T>): List<T> = java.util.List.copyOf(values)
}
