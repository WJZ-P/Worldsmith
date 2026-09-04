package com.wjz.worldsmith.core.analysis

import com.wjz.worldsmith.core.model.BiomeArchetypeRole
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.ClimateBands
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.NumericRange
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.model.TerrainShape
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What share of the world one biome is expected to cover. */
data class BiomeShare(
    val id: String,
    val archetype: BiomeArchetypeRole,
    val share: Double,
)

/** Two biomes that keep coming second to each other, which is what a border is. */
data class BiomeBorder(
    val first: String,
    val second: String,
    val share: Double,
)

data class DistributionReport(
    val samples: Int,
    val biomes: List<BiomeShare>,
    val landShare: Double,
    val waterShare: Double,
    val archetypes: Map<BiomeArchetypeRole, Double>,
    val absent: List<String>,
    val rare: List<String>,
    val dominant: List<String>,
    val borders: List<BiomeBorder>,
    val notes: List<String>,
)

/**
 * Predicts how much of the world each biome will actually cover.
 *
 * <p>A climate box says where a biome is allowed to be, never how much of the
 * world that is, and the two come apart badly. The axes are noise, so they are
 * bell-shaped rather than flat: the `HOT` band spans 0.55 to 1.0 and the
 * measured temperature field has a standard deviation of 0.383, which makes it
 * roughly a fourteenth of the world while `COLD`, which looks like its mirror,
 * takes a third. Nothing an author or a model can read off the document says so.
 *
 * <p>The sampling is Monte Carlo over the six climate axes. Continuous axes are
 * drawn from distributions measured against the real noise router; procedural
 * erosion is instead the same authored flat/highland/peak mixture as the terrain
 * compiler, with measured noise inside each band. See the constants below and
 * the mod-side test that re-measures them.
 * The result is a share of climate space, which is not identical to a share of
 * the ground a player walks: it takes no account of a biome sitting where the
 * terrain happens to put more surface. It is close enough to answer the
 * questions that actually go wrong - which biome is never chosen, which one
 * quietly owns half the map, and which two meet along a border.
 *
 * <p>Deterministic, so the same pack always reports the same numbers and a
 * before/after comparison means something.
 */
object BiomeDistributionAnalyzer {
    const val DEFAULT_SAMPLES: Int = 20_000

    /** A biome under this share is one a player may never find. */
    const val RARE_SHARE: Double = 0.02

    /** A biome over this share is most of the world. */
    const val DOMINANT_SHARE: Double = 0.30

    private const val MAX_BORDERS = 8

    // Measured by sampling the real climate sampler over 40k positions across
    // four land ratios. Public so the mod-side test can re-measure and fail if
    // the wired noise ever drifts away from them, which would leave this whole
    // report quietly describing a world nobody generates.
    const val TEMPERATURE_SIGMA: Double = 0.383
    const val HUMIDITY_SIGMA: Double = 0.240
    const val CONTINENTALNESS_SIGMA: Double = 0.371
    /** Spread of the raw erosion texture before procedural landform remapping. */
    const val LANDFORM_TEXTURE_SIGMA: Double = 0.302
    const val FLATS_LANDFORM_CENTER: Double = 0.45
    const val FLATS_LANDFORM_TEXTURE: Double = 0.25
    const val HIGHLANDS_LANDFORM_CENTER: Double = -0.16
    const val HIGHLANDS_LANDFORM_TEXTURE: Double = 0.14
    const val PEAKS_LANDFORM_CENTER: Double = -0.68
    const val PEAKS_LANDFORM_TEXTURE: Double = 0.18
    const val WEIRDNESS_SIGMA: Double = 0.343
    /** Marginal depth spread, retained for calibration against the live router. */
    const val DEPTH_SIGMA: Double = 0.354

    /** Conditional depth fit measured together with the marginal spread above. */
    const val DEPTH_CONTINENTALNESS_SLOPE: Double = 0.838
    const val DEPTH_INTERCEPT: Double = 0.146
    const val DEPTH_RESIDUAL_SIGMA: Double = 0.165

    /** Continentalness above this is land, matching the semantic band table. */
    private const val COAST_EDGE = -0.11

