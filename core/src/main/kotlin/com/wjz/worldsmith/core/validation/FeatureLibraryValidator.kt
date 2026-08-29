package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.FeatureLibrary
import com.wjz.worldsmith.core.model.MaterialSelector

object FeatureLibraryValidator {
    internal val ID = Regex("^[a-z0-9_.-]+$")

    fun validate(library: FeatureLibrary): List<Diagnostic> = buildList {
        if (library.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported feature library schema ${library.schemaVersion}"))
        }

        library.features.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error("features[$id]", "DUPLICATE_FEATURE", "Feature id must be unique"))
        }

        library.features.forEachIndexed { index, feature ->
            val path = "features[$index]"
            if (!ID.matches(feature.id)) {
                add(error("$path.id", "INVALID_FEATURE_ID", "Feature id must match ${ID.pattern}"))
            }
            if (feature.density !in 0.0..1.0) {
                add(error("$path.density", "DENSITY_OUT_OF_RANGE", "Feature density must be between 0 and 1"))
            }
            addAll(validateMaterial("$path.block", feature.block))
        }
    }

    internal fun validateMaterial(path: String, selector: MaterialSelector): List<Diagnostic> = buildList {
        if (selector.semanticRole.isBlank()) {
            add(error("$path.semanticRole", "EMPTY_SEMANTIC_ROLE", "Material selector must name a semantic role"))
        }
        if (selector.preferredIds.isEmpty() && selector.requiredTags.isEmpty()) {
            add(error(path, "EMPTY_MATERIAL", "Material selector must list preferred ids or required tags"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
