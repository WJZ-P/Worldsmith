package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.PriorityQueue

/** Bounded, deterministic symbol/link planner. It never installs native types or activates a world. */
class WorldContentRegistry(modules: List<WorldContentModule>) {
    private val modules = modules.associateBy { it.descriptor.id }
    val descriptors: List<ContentModuleDescriptor> get() = modules.values.map { it.descriptor }.sortedBy { it.id }

    init {
        require(this.modules.size == modules.size) { "Duplicate content module id" }
        val kinds = modules.flatMap { it.descriptor.kinds }
        require(kinds.distinct().size == kinds.size) { "A content kind has exactly one owning module" }
        modules.forEach {
            val d = it.descriptor
            require(validKind(d.id) && d.kinds.isNotEmpty() && d.kinds.all(::validKind))
            require(d.schemaVersions.isNotEmpty() && d.schemaVersions.all { v -> v > 0 })
            require(d.compileAfter.distinct().size == d.compileAfter.size && d.compileAfter.all(::validKind))
            require(d.requirements.all { r -> validName(r.capability) && r.version > 0 })
        }
    }

    fun plan(input: WorldContentInput, availableCapabilities: Map<String, Int> = emptyMap()): WorldContentPlan {
        val problems = mutableListOf<Diagnostic>()
        var diagnosticCount = 0
        var errorCount = 0
        fun problem(path: String, code: String, message: String, warning: Boolean = false) {
            diagnosticCount++
            if (!warning) errorCount++
            if (problems.size < 256) problems += Diagnostic(path.take(1024), code, if (warning) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR, message.take(2048))
        }
        fun result(entries: List<ContentEntry>, assets: List<ContentAsset>, order: List<String>, requirements: List<ContentRequirement>): WorldContentPlan =
            WorldContentPlan(WorldContentCatalog(input.scope, entries, assets), errorCount == 0, order, requirements,
                requirements.filter { (availableCapabilities[it.capability] ?: 0) < it.version }, problems.toList(), diagnosticCount, errorCount)
        if (input.scope.isBlank() || input.scope.length > 256 || input.modules.size !in 1..32 || input.assets.size > MAX_ASSETS ||
            input.modules.values.sumOf { it.toString().length.toLong() } > 8 * 1024 * 1024) {
            problem("content", "CONTENT_INPUT_LIMIT", "Use a nonempty scope, 1..32 module documents, at most $MAX_ASSETS assets and 8 Mi characters")
            return result(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val entries = mutableListOf<ContentEntry>()
        val assets = input.assets.toMutableList()
        val active = linkedMapOf<String, WorldContentModule>()
        for ((id, document) in input.modules.toSortedMap()) {
            if (!validKind(id)) {
                problem("modules", "CONTENT_MODULE_ID_INVALID", "Use a lowercase module identifier of at most 48 characters")
                continue
            }
            val module = modules[id]
            if (module == null) {
                problem("modules.$id", "CONTENT_MODULE_UNAVAILABLE", "No installed module handles '$id'; its content was not treated as supported")
                continue
            }
            active[id] = module
            val schema = runCatching { document["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
            if (schema !in module.descriptor.schemaVersions) {
                problem("modules.$id.schemaVersion", "CONTENT_SCHEMA_UNSUPPORTED", "Supported versions: ${module.descriptor.schemaVersions}")
                continue
            }
            val contribution = try { module.describe(document) } catch (e: Exception) {
                problem("modules.$id", "CONTENT_DOCUMENT_INVALID", (e.message ?: "Invalid module document").take(1024))
                continue
            }
            contribution.diagnostics.forEach { problem(it.path, it.code, it.message, it.severity != DiagnosticSeverity.ERROR) }
            for (entry in contribution.entries) {
                if (entry.module != id || entry.key.kind !in module.descriptor.kinds)
                    problem(entry.path, "CONTENT_WRONG_OWNER", "${entry.key} is not owned by module '$id'")
            }
            entries += contribution.entries
            assets += contribution.assets
            if (entries.size > MAX_ENTRIES || assets.size > MAX_ASSETS) {
                problem("content", "CONTENT_CATALOG_LIMIT", "Content catalog exceeds $MAX_ENTRIES definitions or $MAX_ASSETS asset descriptors")
                return result(emptyList(), emptyList(), emptyList(), emptyList())
            }
        }

        val symbols = linkedMapOf<ContentKey, ContentEntry>()
        for (entry in entries) {
            if (!validKind(entry.key.kind) || !validName(entry.key.id)) problem(entry.path, "CONTENT_ID_INVALID", "Invalid logical content key ${entry.key}")
            if (symbols.putIfAbsent(entry.key, entry) != null) problem(entry.path, "CONTENT_ID_DUPLICATE", "Logical content key ${entry.key} appears more than once")
            if (entry.references.size > 256 || entry.assets.size > 256 || entry.nativeReferences.size > 512)
                problem(entry.path, "CONTENT_REFERENCE_LIMIT", "Entry reference budget exceeded")
        }
        val assetIndex = linkedMapOf<String, ContentAsset>()
        for (asset in assets) {
            if (!validName(asset.id) || !SHA256.matches(asset.sha256) || asset.mediaType.length > 128 || !MEDIA_TYPE.matches(asset.mediaType) ||
                asset.byteLength?.let { it !in 0..ContentAssetStore.MAX_ASSET_BYTES.toLong() } == true ||
                asset.path?.let { !validRelativePath(it) } == true)
                problem("assets.${asset.id}", "CONTENT_ASSET_INVALID", "Invalid asset identity, digest, media type, size or relative path")
            val previous = assetIndex.putIfAbsent(asset.id, asset)
            if (previous != null && previous != asset) problem("assets.${asset.id}", "CONTENT_ASSET_CONFLICT", "One asset id names conflicting descriptors")
        }
        // All symbols exist before linking: legitimate mutual runtime references are allowed.
        for (entry in entries) {
            for (ref in entry.references.take(256)) if (ref.target !in symbols)
                problem(ref.path, "CONTENT_REFERENCE_MISSING", "Missing logical content ${ref.target}", !ref.required)
            for (id in entry.assets.take(256)) if (id !in assetIndex)
                problem(entry.path, "CONTENT_ASSET_MISSING", "Missing asset '$id'")
        }

        val dependencies = active.mapValues { (_, module) -> module.descriptor.compileAfter.toSet() }
        dependencies.forEach { (id, deps) -> deps.filter { it !in active }.forEach {
            problem("modules.$id", "CONTENT_MODULE_DEPENDENCY_MISSING", "Module '$id' needs module '$it'")
        } }
        val pending = dependencies.mapValues { (_, deps) -> deps.count { it in active } }.toMutableMap()
        val ready = PriorityQueue(pending.filterValues { it == 0 }.keys)
        val order = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val id = ready.remove(); order += id
            dependencies.forEach { (next, deps) -> if (id in deps) {
                pending[next] = pending.getValue(next) - 1
                if (pending[next] == 0) ready += next
            } }
        }
        if (order.size != active.size) problem("modules", "CONTENT_COMPILE_CYCLE", "Compile dependency cycle: ${(active.keys - order.toSet()).sorted()}")
        val requirements = active.values.flatMap { it.descriptor.requirements }.groupBy { it.capability to it.lifecycle }
            .map { (_, values) -> values.maxBy { it.version } }.sortedWith(compareBy({ it.lifecycle.ordinal }, { it.capability }))
        return result(symbols.values.sortedWith(compareBy({ it.key.kind }, { it.key.id })), assetIndex.values.sortedBy { it.id }, order, requirements)
    }

    companion object {
        const val MAX_ENTRIES = 16384
        const val MAX_ASSETS = 2048
        val SHA256 = Regex("[a-f0-9]{64}")
        private val MEDIA_TYPE = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
        fun validKind(value: String) = value.matches(Regex("[a-z][a-z0-9_]{0,47}"))
        fun validName(value: String) = value.length in 1..256 && value.matches(Regex("[a-z0-9_.:/-]+")) &&
            !value.startsWith('/') && value.split('/').none { it.isBlank() || it == "." || it == ".." }
        fun validRelativePath(value: String) = value.length in 1..512 && value.matches(Regex("[a-z0-9_./-]+")) && !value.startsWith('/') &&
            value.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}
