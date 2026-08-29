package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.BiomeLayoutPlan
import com.wjz.worldsmith.core.model.BiomeSkin
import com.wjz.worldsmith.core.model.BiomeSkinSet
import com.wjz.worldsmith.core.model.MaterialSelector

/**
 * Deterministic checks for a generated biome skin set.
 *
 * Block identifiers cannot be checked here because core never sees the
 * Minecraft registries. The target compiler resolves every [MaterialSelector]
 * and reports unresolved ones itself.
 */
object BiomeSkinValidator {
    private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private const val MIN_FOG_END_DISTANCE = 16.0f
    private const val MAX_FOG_END_DISTANCE = 4096.0f

    fun validate(set: BiomeSkinSet, layout: BiomeLayoutPlan): List<Diagnostic> = buildList {
        if (set.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported biome skin schema ${set.schemaVersion}"))
        }
        val known = layout.skeletons.mapTo(linkedSetOf()) { it.id }
        val seen = linkedSetOf<String>()

        set.skins.forEachIndexed { index, skin ->
            val path = "skins[$index]"
            if (skin.skeletonId !in known) {
                add(error("$path.skeletonId", "UNKNOWN_SKELETON", "Unknown biome skeleton '${skin.skeletonId}'"))
            } else if (!seen.add(skin.skeletonId)) {
                add(error("$path.skeletonId", "DUPLICATE_SKELETON", "Skeleton '${skin.skeletonId}' is skinned more than once"))
            }
            addAll(validateSkin(path, skin))
        }

        (known - seen).sorted().forEach { skeletonId ->
            add(error("skins", "MISSING_SKELETON", "Skeleton '$skeletonId' has no skin"))
        }
    }

    private fun validateSkin(path: String, skin: BiomeSkin): List<Diagnostic> = buildList {
        if (skin.displayName.isBlank()) {
            add(error("$path.displayName", "EMPTY_DISPLAY_NAME", "Biome display name must not be blank"))
        }

        val colors = skin.colors
        listOf(
            "grass" to colors.grass,
            "foliage" to colors.foliage,
            "water" to colors.water,
            "sky" to colors.sky,
            "fog" to colors.fog,
        ).forEach { (field, value) ->
            if (!HEX_COLOR.matches(value)) {
                add(error("$path.colors.$field", "INVALID_COLOR", "Color must be #RRGGBB but was '$value'"))
            }
        }
        if (colors.fogEndDistance !in MIN_FOG_END_DISTANCE..MAX_FOG_END_DISTANCE) {
            add(
                error(
                    "$path.colors.fogEndDistance",
                    "FOG_DISTANCE_OUT_OF_RANGE",
                    "Fog end distance must be between $MIN_FOG_END_DISTANCE and $MAX_FOG_END_DISTANCE",
                ),
            )
        }

        addAll(validateMaterial("$path.surface.top", skin.surface.top))
        addAll(validateMaterial("$path.surface.under", skin.surface.under))
        addAll(validateMaterial("$path.surface.deep", skin.surface.deep))
        skin.surface.steepOverride?.let { addAll(validateMaterial("$path.surface.steepOverride", it)) }

        skin.vegetation.forEachIndexed { index, slot ->
            val slotPath = "$path.vegetation[$index]"
            if (slot.density !in 0.0..1.0) {
                add(error("$slotPath.density", "DENSITY_OUT_OF_RANGE", "Vegetation density must be between 0 and 1"))
            }
            addAll(validateMaterial("$slotPath.block", slot.block))
        }
    }

    private fun validateMaterial(path: String, selector: MaterialSelector): List<Diagnostic> = buildList {
        if (selector.semanticRole.isBlank()) {
            add(error("$path.semanticRole", "EMPTY_SEMANTIC_ROLE", "Material selector must name a semantic role"))
        }
        if (selector.preferredIds.isEmpty() && selector.requiredTags.isEmpty()) {
            add(error(path, "EMPTY_MATERIAL", "Material selector must list preferred ids or required tags"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(
        path = path,
        code = code,
        severity = DiagnosticSeverity.ERROR,
        message = message,
    )
}
