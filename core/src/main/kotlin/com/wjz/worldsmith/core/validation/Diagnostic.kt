package com.wjz.worldsmith.core.validation

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

@Serializable
data class Diagnostic(
    val path: String,
    val code: String,
    val severity: DiagnosticSeverity,
    val message: String,
)