    fun analyze(
        plan: BiomePlan,
        terrain: TerrainPlan,
        samples: Int = DEFAULT_SAMPLES,
    ): DistributionReport {
        val boxes = plan.biomes.flatMap { biome ->
            biome.allPlacements.mapNotNull { placement ->
                val box = placement.slot?.let(ClimateBands::resolve) ?: placement.climate
                box?.let { biome to it }
            }
        }
        if (boxes.isEmpty()) {
            return DistributionReport(
                0, emptyList(), 0.0, 0.0, emptyMap(), plan.biomes.map { it.id }, emptyList(), emptyList(), emptyList(),
                listOf("No biome declares a placement, so there is nothing to sample."),
            )
        }

        val bias = continentalnessMean(terrain)
        val random = Random(0x0B10_5EED)
        val hits = LinkedHashMap<String, Int>()
        val archetypeHits = LinkedHashMap<BiomeArchetypeRole, Int>()
        val borderHits = LinkedHashMap<Pair<String, String>, Int>()
        var land = 0

        repeat(samples) {
            val baseContinentalness = normal(random, bias, CONTINENTALNESS_SIGMA)
            val continentalness = sampleHydrology(random, baseContinentalness, terrain)
            val point = doubleArrayOf(
                normal(random, 0.0, TEMPERATURE_SIGMA),
                normal(random, 0.0, HUMIDITY_SIGMA),
                continentalness,
                sampleLandform(random, terrain),
                // Depth follows the continentalness of this position, not the
                // world-wide land-ratio bias. Using the bias here erased their
                // measured correlation and badly overestimated cave boxes on
                // oceanic worlds.
                normal(
                    random,
                    DEPTH_CONTINENTALNESS_SLOPE * continentalness + DEPTH_INTERCEPT,
                    DEPTH_RESIDUAL_SIGMA,
                ),
                normal(random, 0.0, WEIRDNESS_SIGMA),
            )
            if (continentalness > COAST_EDGE) {
                land++
            }

            var bestId: String? = null
            var bestArchetype: BiomeArchetypeRole? = null
            var bestFitness = Double.MAX_VALUE
            var runnerUp: String? = null
            var runnerUpFitness = Double.MAX_VALUE
            boxes.forEach { (biome, box) ->
                val fitness = fitness(box, point)
                when {
                    fitness < bestFitness -> {
                        if (bestId != null && bestId != biome.id) {
                            runnerUp = bestId
                            runnerUpFitness = bestFitness
                        }
                        bestId = biome.id
                        bestArchetype = biome.archetype
                        bestFitness = fitness
                    }
                    fitness < runnerUpFitness && biome.id != bestId -> {
                        runnerUp = biome.id
                        runnerUpFitness = fitness
                    }
                }
            }

            val winner = bestId ?: return@repeat
            hits[winner] = (hits[winner] ?: 0) + 1
            bestArchetype?.let { archetypeHits[it] = (archetypeHits[it] ?: 0) + 1 }
            // A sample whose two best boxes are nearly tied is a sample standing
            // on the line between them, which is the only thing here that can
            // report adjacency without a world to walk.
            val neighbour = runnerUp
            if (neighbour != null && runnerUpFitness - bestFitness < BORDER_MARGIN) {
                val key = if (winner < neighbour) winner to neighbour else neighbour to winner
                borderHits[key] = (borderHits[key] ?: 0) + 1
            }
        }

        val shares = plan.biomes.map { biome ->
            BiomeShare(biome.id, biome.archetype, (hits[biome.id] ?: 0) / samples.toDouble())
        }.sortedByDescending { it.share }

        val notes = buildList {
            add(
                "Shares are of climate space under the measured noise distribution, not of walked ground. " +
                    "Treat them as ratios between biomes rather than exact areas.",
            )
            shares.firstOrNull()?.let { top ->
                if (top.share > DOMINANT_SHARE) {
                    add("${top.id} takes ${percent(top.share)} of the world on its own.")
                }
            }
            val absent = shares.filter { it.share == 0.0 }
            if (absent.isNotEmpty()) {
                add(
                    "${absent.size} biome(s) are never chosen: another box wins everywhere theirs reaches, " +
                        "so they exist in the document and nowhere in the world.",
                )
            }
            if (plan.spatial.regionScale != 1.0 || plan.spatial.boundaryRoughness != 0.0) {
                add(
                    "Spatial settings change biome patch diameter and border shape, not marginal climate shares; " +
                        "this report therefore keeps the same share model and does not predict contiguous region size.",
                )
            }
            val procedural = terrain.shape as? TerrainShape.Procedural
            if (procedural != null) {
                if (procedural.anchors.any { it.climateBias != null }) {
                    add(
                        "Anchor climate biases create local landmark biomes that this global climate-space sample " +
                            "does not spatially place; inspect those anchors separately.",
                    )
                }
                if (procedural.hydrology.riverCoverage > 0.0 || procedural.hydrology.lakeDensity > 0.0) {
                    add(
                        "River and lake biome signals are estimated as independent statistical masks at their authored " +
                            "coverage; the report includes their aquatic share but not their exact spatial overlap.",
                    )
                }
                if (procedural.bands.isNotEmpty()) {
                    add(
                        "Added or carved terrain bands can introduce extra vertical surfaces; their local depth " +
                            "distribution is omitted from this single-layer estimate.",
                    )
                }
            }
        }

        return DistributionReport(
            samples = samples,
            biomes = shares,
            landShare = land / samples.toDouble(),
            waterShare = 1.0 - land / samples.toDouble(),
            archetypes = BiomeArchetypeRole.entries
                .associateWith { (archetypeHits[it] ?: 0) / samples.toDouble() }
                .filterValues { it > 0.0 },
            absent = shares.filter { it.share == 0.0 }.map { it.id },
            rare = shares.filter { it.share > 0.0 && it.share < RARE_SHARE }.map { it.id },
            dominant = shares.filter { it.share > DOMINANT_SHARE }.map { it.id },
            borders = borderHits.entries
                .sortedByDescending { it.value }
                .take(MAX_BORDERS)
                .map { BiomeBorder(it.key.first, it.key.second, it.value / samples.toDouble()) },
            notes = notes,
        )
    }

