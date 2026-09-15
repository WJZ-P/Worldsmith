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

/** Current-only immutable bundle boundary. Format 7 makes anchor-scoped mechanics a required module. */
object WorldContentBundleIO {
    const val FORMAT_VERSION = 7
    val REQUIRED_MODULES = setOf("theme", "terrain", "features", "biomes", "structures", "blocks", "creatures", "items", "quests", "mechanics")
    const val MAX_TEXT_BYTES = 24 * 1024 * 1024
    const val MAX_DRAWING_BYTES = 256L * 1024 * 1024

    @JvmStatic @JvmOverloads
    fun create(displayName: String, description: String, terrain: TerrainPlan, biomes: BiomePlan,
        features: FeatureLibrary, structures: StructureLibrary = StructureLibrary(), theme: WorldTheme,
        blocks: CustomBlockLibrary = CustomBlockLibrary(), creatures: CreatureLibrary = CreatureLibrary(),
        assets: Map<String, ByteArray> = emptyMap(), items: CustomItemLibrary = CustomItemLibrary(), quests: QuestLibrary = QuestLibrary(),
        representativeContent: ContentKey? = null, mechanics: WorldMechanicLibrary = WorldMechanicLibrary()): WorldsmithPack {
        val descriptors = assets.toSortedMap().map { (id, bytes) ->
            val hash = ContentAssetValidation.hash(bytes)
            ContentAsset(id, hash, "image/png", bytes.size.toLong(), ContentAssetValidation.path(hash))
        }
        ContentAssetValidation.verifyAll(descriptors, assets)
        val modules = REQUIRED_MODULES.sorted().associateWith { id ->
            WorldsmithModuleFile(when(id) {"structures"->structures.schemaVersion;"creatures"->creatures.schemaVersion;"items"->items.schemaVersion;"mechanics"->mechanics.schemaVersion;else->1}, "$id.json")
        }
        val manifest = WorldsmithPackManifest(FORMAT_VERSION, "0".repeat(64), displayName, description,
            modules = modules, assets = descriptors, representativeContent = representativeContent)
        val draft = WorldsmithPack(manifest, terrain, biomes, features, manifest.id, structures, theme, blocks, creatures, assets, items, quests, mechanics)
        val files = encode(draft)
        return draft.copy(manifest = files.manifest, computedId = files.manifest.id)
    }

    @JvmStatic
    fun encode(pack: WorldsmithPack): WorldContentBundleFiles {
        validateManifest(pack.manifest)
        require(pack.creatures.creatures.none { it.sounds != null } || pack.creatures.schemaVersion == 3) {
            "Authored creature sounds require creature module schema 3"
        }
        ContentAssetValidation.verifyAll(pack.manifest.assets, pack.assets)
        val texts = linkedMapOf<String, String>()
        fun put(module: String, text: String) { texts[pack.manifest.modulePath(module)] = text }
        put("theme", WorldsmithJson.encode(pack.theme))
        put("terrain", WorldsmithJson.encode(pack.terrain))
        put("features", WorldsmithJson.encode(pack.features))
        put("biomes", WorldsmithJson.encode(pack.biomes))
        put("blocks", WorldsmithJson.encode(pack.blocks))
        put("creatures", WorldsmithJson.encode(pack.creatures))
        put("items", WorldsmithJson.encode(pack.items))
        put("quests", WorldsmithJson.encode(pack.quests))
        put("mechanics", WorldsmithJson.encode(pack.mechanics))
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
        require(manifest.formatVersion == FORMAT_VERSION) {
            "Unsupported bundle format ${manifest.formatVersion}; Worldsmith requires format $FORMAT_VERSION with the mechanics module. Older formats 3/4/5/6 must be authored again; existing files are left unchanged."
        }
        require(manifest.modules.keys == REQUIRED_MODULES) { "Format $FORMAT_VERSION requires exactly ${REQUIRED_MODULES.sorted()}" }
        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 160 && manifest.description.length <= 8192) { "Invalid bundle display metadata" }
        require(manifest.modules.values.map { it.path }.distinct().size == manifest.modules.size) { "Module documents must have distinct paths" }
        manifest.modules.forEach { (id, file) ->
            val versions = when (id) { "creatures" -> 1..3; "structures", "items" -> 1..2; else -> 1..1 }
            require(file.schemaVersion in versions) { "Unsupported schema for module '$id' in bundle format ${manifest.formatVersion}" }
            require(WorldContentRegistry.validRelativePath(file.path) && file.path.endsWith(".json") &&
                file.path != "worldsmith.json" && !file.path.startsWith("structures/") && !file.path.startsWith("assets/") && !file.path.startsWith("drawings/")) { "Invalid or reserved module document path" }
        }
        require(manifest.assets.size <= ContentAssetValidation.MAX_ASSETS && manifest.assets.map { it.id }.distinct().size == manifest.assets.size) { "Invalid asset count or duplicate id" }
        require(manifest.assets.all { it.byteLength != null && it.byteLength in 1..ContentAssetValidation.MAX_ASSET_BYTES.toLong() &&
            WorldContentRegistry.SHA256.matches(it.sha256) && it.path == ContentAssetValidation.path(it.sha256) && it.mediaType == "image/png" }) { "Invalid PNG asset descriptors" }
        require(manifest.assets.sumOf { requireNotNull(it.byteLength) } <= ContentAssetValidation.MAX_TOTAL_BYTES) { "Bundle asset budget exceeded" }
        manifest.representativeContent?.let {
            require(it.kind in setOf("item", "block") && CustomItemValidation.validId(it.id)) { "Representative content names a local item or block" }
        }
    }
}
