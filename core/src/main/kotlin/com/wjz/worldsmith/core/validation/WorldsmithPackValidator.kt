package com.wjz.worldsmith.core.validation

import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.WorldsmithPack

object WorldsmithPackValidator {
    private const val FORMAT_VERSION = 1
    private val ID = Regex("^[0-9a-f]{64}$")

    fun validate(pack: WorldsmithPack): List<Diagnostic> = buildList {
        val manifest = pack.manifest
        if (manifest.formatVersion != FORMAT_VERSION) {
            add(error("manifest.formatVersion", "UNSUPPORTED_PACK_FORMAT", "Unsupported pack format ${manifest.formatVersion}"))
        }
        if (!ID.matches(manifest.id)) {
            add(error("manifest.id", "INVALID_PACK_ID", "Pack id must be a lowercase SHA-256"))
        } else if (!WorldsmithHashUtil.matches(manifest, pack.computedId)) {
            add(error("manifest.id", "PACK_HASH_MISMATCH", "Pack id does not match its generation content"))
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

        addAll(TerrainPlanValidator.validate(pack.terrain).map { it.prefixed("terrain") })
        addAll(BiomeLayoutValidator.validate(pack.biomeLayout).map { it.prefixed("biomeLayout") })
        addAll(BiomeSkinValidator.validate(pack.biomeSkins, pack.biomeLayout).map { it.prefixed("biomeSkins") })
    }

    private fun Diagnostic.prefixed(prefix: String) = copy(path = "$prefix.$path")

    private fun error(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
}
