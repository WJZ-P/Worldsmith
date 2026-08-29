package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.MaterialSelector
import com.wjz.worldsmith.core.model.TerrainPlan

object TerrainPlanValidator {
    fun validate(plan: TerrainPlan): List<Diagnostic> = buildList {
        if (plan.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported terrain schema ${plan.schemaVersion}"))
        }
        if (plan.minY % 16 != 0) {
            add(error("minY", "MIN_Y_ALIGNMENT", "Minimum Y must be divisible by 16"))
        }
        if (plan.height <= 0 || plan.height % 16 != 0) {
            add(error("height", "HEIGHT_ALIGNMENT", "Height must be positive and divisible by 16"))
        }
        if (plan.horizontalNoiseSize !in 1..4 || plan.verticalNoiseSize !in 1..4) {
            add(error("noiseSize", "NOISE_SIZE_OUT_OF_RANGE", "Noise sizes must be between 1 and 4"))
        }
        if (plan.seaLevel !in plan.minY until (plan.minY + plan.height)) {
            add(error("seaLevel", "SEA_LEVEL_OUT_OF_RANGE", "Sea level must be inside the terrain height range"))
        }
        addAll(validateMaterial("defaultBlock", plan.defaultBlock))
        addAll(validateMaterial("defaultFluid", plan.defaultFluid))
        if (plan.spawnTargets.isEmpty()) {
            add(error("spawnTargets", "EMPTY_SPAWN_TARGETS", "At least one spawn target is required"))
        }
        plan.spawnTargets.forEachIndexed { index, target ->
            addAll(BiomePlanValidator.validateClimate("spawnTargets[$index]", target))
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

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
