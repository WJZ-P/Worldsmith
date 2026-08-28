package com.wjz.worldsmith.core.pipeline

import com.wjz.worldsmith.core.model.WorldBlueprint
import com.wjz.worldsmith.core.validation.Diagnostic

sealed interface GenerationResult {
    data class Success(val blueprint: WorldBlueprint) : GenerationResult
    data class Rejected(val diagnostics: List<Diagnostic>) : GenerationResult
}
