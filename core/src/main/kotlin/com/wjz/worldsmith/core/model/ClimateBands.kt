package com.wjz.worldsmith.core.model

/** One indivisible square of the semantic climate grid. */
data class ClimateCell(
    val relief: ReliefBand,
    val temperature: TemperatureBand,
    val humidity: HumidityBand,
)

/**
 * Translates semantic climate slots into the raw parameter boxes Minecraft's
 * biome source wants.
 *
 * The band edges mirror vanilla's `OverworldBiomeBuilder`. They are chosen to be
 * adjacent and gapless: continentalness is cut into four touching spans, the
 * inland span into three touching erosion spans, and temperature and humidity
 * into touching spans of their own. Because of that, "every cell is claimed
 * exactly once" is a sufficient proof that every biome in the pack can actually
 * generate, with no sampling required.
 *
 * A slot may claim several adjacent bands on an axis. They must be adjacent:
 * a box spanning COLD and HOT would silently swallow TEMPERATE as well, so the
 * validator rejects gaps rather than letting the box lie about what it covers.
 *
 * A pack may still write a raw [ClimateBox] instead of a slot. That escape hatch
 * is legal but forfeits the proof, and the validator says so.
 */
object ClimateBands {
    private val FULL = NumericRange(-1.0f, 1.0f)

    /** Continentalness and erosion spans per relief band. */
    private val RELIEF: Map<ReliefBand, Pair<NumericRange, NumericRange>> = mapOf(
        ReliefBand.DEEP_WATER to (NumericRange(-1.2f, -0.455f) to FULL),
        ReliefBand.SHALLOW_WATER to (NumericRange(-0.455f, -0.19f) to FULL),
        ReliefBand.COAST to (NumericRange(-0.19f, -0.11f) to FULL),
        ReliefBand.PEAKS to (NumericRange(-0.11f, 1.0f) to NumericRange(-1.0f, -0.375f)),
        ReliefBand.HIGHLAND to (NumericRange(-0.11f, 1.0f) to NumericRange(-0.375f, 0.05f)),
        ReliefBand.FLATS to (NumericRange(-0.11f, 1.0f) to NumericRange(0.05f, 1.0f)),
    )

    private val TEMPERATURE: Map<TemperatureBand, NumericRange> = mapOf(
        TemperatureBand.COLD to NumericRange(-1.0f, -0.15f),
        TemperatureBand.TEMPERATE to NumericRange(-0.15f, 0.55f),
        TemperatureBand.HOT to NumericRange(0.55f, 1.0f),
    )

    private val HUMIDITY: Map<HumidityBand, NumericRange> = mapOf(
        HumidityBand.ARID to NumericRange(-1.0f, -0.1f),
        HumidityBand.HUMID to NumericRange(-0.1f, 1.0f),
    )

    /** Every cell the grid contains. */
    val ALL_CELLS: Set<ClimateCell> = ReliefBand.entries.flatMap { relief ->
        TemperatureBand.entries.flatMap { temperature ->
            HumidityBand.entries.map { ClimateCell(relief, temperature, it) }
        }
    }.toSet()

    /** An empty band list on an axis claims that whole axis. */
    fun temperatureBands(slot: ClimateSlot): List<TemperatureBand> =
        slot.temperature.ifEmpty { TemperatureBand.entries }

    fun humidityBands(slot: ClimateSlot): List<HumidityBand> =
        slot.humidity.ifEmpty { HumidityBand.entries }

    /** The cells a slot claims. */
    fun cells(slot: ClimateSlot): Set<ClimateCell> = buildSet {
        temperatureBands(slot).forEach { temperature ->
            humidityBands(slot).forEach { humidity ->
                add(ClimateCell(slot.relief, temperature, humidity))
            }
        }
    }

    /** True when the listed bands form an unbroken run, so their span covers only them. */
    fun <T : Enum<T>> isContiguous(bands: List<T>): Boolean {
        if (bands.size <= 1) {
            return true
        }
        val ordinals = bands.map { it.ordinal }.distinct().sorted()
        return ordinals.size == bands.size && ordinals.last() - ordinals.first() == bands.size - 1
    }

    /**
     * Depth and weirdness stay open so the grid keeps covering caves and river
     * bands rather than leaving holes for the nearest-neighbour search to fill
     * arbitrarily.
     */
    fun resolve(slot: ClimateSlot): ClimateBox {
        val (continentalness, erosion) = requireNotNull(RELIEF[slot.relief]) { "Unmapped relief band ${slot.relief}" }
        return ClimateBox(
            temperature = span(temperatureBands(slot).map { TEMPERATURE.getValue(it) }),
            humidity = span(humidityBands(slot).map { HUMIDITY.getValue(it) }),
            continentalness = continentalness,
            erosion = erosion,
            depth = FULL,
            weirdness = FULL,
            offset = 0.0f,
        )
    }

    private fun span(ranges: List<NumericRange>): NumericRange =
        NumericRange(ranges.minOf { it.min }, ranges.maxOf { it.max })
}
