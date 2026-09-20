package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.drawhost.DrawingRuntime
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** Trusted JVM extension, not a sandbox. Only instantiate after installation approval and restart. */
interface AbilityExtension {
    fun spec(): AbilityCapabilitySpec
    @JvmSuppressWildcards
    fun invoke(host: AbilityHost, arguments: List<AbilityValue>): AbilityValue
}

@Serializable
data class AbilityExtensionProject(
    val id: String,
    val name: String,
    val requestId: String,
    val entryClasses: List<String>,
    val declaredSpecs: Map<String, AbilityCapabilitySpec>,
    val sources: Map<String, String>,
)

@Serializable
data class AbilityExtensionManifest(
    val apiVersion: Int = 1,
    val id: String,
    val name: String,
    val sourceHash: String,
    val entryClasses: List<String>,
    val declaredSpecs: Map<String, AbilityCapabilitySpec>,
    val javaTarget: Int = 21,
    val validation: String = "static_abi_only",
)

@Serializable
data class AbilityExtensionArtifact(val manifest: AbilityExtensionManifest, val artifactHash: String, val bytes: Long)

@Serializable
data class AbilityExtensionInstallReceipt(
    val apiVersion: Int = 1,
    val id: String,
    val sourceHash: String,
    val artifactHash: String,
    val approvedAtMillis: Long,
    val restartRequired: Boolean = true,
)

@Serializable
enum class AbilityExtensionStage { QUEUED, COMPILING, STATIC_PROBING, READY, WAITING_INSTALL_APPROVAL, INSTALLED, DENIED, FAILED, CANCELLED, INTERRUPTED }

@Serializable
data class AbilityExtensionJob(
    val jobId: String,
    val projectId: String,
    val name: String,
    val requestId: String,
    val sourceHash: String,
    val stage: AbilityExtensionStage,
    val artifactHash: String? = null,
    val artifactBytes: Long = 0,
    val message: String = "",
    val log: String = "",
    val manifest: AbilityExtensionManifest? = null,
    val createdAtMillis: Long = 0,
    val completedAtMillis: Long = 0,
    val restartRequired: Boolean = false,
    val installedPath: String? = null,
    val backupPath: String? = null,
    val staticProbeOnly: Boolean = true,
    val providerCodeExecuted: Boolean = false,
)

/** The host UI receives exact hashes; approval is independent of any drawing/session grant. */
@Serializable
data class AbilityExtensionApproval(
    val jobId: String,
    val id: String,
    val name: String,
    val sourceHash: String,
    val artifactHash: String,
    val existingArtifactHash: String?,
    val manifest: AbilityExtensionManifest,
    val sourcePath: String,
    val artifactPath: String,
    val warning: String = "This installs trusted JVM code, not sandboxed AbilityScript. It may access native APIs, files and network after the next startup. Only static ABI has been checked; spec() and invoke() have not executed.",
    val restartRequired: Boolean = true,
)

data class AbilityExtensionLimits @JvmOverloads constructor(val compileSeconds: Long = 30, val probeSeconds: Long = 10, val heapMiB: Int = 256)

/** Paths and JVM arguments are host configuration, never MCP inputs. */
data class AbilityExtensionRuntime @JvmOverloads constructor(
    val worker: DrawingRuntime,
    val classpath: List<Path>,
    val nativeCompilerClasspath: List<Path> = emptyList(),
) {
    companion object {
        /** Convenient bounded SPI/probe classpath; the embedder adds native game dependencies explicitly. */
        @JvmStatic fun currentClasspath(): List<Path> = listOf(
            AbilityExtension::class.java, AbilityValue::class.java, kotlin.Unit::class.java,
            kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
        ).map { type ->
            val url = requireNotNull(type.protectionDomain.codeSource?.location) { "No code source for ${type.name}; supply an explicit extension runtime classpath." }
            require(url.protocol == "file") { "Non-file code source for ${type.name}; supply an extracted host runtime classpath." }
            Path.of(url.toURI()).toAbsolutePath().normalize()
        }.distinct()
    }
}
