package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureLibrary
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

/** Installed domain modules share one catalog; native execution remains a separate lifecycle. */
object ExistingWorldContentModules {
    private fun requirement(id: String) = listOf(ContentRequirement("worldgen.$id", 1, ContentLifecycle.WORLD_DATA))
    private fun descriptor(id: String, kinds: List<String>, after: List<String> = emptyList(), schemas: List<Int> = listOf(1)) =
        ContentModuleDescriptor(id, kinds, schemas, after, requirement(id), "Existing typed $id document; inventory and linking adapter")

    private class TypedModule<T>(override val descriptor: ContentModuleDescriptor, private val serializer: DeserializationStrategy<T>,
        private val inspect: (T, JsonObject) -> ContentContribution) : WorldContentModule {
        override fun describe(document: JsonObject) = inspect(WorldsmithJson.format.decodeFromJsonElement(serializer, document), document)
    }

    fun registry() = WorldContentRegistry(listOf(
        TypedModule(descriptor("terrain", listOf("terrain", "anchor"), listOf("blocks")), TerrainPlan.serializer()) { terrain, raw ->
            val anchors = (terrain.shape as? TerrainShape.Procedural)?.anchors.orEmpty()
            val refs = (terrain.shape as? TerrainShape.Procedural)?.bands.orEmpty().mapIndexedNotNull { i, band ->
                band.anchor?.let { ContentReference(ContentKey("anchor", it), "terrain.shape.bands[$i].anchor") }
            }
            ContentContribution(listOf(ContentEntry(ContentKey("terrain", "main"), "terrain", "terrain", refs + localBlockReferences(raw, "terrain"), nativeReferences = nativeReferences(raw))) +
                anchors.mapIndexed { i, anchor -> ContentEntry(ContentKey("anchor", anchor.id), "terrain", "terrain.shape.anchors[$i]") })
        },
        TypedModule(descriptor("features", listOf("feature"), listOf("terrain")), FeatureLibrary.serializer()) { features, raw ->
            ContentContribution(features.features.mapIndexed { i, feature ->
                ContentEntry(ContentKey("feature", feature.id), "features", "features.features[$i]", localBlockReferences(raw.getValue("features").jsonArray[i], "features.features[$i]"), nativeReferences = nativeReferences(raw.getValue("features").jsonArray[i]))
            })
        },
        TypedModule(descriptor("biomes", listOf("biome"), listOf("terrain", "features")), BiomePlan.serializer()) { biomes, raw ->
            ContentContribution(biomes.biomes.mapIndexed { i, biome ->
                val path = "biomes.biomes[$i]"
                val references = biome.features.mapIndexed { j, ref -> ContentReference(ContentKey("feature", ref.feature), "$path.features[$j].feature") } +
                    biome.surface.rules.mapIndexedNotNull { j, rule -> rule.conditions.anchor?.let { ContentReference(ContentKey("anchor", it.anchor), "$path.surface.rules[$j].conditions.anchor") } }
                ContentEntry(ContentKey("biome", biome.id), "biomes", path, references + localBlockReferences(raw.getValue("biomes").jsonArray[i], path), nativeReferences = nativeReferences(raw.getValue("biomes").jsonArray[i]))
            })
        },
        TypedModule(descriptor("structures", listOf("structure", "blueprint", "drawing"), listOf("terrain", "biomes"), listOf(1, 2)), StructureLibrary.serializer()) { library, _ ->
            val entries = mutableListOf<ContentEntry>()
            val assets = library.artifacts.toSortedMap().values.map { a ->
                entries += ContentEntry(ContentKey("drawing", a.id), "structures", "structures.artifacts.${a.id}", assets = listOf(a.id))
                ContentAsset(a.id, a.dataHash, "application/vnd.worldsmith.draw", path = a.path)
            }
            library.structures.forEachIndexed { i, structure ->
                val path = "structures.structures[$i]"
                fun blueprintKey(id: String) = ContentKey("blueprint", "${structure.id}/$id")
                val references = mutableListOf(ContentReference(blueprintKey(structure.blueprint.id), "$path.blueprint"))
                references += structure.placement.biomes.mapIndexed { j, id -> ContentReference(ContentKey("biome", id), "$path.placement.biomes[$j]") }
                structure.placement.anchor?.let { references += ContentReference(ContentKey("anchor", it.id), "$path.placement.anchor") }
                val raw = WorldsmithJson.format.encodeToJsonElement(structure).jsonObject
                val assembly = raw["assembly"] as? JsonObject
                (assembly?.get("pools") as? JsonObject)?.forEach { (pool, choices) -> choices.jsonArray.forEachIndexed { j, choice ->
                    references += ContentReference(blueprintKey(choice.jsonObject.getValue("piece").jsonPrimitive.content), "$path.assembly.pools.$pool[$j].piece")
                } }
                entries += ContentEntry(ContentKey("structure", structure.id), "structures", path, references)
                val blueprints = listOf(structure.blueprint to "$path.blueprint") + structure.assembly?.pieces.orEmpty().map { (id, b) -> b to "$path.assembly.pieces.$id" }
                blueprints.forEach { (blueprint, location) ->
                    entries += ContentEntry(blueprintKey(blueprint.id), "structures", location,
                        blueprint.drawing?.variants.orEmpty().mapIndexed { j, id -> ContentReference(ContentKey("drawing", id), "$location.drawing.variants[$j]") } + localBlockReferences(WorldsmithJson.format.encodeToJsonElement(blueprint), location),
                        nativeReferences = nativeReferences(WorldsmithJson.format.encodeToJsonElement(blueprint)))
                }
            }
            ContentContribution(entries, assets)
        },
        TypedModule(ContentModuleDescriptor("blocks", listOf("block", "block_item"), listOf(1), requirements = listOf(
            ContentRequirement("custom_blocks.native_hosts", 1, ContentLifecycle.BOOTSTRAP),
            ContentRequirement("world_content.client_resources", 1, ContentLifecycle.CLIENT_RESOURCES),
            ContentRequirement("world_content.world_binding", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "Bounded native block profiles with immutable PNG textures and per-world bindings"), CustomBlockLibrary.serializer()) { library, _ ->
            ContentContribution(library.blocks.flatMapIndexed { i, block ->
                val path = "blocks.blocks[$i]"
                listOf(ContentEntry(ContentKey("block", block.id), "blocks", path, assets = listOf(block.textureAsset)),
                    ContentEntry(ContentKey("block_item", block.id), "blocks", path, references = listOf(ContentReference(ContentKey("block", block.id), "$path.id"))))
            }, diagnostics = CustomBlockValidation.validate(library).map { it.copy(path = "blocks.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("creatures", listOf("creature"), listOf(1), compileAfter = listOf("biomes"), requirements = listOf(
            ContentRequirement("creatures.native_hosts", 1, ContentLifecycle.BOOTSTRAP),
            ContentRequirement("assets.entity_models", 1, ContentLifecycle.CLIENT_RESOURCES),
            ContentRequirement("creatures.world_behaviors", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "Ground creature hosts, cuboid rigs, procedural animation and server-side behaviors"), CreatureLibrary.serializer()) { library, _ ->
            ContentContribution(library.creatures.mapIndexed { i, creature ->
                val path = "creatures.creatures[$i]"
                ContentEntry(ContentKey("creature", creature.id), "creatures", path,
                    creature.spawn.biomes.mapIndexed { j, biome -> ContentReference(ContentKey("biome", biome), "$path.spawn.biomes[$j]") },
                    assets = listOf(creature.model.texture))
            }, diagnostics = CustomCreatureValidator.validate(library).map { it.copy(path = "creatures.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("theme", listOf("theme", "narrative_beat"), listOf(1),
            compileAfter = listOf("structures", "creatures", "blocks"), description = "One persistent world premise and narrative beats anchored to concrete content; not executable quests"), WorldTheme.serializer()) { theme, _ ->
            ContentContribution(listOf(ContentEntry(ContentKey("theme", theme.id), "theme", "theme",
                theme.beats.mapIndexed { i, beat -> ContentReference(ContentKey("narrative_beat", beat.id), "theme.beats[$i]") })) +
                theme.beats.mapIndexed { i, beat -> ContentEntry(ContentKey("narrative_beat", beat.id), "theme", "theme.beats[$i]",
                    beat.content.mapIndexed { j, key -> ContentReference(key, "theme.beats[$i].content[$j]") }) },
                diagnostics = WorldThemeValidation.validate(theme).map { it.copy(path = "theme.${it.path}") })
        },
    ))

    fun input(pack: WorldsmithPack) = WorldContentInput(pack.manifest.id, linkedMapOf(
        "terrain" to WorldsmithJson.format.encodeToJsonElement(pack.terrain).jsonObject,
        "features" to WorldsmithJson.format.encodeToJsonElement(pack.features).jsonObject,
        "biomes" to WorldsmithJson.format.encodeToJsonElement(pack.biomes).jsonObject,
        // Inventory does not need to duplicate potentially large archived authoring sources.
        "structures" to WorldsmithJson.format.encodeToJsonElement(pack.structures.copy(sources = emptyMap())).jsonObject,
        "theme" to WorldsmithJson.format.encodeToJsonElement(pack.theme).jsonObject,
        "blocks" to WorldsmithJson.format.encodeToJsonElement(pack.blocks).jsonObject,
        "creatures" to WorldsmithJson.format.encodeToJsonElement(pack.creatures).jsonObject,
    ), pack.manifest.assets)

    /** Names describe installed adapter capabilities, not a successful native activation receipt. */
    fun nativeCapabilities() = listOf("terrain", "features", "biomes", "structures").associate { "worldgen.$it" to 1 } + mapOf(
        "custom_blocks.native_hosts" to 1, "world_content.client_resources" to 1, "world_content.world_binding" to 1,
        "creatures.native_hosts" to 1, "assets.entity_models" to 1, "creatures.world_behaviors" to 1,
    )

    val plannedModules = listOf(
        ContentModuleDescriptor("quests", listOf("quest"), emptyList(), description = "Future executable quest objectives and progression; not installed or accepted"),
        ContentModuleDescriptor("achievements", listOf("achievement"), emptyList(), description = "Future world-specific achievement criteria and rewards; not installed or accepted"),
    )

    private fun nativeReferences(value: JsonElement): List<NativeContentReference> {
        val result = linkedSetOf<NativeContentReference>()
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key in setOf("block", "item", "particle") && child is JsonPrimitive && child.isString && ':' in child.content && !child.content.startsWith(LOCAL_BLOCK_PREFIX))
                        result += NativeContentReference(key, child.content)
                    if (key == "preferredIds" && child is JsonArray) child.forEach { item ->
                        (item as? JsonPrimitive)?.contentOrNull?.takeUnless { it.startsWith(LOCAL_BLOCK_PREFIX) }?.let { result += NativeContentReference("block", it) }
                    }
                    collect(child)
                }
                is JsonArray -> value.forEach(::collect)
                else -> Unit
            }
        }
        collect(value)
        return result.sortedWith(compareBy({ it.registry }, { it.id }))
    }

    const val LOCAL_BLOCK_PREFIX = "worldsmith:content/"
    private fun localBlockReferences(value: JsonElement, path: String): List<ContentReference> {
        val refs = mutableListOf<ContentReference>()
        fun visit(value: JsonElement, at: String) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, "$at.$key") }
                is JsonArray -> value.forEachIndexed { i, child -> visit(child, "$at[$i]") }
                is JsonPrimitive -> if (value.isString && value.content.startsWith(LOCAL_BLOCK_PREFIX))
                    refs += ContentReference(ContentKey("block", value.content.removePrefix(LOCAL_BLOCK_PREFIX).substringBefore('[')), at)
            }
        }
        visit(value, path)
        return refs
    }
}
