package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.content.*
import kotlinx.serialization.Transient

@Serializable
data class WorldsmithPackFiles(
    val terrain: String = "terrain.json",
    val biomes: String = "biomes.json",
    val features: String = "features.json",
    val structures: String = "structures.json",
)

@Serializable
data class WorldsmithModuleFile(val schemaVersion: Int, val path: String)

@Serializable
data class WorldsmithPackManifest @JvmOverloads constructor(
    val formatVersion: Int,
    val id: String,
    val displayName: String,
    val description: String,
    @Transient val files: WorldsmithPackFiles = WorldsmithPackFiles(),
    val modules: Map<String, WorldsmithModuleFile> = emptyMap(),
    val assets: List<ContentAsset> = emptyList(),
) {
    fun modulePath(id: String): String = requireNotNull(modules[id]) { "Missing module '$id'" }.path
}

class WorldsmithPack @JvmOverloads constructor(
    val manifest: WorldsmithPackManifest,
    val terrain: TerrainPlan,
    val biomes: BiomePlan,
    val features: FeatureLibrary,
    val computedId: String,
    val structures: StructureLibrary = StructureLibrary(),
    val theme: WorldTheme = WorldTheme(),
    val blocks: CustomBlockLibrary = CustomBlockLibrary(),
    val creatures: CreatureLibrary = CreatureLibrary(),
    assets: Map<String, ByteArray> = emptyMap(),
) {
    private val frozenAssets = assets.mapValues { (_, bytes) -> bytes.copyOf() }
    /** Callers never receive the immutable bundle's backing bytes. */
    val assets: Map<String, ByteArray> get() = frozenAssets.mapValues { (_, bytes) -> bytes.copyOf() }

    fun copy(manifest: WorldsmithPackManifest = this.manifest, terrain: TerrainPlan = this.terrain,
        biomes: BiomePlan = this.biomes, features: FeatureLibrary = this.features, computedId: String = this.computedId,
        structures: StructureLibrary = this.structures, theme: WorldTheme = this.theme,
        blocks: CustomBlockLibrary = this.blocks, creatures: CreatureLibrary = this.creatures,
        assets: Map<String, ByteArray> = this.assets) =
        WorldsmithPack(manifest, terrain, biomes, features, computedId, structures, theme, blocks, creatures, assets)
}
