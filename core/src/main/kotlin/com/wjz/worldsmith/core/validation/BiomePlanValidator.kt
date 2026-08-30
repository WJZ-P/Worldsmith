package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.feature.VegetationBudget
import com.wjz.worldsmith.core.model.BiomeDefinition
import com.wjz.worldsmith.core.model.BiomeEnvironment
import com.wjz.worldsmith.core.model.BiomePlan
import com.wjz.worldsmith.core.model.ClimateBands
import com.wjz.worldsmith.core.model.ClimateBox
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.NumericRange
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
    private val RESOURCE_ID = Regex("^[a-z0-9_.-]+:[a-z0-9/._-]+$")
    private const val MIN_FOG_END_DISTANCE = 16.0f
    private const val MAX_FOG_END_DISTANCE = 4096.0f

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
            biome.slot != null && biome.climate != null ->
                add(error(path + ".climate", "AMBIGUOUS_CLIMATE", "A biome declares either a slot or a raw climate, not both"))
            biome.slot == null && biome.climate == null ->
                add(error(path + ".slot", "MISSING_CLIMATE", "A biome must declare a climate slot or a raw climate box"))
        }
        biome.climate?.let { addAll(validateClimate(path + ".climate", it)) }

        // Bands are turned into one span, so a gap would make the box cover a
        // band the biome never asked for.
        biome.slot?.let { slot ->
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
        listOf(
            "grassColor" to environment.grassColor,
            "foliageColor" to environment.foliageColor,
            "waterColor" to environment.waterColor,
            "skyColor" to environment.skyColor,
            "fogColor" to environment.fogColor,
        ).forEach { (field, value) ->
            if (!HEX_COLOR.matches(value)) {
                add(error(path + "." + field, "INVALID_COLOR", "Color must be #RRGGBB but was " + value))
            }
        }
        if (environment.fogEndDistance !in MIN_FOG_END_DISTANCE..MAX_FOG_END_DISTANCE) {
            add(
                error(
                    path + ".fogEndDistance",
                    "FOG_DISTANCE_OUT_OF_RANGE",
                    "Fog end distance must be between " + MIN_FOG_END_DISTANCE + " and " + MAX_FOG_END_DISTANCE,
                ),
            )
        }

        environment.waterFog?.let { fog ->
            if (!HEX_COLOR.matches(fog.color)) {
                add(error(path + ".waterFog.color", "INVALID_COLOR", "Color must be #RRGGBB but was " + fog.color))
            }
            if (fog.endDistance <= 0.0f || fog.endDistance > MAX_FOG_END_DISTANCE) {
                add(
                    error(
                        path + ".waterFog.endDistance",
                        "FOG_DISTANCE_OUT_OF_RANGE",
                        "Water fog must end within 0 and " + MAX_FOG_END_DISTANCE,
                    ),
                )
            }
            if (fog.startDistance > fog.endDistance) {
                add(error(path + ".waterFog", "REVERSED_RANGE", "Water fog must not start after it ends"))
            }
        }

        environment.ambientParticles.forEachIndexed { index, particle ->
            val particlePath = path + ".ambientParticles[" + index + "]"
            if (!RESOURCE_ID.matches(particle.particle)) {
                add(
                    error(
                        particlePath + ".particle",
                        "INVALID_PARTICLE_ID",
                        "Particle must be a namespaced id but was " + particle.particle,
                    ),
                )
            }
            if (particle.probability !in 0.0f..1.0f) {
                add(
                    error(
                        particlePath + ".probability",
                        "PROBABILITY_OUT_OF_RANGE",
                        "Particle probability must be between 0 and 1",
                    ),
                )
            }
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
            conditions.hydrology == null
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

    private fun error(path: String, code: String, message: String) =
        Diagnostic(path, code, DiagnosticSeverity.ERROR, message)

    private const val MAX_SURFACE_RULES = 32
    private const val MAX_LAYER_DEPTH = 8
    private const val MAX_SURFACE_DEPTH = 8
    private const val MIN_SURFACE_Y = -2048
    private const val MAX_SURFACE_Y = 2048

}
