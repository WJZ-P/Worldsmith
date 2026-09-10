package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.structure.StructurePackIO

data class WorldContentBundleFiles(
    val manifest: WorldsmithPackManifest,
    val texts: Map<String, String>,
    val binaries: Map<String, ByteArray>,
)

/** Format-4 authoring boundary; format 3 is retained only for reading and faithful save re-embedding. */
object WorldContentBundleIO {
    const val FORMAT_VERSION = 4
    const val LEGACY_FORMAT_VERSION = 3
    val LEGACY_MODULES = setOf("theme", "terrain", "features", "biomes", "structures", "blocks", "creatures")
    val REQUIRED_MODULES = LEGACY_MODULES + "items"
    const val MAX_TEXT_BYTES = 24 * 1024 * 1024
    const val MAX_DRAWING_BYTES = 256L * 1024 * 1024

    @JvmStatic @JvmOverloads
    fun create(displayName: String, description: String, terrain: TerrainPlan, biomes: BiomePlan,
        features: FeatureLibrary, structures: StructureLibrary = StructureLibrary(), theme: WorldTheme,
        blocks: CustomBlockLibrary = CustomBlockLibrary(), creatures: CreatureLibrary = CreatureLibrary(),
        assets: Map<String, ByteArray> = emptyMap(), items: CustomItemLibrary = CustomItemLibrary()): WorldsmithPack {
        val descriptors = assets.toSortedMap().map { (id, bytes) ->
            val hash = ContentAssetValidation.hash(bytes)
            ContentAsset(id, hash, "image/png", bytes.size.toLong(), ContentAssetValidation.path(hash))
        }
        ContentAssetValidation.verifyAll(descriptors, assets)
        val modules = REQUIRED_MODULES.sorted().associateWith { id ->
            WorldsmithModuleFile(if (id == "structures") structures.schemaVersion else 1, "$id.json")
        }
        val manifest = WorldsmithPackManifest(FORMAT_VERSION, "0".repeat(64), displayName, description,
            modules = modules, assets = descriptors)
        val draft = WorldsmithPack(manifest, terrain, biomes, features, manifest.id, structures, theme, blocks, creatures, assets, items)
        val files = encode(draft)
        return draft.copy(manifest = files.manifest, computedId = files.manifest.id)
    }

    @JvmStatic
    fun encode(pack: WorldsmithPack): WorldContentBundleFiles {
        validateManifest(pack.manifest)
        ContentAssetValidation.verifyAll(pack.manifest.assets, pack.assets)
        val texts = linkedMapOf<String, String>()
        fun put(module: String, text: String) { texts[pack.manifest.modulePath(module)] = text }
        put("theme", WorldsmithJson.encode(pack.theme))
        put("terrain", WorldsmithJson.encode(pack.terrain))
        put("features", WorldsmithJson.encode(pack.features))
        put("biomes", WorldsmithJson.encode(pack.biomes))
        put("blocks", WorldsmithJson.encode(pack.blocks))
        if (pack.manifest.formatVersion == LEGACY_FORMAT_VERSION) {
            require(pack.items.items.isEmpty()) { "Format 3 does not contain ordinary items; create a format-4 bundle for new content" }
            put("creatures", LegacyCreaturesV3.encode(pack.creatures))
        } else {
            put("creatures", WorldsmithJson.encode(pack.creatures))
            put("items", WorldsmithJson.encode(pack.items))
        }
        val structureFiles = StructurePackIO.files(pack.structures)
        texts.putAll(structureFiles - StructurePackIO.INDEX_FILE)
        put("structures", structureFiles.getValue(StructurePackIO.INDEX_FILE))
        val binaries = StructurePackIO.binaryFiles(pack.structures).toMutableMap()
        require(binaries.values.sumOf { it.size.toLong() } <= MAX_DRAWING_BYTES) { "Frozen drawing byte budget exceeded" }
        val assetBytes = pack.assets
        pack.manifest.assets.forEach { asset -> binaries[requireNotNull(asset.path)] = assetBytes.getValue(asset.id) }
        require(texts.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_TEXT_BYTES) { "Bundle text budget exceeded" }
        val finalized = WorldsmithHashUtil.finalizeManifest(pack.manifest, texts, binaries)
        return WorldContentBundleFiles(finalized, texts.toMap(), binaries.toMap())
    }

    @JvmStatic
    fun validateManifest(manifest: WorldsmithPackManifest) {
        require(manifest.formatVersion in LEGACY_FORMAT_VERSION..FORMAT_VERSION) { "Worldsmith reads bundle formats 3/4 and creates format 4; unreleased formats 1/2 are not supported" }
        val expected = if (manifest.formatVersion == LEGACY_FORMAT_VERSION) LEGACY_MODULES else REQUIRED_MODULES
        require(manifest.modules.keys == expected) { "Format ${manifest.formatVersion} requires exactly ${expected.sorted()}; quests and achievements are not installed" }
        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 160 && manifest.description.length <= 8192) { "Invalid bundle display metadata" }
        require(manifest.modules.values.map { it.path }.distinct().size == manifest.modules.size) { "Module documents must have distinct paths" }
        manifest.modules.forEach { (id, file) ->
            require(file.schemaVersion in if (id == "structures") 1..2 else 1..1) { "Unsupported schema for module '$id'" }
            require(WorldContentRegistry.validRelativePath(file.path) && file.path.endsWith(".json") &&
                file.path != "worldsmith.json" && !file.path.startsWith("structures/") && !file.path.startsWith("assets/") && !file.path.startsWith("drawings/")) { "Invalid or reserved module document path" }
        }
        require(manifest.assets.size <= ContentAssetValidation.MAX_ASSETS && manifest.assets.map { it.id }.distinct().size == manifest.assets.size) { "Invalid asset count or duplicate id" }
        require(manifest.assets.all { it.byteLength != null && it.byteLength in 1..ContentAssetValidation.MAX_ASSET_BYTES.toLong() &&
            WorldContentRegistry.SHA256.matches(it.sha256) && it.path == ContentAssetValidation.path(it.sha256) && it.mediaType == "image/png" }) { "Invalid PNG asset descriptors" }
        require(manifest.assets.sumOf { requireNotNull(it.byteLength) } <= ContentAssetValidation.MAX_TOTAL_BYTES) { "Bundle asset budget exceeded" }
    }
}
