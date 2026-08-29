package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.model.WorldsmithPack

object WorldsmithPackValidator {
    private const val FORMAT_VERSION = 1
    private val ID = Regex("^[a-z0-9_.-]+$")

    fun validate(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val manifest = pack.manifest
        if (manifest.formatVersion != FORMAT_VERSION) {
            add(error("manifest.formatVersion", "UNSUPPORTED_PACK_FORMAT", "Unsupported pack format ${manifest.formatVersion}"))
        }
        if (!ID.matches(manifest.id)) {
            add(error("manifest.id", "INVALID_PACK_ID", "Pack id must match ${ID.pattern}"))
        }
        if (manifest.displayName.isBlank()) {
            add(error("manifest.displayName", "EMPTY_DISPLAY_NAME", "Pack display name must not be blank"))
        }
        listOf(
            "terrain" to manifest.files.terrain,
            "biomeLayout" to manifest.files.biomeLayout,
            "biomeSkins" to manifest.files.biomeSkins,
        ).forEach { (name, path) ->
            if (path.startsWith('/') || path.split('/').any { it == ".." }) {
                add(error("manifest.files.$name", "UNSAFE_PACK_PATH", "Pack content paths must stay inside the pack"))
            }
        }

        if (pack.terrain.worldId != manifest.id) {
            add(error("terrain.worldId", "WORLD_ID_MISMATCH", "Terrain world id must match the manifest"))
        }
        if (pack.biomeLayout.worldId != manifest.id) {
            add(error("biomeLayout.worldId", "WORLD_ID_MISMATCH", "Biome layout world id must match the manifest"))
        }
        if (pack.biomeSkins.worldId != manifest.id) {
            add(error("biomeSkins.worldId", "WORLD_ID_MISMATCH", "Biome skin world id must match the manifest"))
        }

        addAll(TerrainPlanValidator.validate(pack.terrain).map { it.prefixed("terrain") })
        addAll(BiomeLayoutValidator.validate(pack.biomeLayout).map { it.prefixed("biomeLayout") })
        addAll(BiomeSkinValidator.validate(pack.biomeSkins, pack.biomeLayout).map { it.prefixed("biomeSkins") })
    }

    private fun Diagnostic.prefixed(prefix: String) = copy(path = "$prefix.$path")

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
