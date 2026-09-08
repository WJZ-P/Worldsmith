package com.wjz.worldsmith.core.validation

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

@Serializable
data class Diagnostic @JvmOverloads constructor(
    val path: String,
    val code: String,
    val severity: DiagnosticSeverity,
    val message: String,
    val stage:String?=null,
    val structureId:String?=null,
    val componentId:String?=null,
    val position:com.wjz.worldsmith.core.structure.BuildPos?=null,
    val region:com.wjz.worldsmith.core.structure.BuildBox?=null,
    val expected:String?=null,
    val actual:String?=null,
    val hint:String?=null,
    val metrics:Map<String,Int> = emptyMap(),
)
