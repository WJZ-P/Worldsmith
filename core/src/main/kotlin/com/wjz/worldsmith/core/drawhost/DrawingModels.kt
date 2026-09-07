package com.wjz.worldsmith.core.drawhost

import kotlinx.serialization.Serializable
import java.nio.file.Path

object DrawingVersions { const val NATIVE_COMPILER = "native-structure-3" }

@Serializable data class DrawingRequest(
    val name: String, val requestId: String, val entryClass: String,
    val sources: Map<String, String>, val seeds: List<Long> = listOf(0), val parameters: Map<String, String> = emptyMap(),
)
@Serializable enum class DrawingJobStage { WAITING_APPROVAL, QUEUED, COMPILING, DRAWING, VALIDATING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
@Serializable data class DrawingDiagnostic(val file: String = "", val line: Long = -1, val column: Long = -1, val severity: String = "ERROR", val message: String)
@Serializable data class DrawingJob(
    val id: String, val sessionId: String, val revision: Long, val inputHash: String,
    val request: DrawingRequest, val stage: DrawingJobStage, val drawingIds: List<String> = emptyList(),
    val diagnostics: List<DrawingDiagnostic> = emptyList(), val message: String = "", val log: String = "",
)
@Serializable data class DrawingSourceRecord(
    val hash: String, val entryClass: String, val files: Map<String,String>,
    val compilerVersion: String = "ecj-3.46.0", val sdkVersion: String = "draw-1",
)
@Serializable data class DrawingArtifact(
    val id: String, val dataHash: String, val sourceHash: String, val seed: Long,
    val parameters: Map<String,String>, val targetDataVersion: Int,
    val sessionId: String, val jobId: String, val name: String, val revision: Long,
    val compilerVersion: String = "ecj-3.46.0", val sdkVersion: String = "draw-1", val codecVersion: Int = 1,
    val nativeCompilerVersion: String = DrawingVersions.NATIVE_COMPILER,
) {
    val path: String get() = "drawings/$id.wsdraw"
}
data class DrawingRuntime @JvmOverloads constructor(val directory: Path, val javaHome: Path, val jvmArguments: List<String> = emptyList())
data class DrawingExecutionLimits @JvmOverloads constructor(val compileSeconds: Long = 30, val drawSeconds: Long = 120, val heapMiB: Int = 1024)
