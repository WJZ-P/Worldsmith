package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.feature.VegetationBudget
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.AmbientParticleSpec
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomeFog
import com.wjz.worldsmith.core.model.BiomeLight
import com.wjz.worldsmith.core.model.BiomeSky
import com.wjz.worldsmith.core.model.BiomeTint
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.ClimateBands
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.ClimateCell
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.NumericRange
import com.wjz.worldsmith.core.model.ReliefBand
import com.wjz.worldsmith.core.model.VegetationRecipe
import com.wjz.worldsmith.core.model.SurfaceConditions
import com.wjz.worldsmith.core.model.SurfaceDefinition
import com.wjz.worldsmith.core.model.SurfaceStack

/**
 * Deterministic checks for a merged biome plan.
 *
 * Block identifiers are not checked here because core never sees the Minecraft
 * registries; the target compiler resolves every material selector and reports
 * what it had to fall back on.
 */
object BiomePlanValidator {
    private val ID = Regex("^[a-z0-9_.-]+$")
    private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val ARGB_COLOR = Regex("^#[0-9A-Fa-f]{8}$")
    private val RESOURCE_ID = Regex("^[a-z0-9_.-]+:[a-z0-9/._-]+$")
    private const val MAX_REPORTED_CELLS = 6
    private const val MIN_FOG_END_DISTANCE = 16.0f
    private const val MAX_FOG_END_DISTANCE = 4096.0f
    private const val MIN_CLOUD_HEIGHT = -64.0f
    private const val MAX_CLOUD_HEIGHT = 1024.0f

    /**
     * Particles that glow or are drawn large, so they read as something
     * happening rather than as weather.
     *
     * <p>Vanilla's four ambient particles are all small, dim and short-lived,
     * and it uses none of these. {@code end_rod} is the one a model reaches for
     * when a prompt says magic: it is a bright white spark vanilla only ever
     * emits from a light source, and at a probability that would be invisible
     * for ash it fills the screen.
     *
     * <p>Every id here is one Minecraft can build from its name alone. A
     * particle needing a block or a colour cannot be an ambient particle at
     * all, so listing one would be listing something that never renders.
     */
    private val OBTRUSIVE_PARTICLES = setOf(
        "minecraft:electric_spark",
        "minecraft:enchant",
        "minecraft:end_rod",
        "minecraft:explosion",
        "minecraft:explosion_emitter",
        "minecraft:firework",
        "minecraft:flame",
        "minecraft:glow",
        "minecraft:glow_squid_ink",
        "minecraft:happy_villager",
        "minecraft:heart",
        "minecraft:lava",
        "minecraft:nautilus",
        "minecraft:sculk_soul",
        "minecraft:soul",
        "minecraft:soul_fire_flame",
        "minecraft:totem_of_undying",
        "minecraft:witch",
    )

    /** Below vanilla's quietest ambient particle, because these are louder at any rate. */
    private const val MAX_OBTRUSIVE_PARTICLE_PROBABILITY = 0.005f

    /** Twice the densest thing vanilla or the built-in pack does. */
    private const val MAX_PARTICLE_PROBABILITY = 0.25f

