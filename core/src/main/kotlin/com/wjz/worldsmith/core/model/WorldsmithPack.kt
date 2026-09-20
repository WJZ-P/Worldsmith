package com.wjz.worldsmith.core.model

import kotlinx.serialization.Serializable
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.ability.AbilityLibrary
import com.wjz.worldsmith.core.ability.AbilityPrograms
import com.wjz.worldsmith.core.story.*

@Serializable
data class WorldsmithModuleFile(val schemaVersion: Int, val path: String)

@Serializable
data class WorldsmithPackManifest @JvmOverloads constructor(
    val formatVersion: Int,
    val id: String,
    val displayName: String,
    val description: String,
    val modules: Map<String, WorldsmithModuleFile> = emptyMap(),
    val assets: List<ContentAsset> = emptyList(),
    /** Display metadata only; resolves to an existing item/block icon without activating its world. */
    val representativeContent: ContentKey? = null,
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
    items: CustomItemLibrary = CustomItemLibrary(),
    quests: QuestLibrary = QuestLibrary(),
    mechanics: WorldMechanicLibrary = WorldMechanicLibrary(),
    abilities: AbilityLibrary = AbilityLibrary(),
    story: StoryLibrary = StoryLibrary(),
) {
    private val frozenAssets = assets.mapValues { (_, bytes) -> bytes.copyOf() }
    val items: CustomItemLibrary = CustomItemValidation.freeze(items)
    val quests: QuestLibrary = QuestValidation.freeze(quests)
    val mechanics: WorldMechanicLibrary = WorldMechanicValidation.freeze(mechanics)
    val abilities: AbilityLibrary = AbilityPrograms.freeze(abilities)
    val story: StoryLibrary = StoryValidation.freeze(story)
    /** Callers never receive the immutable bundle's backing bytes. */
    val assets: Map<String, ByteArray> get() = frozenAssets.mapValues { (_, bytes) -> bytes.copyOf() }

    fun copy(manifest: WorldsmithPackManifest = this.manifest, terrain: TerrainPlan = this.terrain,
        biomes: BiomePlan = this.biomes, features: FeatureLibrary = this.features, computedId: String = this.computedId,
        structures: StructureLibrary = this.structures, theme: WorldTheme = this.theme,
        blocks: CustomBlockLibrary = this.blocks, creatures: CreatureLibrary = this.creatures,
        assets: Map<String, ByteArray> = this.assets, items: CustomItemLibrary = this.items, quests: QuestLibrary = this.quests,
        mechanics: WorldMechanicLibrary = this.mechanics, abilities: AbilityLibrary = this.abilities, story: StoryLibrary = this.story) =
        WorldsmithPack(manifest, terrain, biomes, features, computedId, structures, theme, blocks, creatures, assets, items, quests, mechanics, abilities, story)
}
