package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Logical identity inside one world scope. Native registry ids are a separate binding. */
@Serializable
data class ContentKey(val kind: String, val id: String)

@Serializable
data class ContentReference(val target: ContentKey, val path: String, val required: Boolean = true)

/** A dependency on Minecraft or another installed mod, not a definition in this bundle. */
@Serializable
data class NativeContentReference(val registry: String, val id: String)

@Serializable
data class ContentAsset(
    val id: String,
    val sha256: String,
    val mediaType: String,
    val byteLength: Long? = null,
    val path: String? = null,
)

@Serializable
data class ContentEntry(
    val key: ContentKey,
    val module: String,
    val path: String,
    val references: List<ContentReference> = emptyList(),
    val assets: List<String> = emptyList(),
    val nativeReferences: List<NativeContentReference> = emptyList(),
)

@Serializable
enum class ContentLifecycle { BOOTSTRAP, WORLD_DATA, CLIENT_RESOURCES, WORLD_BINDING }

@Serializable
data class ContentRequirement(val capability: String, val version: Int, val lifecycle: ContentLifecycle)

@Serializable
data class ContentModuleDescriptor(
    val id: String,
    val kinds: List<String>,
    val schemaVersions: List<Int>,
    val compileAfter: List<String> = emptyList(),
    val requirements: List<ContentRequirement> = emptyList(),
    val description: String,
)

/** Draft documents for the installed modules. Format-3 manifests persist paths to these typed domains. */
@Serializable
data class WorldContentInput(
    val scope: String,
    val modules: Map<String, JsonObject>,
    val assets: List<ContentAsset> = emptyList(),
)

data class ContentContribution(
    val entries: List<ContentEntry>,
    val assets: List<ContentAsset> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** Each domain owns its typed document. The platform owns linking and lifecycle planning. */
interface WorldContentModule {
    val descriptor: ContentModuleDescriptor
    fun describe(document: JsonObject): ContentContribution
}

@Serializable
data class WorldContentCatalog(
    val scope: String,
    val entries: List<ContentEntry>,
    val assets: List<ContentAsset>,
)

@Serializable
data class WorldContentPlan(
    val catalog: WorldContentCatalog,
    val catalogValid: Boolean,
    val compileOrder: List<String>,
    val requirements: List<ContentRequirement>,
    val missingCapabilities: List<ContentRequirement>,
    val diagnostics: List<Diagnostic>,
    val totalDiagnostics: Int,
    val errorCount: Int,
) {
    val capabilitiesSatisfied: Boolean get() = catalogValid && missingCapabilities.isEmpty()
}
