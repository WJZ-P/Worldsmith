package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.WorldsmithCore
import com.wjz.worldsmith.core.model.StructureBrief
import com.wjz.worldsmith.core.model.WorldBlueprint
import com.wjz.worldsmith.core.model.WorldGenerationRequest

object WorldBlueprintValidator {
    private const val MAX_STRUCTURE_COUNT = 64

    fun validateRequest(request: WorldGenerationRequest): List<Diagnostic> = buildList {
        if (request.playerPrompt.isBlank()) {
            add(error("playerPrompt", "EMPTY_PROMPT", "Player prompt must not be blank"))
        }
        if (request.requestedStructureCount !in 1..MAX_STRUCTURE_COUNT) {
            add(
                error(
                    "requestedStructureCount",
                    "STRUCTURE_COUNT_OUT_OF_RANGE",
                    "Requested structure count must be between 1 and $MAX_STRUCTURE_COUNT",
                ),
            )
        }
    }

    fun validateBriefs(request: WorldGenerationRequest, briefs: List<StructureBrief>): List<Diagnostic> = buildList {
        if (briefs.size != request.requestedStructureCount) {
            add(
                error(
                    "structureBriefs",
                    "STRUCTURE_COUNT_MISMATCH",
                    "Expected ${request.requestedStructureCount} structure briefs but received ${briefs.size}",
                ),
            )
        }

        val duplicateIds = briefs.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.sorted().forEach { id ->
            add(error("structureBriefs[$id]", "DUPLICATE_STRUCTURE_ID", "Structure brief id must be unique"))
        }

        briefs.forEachIndexed { index, brief ->
            if (brief.id.isBlank()) {
                add(error("structureBriefs[$index].id", "EMPTY_STRUCTURE_ID", "Structure brief id must not be blank"))
            }
            if (brief.footprint.width <= 0 || brief.footprint.depth <= 0) {
                add(error("structureBriefs[$index].footprint", "INVALID_FOOTPRINT", "Structure footprint must be positive"))
            }
            if (brief.footprint.minHeight <= 0 || brief.footprint.maxHeight < brief.footprint.minHeight) {
                add(error("structureBriefs[$index].footprint", "INVALID_HEIGHT_RANGE", "Structure height range is invalid"))
            }
        }
    }

    fun validateBlueprint(blueprint: WorldBlueprint): List<Diagnostic> = buildList {
        addAll(validateRequest(blueprint.request))
        addAll(validateBriefs(blueprint.request, blueprint.structureBriefs))

        if (blueprint.schemaVersion != WorldsmithCore.BLUEPRINT_SCHEMA_VERSION) {
            add(error("schemaVersion", "UNSUPPORTED_SCHEMA", "Unsupported blueprint schema ${blueprint.schemaVersion}"))
        }
        if (blueprint.bible.architecture.decayLevel !in 0.0..1.0) {
            add(error("bible.architecture.decayLevel", "DECAY_OUT_OF_RANGE", "Decay level must be between 0 and 1"))
        }

        val briefIds = blueprint.structureBriefs.mapTo(linkedSetOf()) { it.id }
        val definitionIds = blueprint.structures.map { it.id }
        val definitionBriefIds = blueprint.structures.map { it.briefId }
        definitionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            add(error("structures[$id]", "DUPLICATE_STRUCTURE_DEFINITION", "Structure definition id must be unique"))
        }
        definitionBriefIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { briefId ->
            add(error("structures[$briefId]", "DUPLICATE_BRIEF_DEFINITION", "Each structure brief must be defined exactly once"))
        }
        blueprint.structures.forEachIndexed { index, definition ->
            if (definition.briefId !in briefIds) {
                add(
                    error(
                        "structures[$index].briefId",
                        "UNKNOWN_STRUCTURE_BRIEF",
                        "Structure definition references unknown brief '${definition.briefId}'",
                    ),
                )
            }
        }
        (briefIds - definitionBriefIds.toSet()).sorted().forEach { briefId ->
            add(error("structures", "MISSING_STRUCTURE_DEFINITION", "Structure brief '$briefId' has no definition"))
        }
    }

    private fun error(path: String, code: String, message: String) = Diagnostic(
        path = path,
        code = code,
        severity = DiagnosticSeverity.ERROR,
        message = message,
    )
}