    /** How close a sample sits to being in two boxes at once before it counts as a border. */
    private const val BORDER_MARGIN = 0.01

    /**
     * Minecraft's own fitness: nothing is added for an axis the sample is inside,
     * and the square of the miss for one it is outside.
     */
    private fun fitness(box: ClimateBox, point: DoubleArray): Double {
        var total = 0.0
        listOf(
            box.temperature, box.humidity, box.continentalness,
            box.erosion, box.depth, box.weirdness,
        ).forEachIndexed { axis, range ->
            val miss = missDistance(range, point[axis])
            total += miss * miss
        }
        return total + box.offset * box.offset
    }

    private fun missDistance(range: NumericRange, value: Double): Double =
        max(0.0, max(range.min - value, value - range.max))

    /**
     * Where the continentalness field is centred once the pack's land ratio is
     * folded in, mirroring the compiler's own log-odds calibration so the report
     * and the generated world describe the same coastline.
     */
    private fun continentalnessMean(terrain: TerrainPlan): Double {
        val shape = terrain.shape
        val landRatio = if (shape is TerrainShape.Procedural) shape.landRatio else 0.5
        return when {
            landRatio <= 0.0 -> -2.0
            landRatio >= 1.0 -> 2.0
            else -> COAST_EDGE + (CONTINENTALNESS_SIGMA / 1.702) * ln(landRatio / (1.0 - landRatio))
        }
    }

    /**
     * Mirrors the procedural compiler's authored relief mixture.
     *
     * The selector first chooses FLATS/HIGHLANDS/PEAKS according to the
     * document's relief weights. A separate erosion noise then adds the small
     * within-band texture used by WorldsmithNoiseSettings. Treating the result
     * as one normal distribution makes the analyzer invent the wrong relief
     * shares and, consequently, the wrong biome shares.
     */
    private fun sampleLandform(random: Random, terrain: TerrainPlan): Double {
        val shape = terrain.shape
        if (shape !is TerrainShape.Procedural) {
            return normal(random, 0.0, LANDFORM_TEXTURE_SIGMA)
        }

        val relief = shape.relief
        val total = relief.flats + relief.highlands + relief.peaks
        if (total <= 0.0) {
            return normal(random, 0.0, LANDFORM_TEXTURE_SIGMA)
        }
        val selected = random.nextDouble() * total
        val texture = normal(random, 0.0, LANDFORM_TEXTURE_SIGMA)
        return when {
            selected < relief.flats ->
                FLATS_LANDFORM_CENTER + texture * FLATS_LANDFORM_TEXTURE
            selected < relief.flats + relief.highlands ->
                HIGHLANDS_LANDFORM_CENTER + texture * HIGHLANDS_LANDFORM_TEXTURE
            else ->
                PEAKS_LANDFORM_CENTER + texture * PEAKS_LANDFORM_TEXTURE
        }
    }

    /**
     * Statistical counterpart of WorldsmithHydrology.waterBiomeSignal.
     *
     * Coverage describes the positive route/basin mask. Within that mask the
     * compiler promotes the inner part to water and keeps a short coast bank;
     * sampling the normalized strength here captures the large change in
     * aquatic shares without pretending this climate-space analyzer knows the
     * actual X/Z overlap of a river and a lake.
     */
    private fun sampleHydrology(random: Random, base: Double, terrain: TerrainPlan): Double {
        val shape = terrain.shape as? TerrainShape.Procedural ?: return base
        if (base <= -0.09) return base

        var result = base
        val hydrology = shape.hydrology
        if (hydrology.riverFill == com.wjz.worldsmith.core.model.RiverFill.FLUID) {
            result = sampleWaterMask(random, result, hydrology.riverCoverage, -0.30)
        }
        result = sampleWaterMask(random, result, hydrology.lakeDensity, -0.38)
        return result
    }

    private fun sampleWaterMask(random: Random, current: Double, coverage: Double, water: Double): Double {
        if (coverage <= 0.0 || random.nextDouble() >= coverage) return current
        val normalizedStrength = random.nextDouble()
        return when {
            normalizedStrength >= 0.24 -> water
            normalizedStrength >= 0.15 -> -0.15
            else -> current
        }
    }

    /** Box-Muller, clamped to the range the real fields were measured to reach. */
    private fun normal(random: Random, mean: Double, sigma: Double): Double {
        var u = random.nextDouble()
        while (u <= 0.0) {
            u = random.nextDouble()
        }
        val v = random.nextDouble()
        val z = kotlin.math.sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
        return min(1.5, max(-1.5, mean + sigma * z))
    }

    private fun percent(share: Double): String = "${(share * 1000).toInt() / 10.0}%"

    /** Exposed so a report and a diagnostic can agree on what counts as a lot. */
    fun isNoticeable(share: Double): Boolean = abs(share) >= RARE_SHARE
}