    fun validate(plan: BiomePlan, features: FeatureLibrary): List<Diagnostic> = buildList {
        if (plan.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported biome plan schema " + plan.schemaVersion))
        }
        if (plan.biomes.isEmpty()) {
            add(error("biomes", "EMPTY_BIOME_PLAN", "A pack must define at least one biome"))
        }

        plan.biomes.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error("biomes[" + id + "]", "DUPLICATE_BIOME", "Biome id must be unique"))
        }

        val featureIds = features.features.mapTo(linkedSetOf()) { it.id }
        plan.biomes.forEachIndexed { index, biome ->
            addAll(validateBiome("biomes[" + index + "]", biome, features, featureIds))
        }

        addAll(reportCoverage(plan))
        addAll(reportSurvivability(plan, features))
    }

    private fun validateBiome(
        path: String,
        biome: BiomeDefinition,
        features: FeatureLibrary,
        featureIds: Set<String>,
    ): List<Diagnostic> = buildList {
        if (!ID.matches(biome.id)) {
            add(error(path + ".id", "INVALID_BIOME_ID", "Biome id must match " + ID.pattern))
        }
        if (biome.displayName.isBlank()) {
            add(error(path + ".displayName", "EMPTY_DISPLAY_NAME", "Biome display name must not be blank"))
        }

        when {
            biome.placements.isNotEmpty() && (biome.slot != null || biome.climate != null) ->
                add(
                    error(
                        path + ".placements",
                        "AMBIGUOUS_CLIMATE",
                        "A biome uses the placements list or the slot/climate shorthand, not both",
                    ),
                )
            biome.slot != null && biome.climate != null ->
                add(error(path + ".climate", "AMBIGUOUS_CLIMATE", "A biome declares either a slot or a raw climate, not both"))
            biome.allPlacements.isEmpty() ->
                add(error(path + ".slot", "MISSING_CLIMATE", "A biome must declare a climate slot or a raw climate box"))
        }
        biome.placements.forEachIndexed { index, placement ->
            val placementPath = path + ".placements[" + index + "]"
            when {
                placement.slot != null && placement.climate != null ->
                    add(error(placementPath, "AMBIGUOUS_CLIMATE", "A placement declares either a slot or a raw climate, not both"))
                placement.slot == null && placement.climate == null ->
                    add(error(placementPath, "MISSING_CLIMATE", "A placement must declare a climate slot or a raw climate box"))
            }
        }
        biome.allPlacements.forEachIndexed { index, placement ->
            placement.climate?.let { addAll(validateClimate(path + ".placements[" + index + "].climate", it)) }
        }

        // Bands are turned into one span, so a gap would make the box cover a
        // band the biome never asked for.
        biome.allPlacements.mapNotNull { it.slot }.forEach { slot ->
            if (!ClimateBands.isContiguous(slot.temperature)) {
                add(
                    error(
                        path + ".slot.temperature",
                        "NON_CONTIGUOUS_BANDS",
                        "Temperature bands must be adjacent and distinct but were " + slot.temperature,
                    ),
                )
            }
            if (!ClimateBands.isContiguous(slot.humidity)) {
                add(
                    error(
                        path + ".slot.humidity",
                        "NON_CONTIGUOUS_BANDS",
                        "Humidity bands must be adjacent and distinct but were " + slot.humidity,
                    ),
                )
            }
        }

        if (biome.behavior.temperature !in -2.0f..2.0f) {
            add(error(path + ".behavior.temperature", "TEMPERATURE_OUT_OF_RANGE", "Temperature must be between -2 and 2"))
        }
        if (biome.behavior.downfall !in 0.0f..1.0f) {
            add(error(path + ".behavior.downfall", "DOWNFALL_OUT_OF_RANGE", "Downfall must be between 0 and 1"))
        }

        addAll(validateEnvironment(path + ".environment", biome.environment))

        addAll(validateSurface(path + ".surface", biome.surface))

        (biome.tags.add + biome.tags.remove).forEachIndexed { index, tag ->
            if (!RESOURCE_ID.matches(tag)) {
                add(error(path + ".tags[" + index + "]", "INVALID_TAG_ID", "Biome tag must be a namespaced id but was " + tag))
            }
        }

        biome.features.groupingBy { it.feature }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error(path + ".features", "DUPLICATE_FEATURE_REFERENCE", "Feature " + id + " is referenced more than once"))
        }

        var attempts = 0.0
        biome.features.forEachIndexed { index, ref ->
            val refPath = path + ".features[" + index + "]"
            val definition = features.features.firstOrNull { it.id == ref.feature }
            if (definition == null) {
                add(error(refPath + ".feature", "UNKNOWN_FEATURE", "Biome references unknown feature " + ref.feature))
                return@forEachIndexed
            }
            val density = ref.density ?: definition.density
            if (density !in 0.0..1.0) {
                add(error(refPath + ".density", "DENSITY_OUT_OF_RANGE", "Feature density override must be between 0 and 1"))
                return@forEachIndexed
            }
            attempts += VegetationBudget.attemptsPerChunk(definition.recipe, density)
        }
        if (attempts > VegetationBudget.MAX_ATTEMPTS_PER_CHUNK) {
            add(
                error(
                    path + ".features",
                    "VEGETATION_BUDGET_EXCEEDED",
                    "Biome spends " + attempts + " vegetation attempts per chunk, the cap is " +
                        VegetationBudget.MAX_ATTEMPTS_PER_CHUNK,
                ),
            )
        }
    }

    private fun validateEnvironment(path: String, environment: BiomeEnvironment): List<Diagnostic> = buildList {
        addAll(validateTint("$path.tint", environment.tint))
        addAll(validateFog("$path.fog", environment.fog))
        addAll(validateSky("$path.sky", environment.sky))
        addAll(validateLight("$path.light", environment.light))
        addAll(validateParticles("$path.ambientParticles", environment.ambientParticles))
    }

    private fun validateTint(path: String, tint: BiomeTint): List<Diagnostic> = buildList {
        addAll(rgb("$path.grass", tint.grass))
        addAll(rgb("$path.foliage", tint.foliage))
        addAll(rgb("$path.water", tint.water))
    }

    private fun validateFog(path: String, fog: BiomeFog): List<Diagnostic> = buildList {
        addAll(rgb("$path.color", fog.color))
        if (fog.endDistance !in MIN_FOG_END_DISTANCE..MAX_FOG_END_DISTANCE) {
            add(error("$path.endDistance", "FOG_DISTANCE_OUT_OF_RANGE", "Fog must end between $MIN_FOG_END_DISTANCE and $MAX_FOG_END_DISTANCE"))
        }
        if (fog.startDistance > fog.endDistance) {
            add(error(path, "REVERSED_RANGE", "Fog must not start after it ends"))
        }
        listOf("skyEndDistance" to fog.skyEndDistance, "cloudEndDistance" to fog.cloudEndDistance).forEach { (field, value) ->
            if (value != null && value !in 0.0f..MAX_FOG_END_DISTANCE) {
                add(error("$path.$field", "FOG_DISTANCE_OUT_OF_RANGE", "Fog distance must be between 0 and $MAX_FOG_END_DISTANCE"))
            }
        }
        fog.water?.let { water ->
            addAll(rgb("$path.water.color", water.color))
            if (water.endDistance <= 0.0f || water.endDistance > MAX_FOG_END_DISTANCE) {
                add(error("$path.water.endDistance", "FOG_DISTANCE_OUT_OF_RANGE", "Water fog must end within 0 and $MAX_FOG_END_DISTANCE"))
            }
            if (water.startDistance > water.endDistance) {
                add(error("$path.water", "REVERSED_RANGE", "Water fog must not start after it ends"))
            }
        }
    }

    private fun validateSky(path: String, sky: BiomeSky): List<Diagnostic> = buildList {
        addAll(rgb("$path.color", sky.color))
        addAll(argb("$path.cloudColor", sky.cloudColor))
        addAll(argb("$path.sunriseSunsetColor", sky.sunriseSunsetColor))
        addAll(unit("$path.starBrightness", sky.starBrightness))
        val height = sky.cloudHeight
        if (height != null && height !in MIN_CLOUD_HEIGHT..MAX_CLOUD_HEIGHT) {
            add(error("$path.cloudHeight", "CLOUD_HEIGHT_OUT_OF_RANGE", "Cloud height must be between $MIN_CLOUD_HEIGHT and $MAX_CLOUD_HEIGHT"))
        }
    }

    private fun validateLight(path: String, light: BiomeLight): List<Diagnostic> = buildList {
        addAll(rgb("$path.skyColor", light.skyColor))
        addAll(rgb("$path.ambientColor", light.ambientColor))
        addAll(rgb("$path.blockTint", light.blockTint))
        addAll(unit("$path.skyFactor", light.skyFactor))
    }

    private fun validateParticles(path: String, particles: List<AmbientParticleSpec>): List<Diagnostic> = buildList {
        particles.forEachIndexed { index, particle ->
            val particlePath = "$path[$index]"
            if (!RESOURCE_ID.matches(particle.particle)) {
                add(error("$particlePath.particle", "INVALID_PARTICLE_ID", "Particle must be a namespaced id but was " + particle.particle))
            }
            addAll(unit("$particlePath.probability", particle.probability))
            if (particle.particle in OBTRUSIVE_PARTICLES &&
                particle.probability > MAX_OBTRUSIVE_PARTICLE_PROBABILITY
            ) {
                add(
                    warning(
                        "$particlePath.probability",
                        "OBTRUSIVE_AMBIENT_PARTICLE",
                        "${particle.particle} glows and is drawn large, so at ${particle.probability} it reads " +
                            "as an effect rather than as air and quickly becomes tiring to stand in. Keep it at " +
                            "or below $MAX_OBTRUSIVE_PARTICLE_PROBABILITY, or choose a small dim particle like " +
                            "ash or a spore, which carry vanilla's range.",
                    ),
                )
            }
            if (particle.probability > MAX_PARTICLE_PROBABILITY) {
                add(
                    warning(
                        "$particlePath.probability",
                        "DENSE_AMBIENT_PARTICLE",
                        "${particle.probability} is denser than anything vanilla does; its heaviest ambient " +
                            "particle is 0.118. This will read as falling snow rather than as atmosphere.",
                    ),
                )
            }
        }
    }

    private fun rgb(path: String, value: String?): List<Diagnostic> = buildList {
        if (value != null && !HEX_COLOR.matches(value)) {
            add(error(path, "INVALID_COLOR", "Color must be #RRGGBB but was $value"))
        }
    }

    /** Cloud and sunrise colours carry alpha, which is what lets a sky have no clouds. */
    private fun argb(path: String, value: String?): List<Diagnostic> = buildList {
        if (value != null && !ARGB_COLOR.matches(value)) {
            add(error(path, "INVALID_COLOR", "Color must be #AARRGGBB but was $value"))
        }
    }

    /** Minecraft clamps these to 0..1 silently, so a pack out of range is a typo worth reporting. */
    private fun unit(path: String, value: Float?): List<Diagnostic> = buildList {
        if (value != null && value !in 0.0f..1.0f) {
            add(error(path, "UNIT_RANGE_OUT_OF_BOUNDS", "Value must be between 0 and 1 but was $value"))
        }
    }

    private fun validateSurface(path: String, surface: SurfaceDefinition): List<Diagnostic> = buildList {
        addAll(validateStack("$path.base", surface.base))
        if (surface.rules.size > MAX_SURFACE_RULES) {
            add(error("$path.rules", "TOO_MANY_SURFACE_RULES", "A biome may define at most $MAX_SURFACE_RULES surface rules"))
        }
        surface.rules.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error("$path.rules[$id]", "DUPLICATE_SURFACE_RULE", "Surface rule id must be unique inside its biome"))
        }
        surface.rules.forEachIndexed { index, rule ->
            val rulePath = "$path.rules[$index]"
            if (!ID.matches(rule.id)) {
                add(error("$rulePath.id", "INVALID_SURFACE_RULE_ID", "Surface rule id must match ${ID.pattern}"))
            }
            addAll(validateConditions("$rulePath.conditions", rule.conditions))
            addAll(validateStack("$rulePath.stack", rule.stack))
        }
    }

    private fun validateConditions(path: String, conditions: SurfaceConditions): List<Diagnostic> = buildList {
        if (
            conditions.altitude == null &&
            conditions.slope == null &&
            conditions.water == null &&
            conditions.temperature == null &&
            conditions.noise == null &&
            conditions.hydrology == null &&
            conditions.anchor == null
        ) {
            add(error(path, "EMPTY_SURFACE_CONDITIONS", "A surface override must declare at least one condition"))
        }
        conditions.altitude?.let { altitude ->
            if (altitude.min == null && altitude.max == null) {
                add(error("$path.altitude", "EMPTY_ALTITUDE_RANGE", "Altitude must declare min, max or both"))
            }
            if (altitude.min != null && altitude.max != null && altitude.min > altitude.max) {
                add(error("$path.altitude", "REVERSED_RANGE", "Altitude minimum must not exceed its maximum"))
            }
            listOfNotNull(altitude.min, altitude.max).forEach { value ->
                if (value !in MIN_SURFACE_Y..MAX_SURFACE_Y) {
                    add(error("$path.altitude", "ALTITUDE_OUT_OF_RANGE", "Altitude must remain between $MIN_SURFACE_Y and $MAX_SURFACE_Y"))
                }
            }
        }
        conditions.noise?.let { noise ->
            if (noise.min > noise.max) {
                add(error("$path.noise", "REVERSED_RANGE", "Noise minimum must not exceed its maximum"))
            }
            if (noise.min !in -2.0..2.0 || noise.max !in -2.0..2.0) {
                add(error("$path.noise", "NOISE_RANGE_OUT_OF_BOUNDS", "Surface noise ranges must remain between -2 and 2"))
            }
        }
    }

    private fun validateStack(path: String, stack: SurfaceStack): List<Diagnostic> = buildList {
        if (stack.layers.isEmpty()) {
            add(error("$path.layers", "EMPTY_SURFACE_STACK", "A surface stack must contain at least one exposed layer"))
        }
        var totalDepth = 0
        stack.layers.forEachIndexed { index, layer ->
            if (layer.depth !in 1..MAX_LAYER_DEPTH) {
                add(error("$path.layers[$index].depth", "LAYER_DEPTH_OUT_OF_RANGE", "Layer depth must be between 1 and $MAX_LAYER_DEPTH"))
            } else {
                totalDepth += layer.depth
            }
            addAll(FeatureLibraryValidator.validateMaterial("$path.layers[$index].material", layer.material))
        }
        if (totalDepth > MAX_SURFACE_DEPTH) {
            add(error("$path.layers", "SURFACE_STACK_TOO_DEEP", "Combined surface layer depth must not exceed $MAX_SURFACE_DEPTH"))
        }
        addAll(FeatureLibraryValidator.validateMaterial("$path.foundation", stack.foundation))
    }

    /**
     * Reports what the semantic grid does and does not cover.
     *
     * None of this blocks. A pack may deliberately name three biomes and let
     * Minecraft's nearest-neighbour search fill everything else, and a prompt
     * asking for that is not wrong. What would be wrong is not knowing: an
     * unclaimed square is silently handed to whichever biome is closest, which
     * looks in-game like a region that was never designed rather than like a
     * gap, so it is reported and left to the author.
     */
    /**
     * Reports a world the player cannot start in.
     *
     * <p>Minecraft survival begins by punching a tree: no wood means no
     * crafting table, no tools, and nothing to do but starve. A pack can only
     * be checked structurally here, because core never sees the Minecraft
     * registries and so cannot know whether a block id names a log; the target
     * compiler checks that half. What can be checked is whether any land biome
     * grows anything tree-shaped at all, which is where this usually fails.
     */
    private fun reportSurvivability(plan: BiomePlan, features: FeatureLibrary): List<Diagnostic> = buildList {
        val trunks = features.features.filter { it.recipe == VegetationRecipe.DEAD_TREE }.mapTo(mutableSetOf()) { it.id }
        if (trunks.isEmpty()) {
            add(
                warning(
                    "features",
                    "NO_WOOD_IN_WORLD",
                    "No feature uses the DEAD_TREE recipe, so the world grows no wood. A player cannot craft " +
                        "anything without it. Even a dead world can carry a petrified trunk or a wrecked mast.",
                ),
            )
            return@buildList
        }

        val reachable = plan.biomes.any { biome ->
            biome.archetype.isLand && biome.features.any { it.feature in trunks }
        }
        if (!reachable) {
            add(
                warning(
                    "biomes",
                    "NO_WOOD_ON_LAND",
                    "Wood is declared but no land biome grows it, so a player who spawns ashore can never craft. " +
                        "Reference a DEAD_TREE feature from a biome the player is likely to meet early.",
                ),
            )
        }
    }

    private fun reportCoverage(plan: BiomePlan): List<Diagnostic> = buildList {
        val rawBoxes = plan.biomes.filter { biome -> biome.allPlacements.any { it.climate != null } }
        if (rawBoxes.isNotEmpty()) {
            add(
                warning(
                    "biomes",
                    "CLIMATE_COVERAGE_UNPROVEN",
                    rawBoxes.size.toString() + " biome(s) place themselves with a raw climate box, so which " +
                        "semantic squares are covered cannot be determined here",
                ),
            )
            return@buildList
        }

        val owners = linkedMapOf<ClimateCell, MutableList<String>>()
        plan.biomes.forEach { biome ->
            biome.allPlacements.mapNotNull { it.slot }.forEach { slot ->
                ClimateBands.cells(slot).forEach { cell ->
                    owners.getOrPut(cell) { mutableListOf() }.addAll(listOf(biome.id).filterNot { it in owners[cell].orEmpty() })
                }
            }
        }

        owners.filterValues { it.size > 1 }.forEach { (cell, claimants) ->
            add(
                warning(
                    "biomes",
                    "OVERLAPPING_CLIMATE_CELL",
                    "Square " + cell + " is claimed by " + claimants.joinToString(", ") +
                        "; the last one wins and the others never generate there",
                ),
            )
        }

        addAll(reportVariety(owners))

        val unclaimed = ClimateBands.ALL_CELLS.filterNot(owners::containsKey)
        if (unclaimed.isNotEmpty()) {
            val shown = unclaimed.take(MAX_REPORTED_CELLS).joinToString(", ")
            val rest = if (unclaimed.size > MAX_REPORTED_CELLS) " and " + (unclaimed.size - MAX_REPORTED_CELLS) + " more" else ""
            add(
                warning(
                    "biomes",
                    "UNCLAIMED_CLIMATE_CELL",
                    unclaimed.size.toString() + " of " + ClimateBands.ALL_CELLS.size + " semantic squares are " +
                        "claimed by no biome and will be filled by the nearest one instead: " + shown + rest,
                ),
            )
        }
    }

    /**
     * Reports a band a player can cross without the world changing.
     *
     * <p>Coverage asks whether every square is claimed and stops there, so a
     * plan can score a perfect partition and still be a topographic map: relief
     * is altitude, and a band claimed by one biome is that biome at that height
     * everywhere, forever. The only border such a world has is a contour line,
     * which is exactly what it looks like from a hilltop.
     *
     * <p>Only bands above water are checked. One deep ocean is a reasonable
     * decision; one plains is a world with a single field in it. Nothing is
     * reported for a plan using raw climate boxes, because the caller has
     * already returned by then - which band a raw box lands in is not knowable
     * from the box alone.
     */
    private fun reportVariety(owners: Map<ClimateCell, List<String>>): List<Diagnostic> = buildList {
        ReliefBand.entries.filter { it.isLand }.forEach { band ->
            val claimants = owners.entries
                .filter { it.key.relief == band }
                .flatMapTo(linkedSetOf()) { it.value }
            if (claimants.size == 1) {
                add(
                    warning(
                        "biomes",
                        "MONOTONE_RELIEF_BAND",
                        "Every " + band + " square belongs to " + claimants.single() + ", so that biome is the " +
                            "whole world at that height and the only border a player crosses is a contour line. " +
                            "Split the band on temperature or humidity, which vary horizontally.",
                    ),
                )
            }
        }
    }

    fun validateClimate(path: String, climate: ClimateBox): List<Diagnostic> = buildList {
        listOf(
            "temperature" to climate.temperature,
            "humidity" to climate.humidity,
            "continentalness" to climate.continentalness,
            "erosion" to climate.erosion,
            "depth" to climate.depth,
            "weirdness" to climate.weirdness,
        ).forEach { (name, range) -> addAll(validateRange(path + "." + name, range)) }
        if (climate.offset !in 0.0f..1.0f) {
            add(error(path + ".offset", "OFFSET_OUT_OF_RANGE", "Climate offset must be between 0 and 1"))
        }
    }

    private fun validateRange(path: String, range: NumericRange): List<Diagnostic> = buildList {
        if (range.min > range.max) {
            add(error(path, "REVERSED_RANGE", "Range minimum must not exceed its maximum"))
        }
        if (range.min !in -2.0f..2.0f || range.max !in -2.0f..2.0f) {
            add(error(path, "CLIMATE_RANGE_OUT_OF_BOUNDS", "Climate ranges must remain between -2 and 2"))
        }
    }

    private fun warning(path: String, code: String, message: String) =
        Diagnostic(path, code, DiagnosticSeverity.WARNING, message)

    private fun error(path: String, code: String, message: String) =
        Diagnostic(path, code, DiagnosticSeverity.ERROR, message)

    private const val MAX_SURFACE_RULES = 32
    private const val MAX_LAYER_DEPTH = 8
    private const val MAX_SURFACE_DEPTH = 8
    private const val MIN_SURFACE_Y = -2048
    private const val MAX_SURFACE_Y = 2048

}
