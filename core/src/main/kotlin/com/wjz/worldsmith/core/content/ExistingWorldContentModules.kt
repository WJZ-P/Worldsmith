package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.story.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.structure.StructureInteraction
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Installed domain modules share one catalog; native execution remains a separate lifecycle. */
object ExistingWorldContentModules {
    private fun requirement(id: String) = listOf(ContentRequirement("worldgen.$id", 1, ContentLifecycle.WORLD_DATA))
    private fun descriptor(id: String, kinds: List<String>, after: List<String> = emptyList(), schemas: List<Int> = listOf(1)) =
        ContentModuleDescriptor(id, kinds, schemas, after, requirement(id), "Existing typed $id document; inventory and linking adapter")

    private class TypedModule<T>(override val descriptor: ContentModuleDescriptor, private val serializer: DeserializationStrategy<T>,
        private val inspect: (T, JsonObject) -> ContentContribution) : WorldContentModule {
        override fun describe(document: JsonObject): ContentContribution {
            val result = inspect(WorldsmithJson.format.decodeFromJsonElement(serializer, document), document)
            return result.copy(diagnostics = result.diagnostics + logicalAliasDiagnostics(document, descriptor.id))
        }
    }

    @JvmOverloads fun registry(capabilities: AbilityCapabilityRegistry = AbilityCapabilities.standard()) = WorldContentRegistry(listOf(
        StoryContentModule,
        TypedModule(ContentModuleDescriptor("abilities", listOf("ability"), listOf(1), requirements = listOf(
            ContentRequirement("abilities.runtime", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "Portable AbilityScript source compiled into bounded immutable programs, with extensible versioned capability calls"), AbilityLibrary.serializer()) { library, _ ->
            ContentContribution(library.programs.mapIndexed { i, program ->
                ContentEntry(ContentKey("ability", program.id), "abilities", "abilities.programs[$i]")
            }, diagnostics = AbilityPrograms.validate(library, capabilities))
        },
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
        TypedModule(descriptor("structures", listOf("structure", "blueprint", "drawing"), listOf("terrain", "biomes", "creatures"), listOf(1, 2, 3)), StructureLibrary.serializer()) { library, _ ->
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
                        blueprint.drawing?.variants.orEmpty().mapIndexed { j, id -> ContentReference(ContentKey("drawing", id), "$location.drawing.variants[$j]") } +
                            blueprint.interactions.mapIndexedNotNull { j, interaction -> (interaction as? StructureInteraction.BossSpawner)?.let {
                                ContentReference(ContentKey("creature", it.creatureId), "$location.interactions[$j].creatureId")
                            } } + blueprint.interactions.flatMapIndexed { j, interaction -> (interaction as? StructureInteraction.StoryAnchor)?.let { anchor ->
                                listOf(ContentReference(ContentKey("place",anchor.place),"$location.interactions[$j].place")) + listOfNotNull(anchor.character?.let { ContentReference(ContentKey("character",it),"$location.interactions[$j].character") })
                            }.orEmpty() } + localBlockReferences(WorldsmithJson.format.encodeToJsonElement(blueprint), location),
                        nativeReferences = nativeReferences(WorldsmithJson.format.encodeToJsonElement(blueprint)))
                }
            }
            ContentContribution(entries, assets)
        },
        TypedModule(ContentModuleDescriptor("blocks", listOf("block", "block_item"), listOf(2), requirements = listOf(
            ContentRequirement("custom_blocks.native_hosts", 1, ContentLifecycle.BOOTSTRAP),
            ContentRequirement("world_content.client_resources", 1, ContentLifecycle.CLIENT_RESOURCES),
            ContentRequirement("world_content.world_binding", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "Bounded native block profiles with immutable PNG textures and per-world bindings"), CustomBlockLibrary.serializer()) { library, _ ->
            ContentContribution(library.blocks.flatMapIndexed { i, block ->
                val path = "blocks.blocks[$i]"
                listOf(ContentEntry(ContentKey("block", block.id), "blocks", path, assets = block.appearance.assetIds()),
                    ContentEntry(ContentKey("block_item", block.id), "blocks", path, references = listOf(ContentReference(ContentKey("block", block.id), "$path.id"))))
            }, diagnostics = CustomBlockValidation.validate(library).map { it.copy(path = "blocks.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("items", listOf("item"), listOf(1, 2, 3, 4), compileAfter = listOf("abilities"), requirements = listOf(
            ContentRequirement("custom_items.native_host", 1, ContentLifecycle.BOOTSTRAP),
            ContentRequirement("world_content.client_resources", 1, ContentLifecycle.CLIENT_RESOURCES),
            ContentRequirement("custom_items.world_stacks", 1, ContentLifecycle.WORLD_BINDING),
            ContentRequirement("custom_items.equipment", 1, ContentLifecycle.WORLD_BINDING),
            ContentRequirement("custom_items.actions", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "World-scoped item stacks; schema 2 equipment and consumption; schema 3 invokes shared AbilityScript programs"), CustomItemLibrary.serializer()) { library, _ ->
            ContentContribution(library.items.mapIndexed { i, item -> ContentEntry(ContentKey("item", item.id), "items", "items.items[$i]", references = item.actions.flatMapIndexed { j, action -> action.effects.mapIndexedNotNull { k, effect ->
                (effect as? ItemEffect.RunProgram)?.let { ContentReference(ContentKey("ability", it.program), "items.items[$i].actions[$j].effects[$k].program") }
            } } + item.abilityBindings.mapIndexed { j, binding -> ContentReference(ContentKey("ability", binding.program), "items.items[$i].abilityBindings[$j].program") }, assets = listOfNotNull(item.textureAsset, item.equipment?.textureAsset)) },
                diagnostics = CustomItemValidation.validate(library).map { it.copy(path = "items.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("creatures", listOf("creature"), listOf(1, 2, 3, 4, 5, 6), compileAfter = listOf("biomes", "abilities"), requirements = listOf(
            ContentRequirement("creatures.native_hosts", 1, ContentLifecycle.BOOTSTRAP),
            ContentRequirement("assets.entity_models", 1, ContentLifecycle.CLIENT_RESOURCES),
            ContentRequirement("creatures.world_behaviors", 1, ContentLifecycle.WORLD_BINDING),
        ), description = "Ground creature hosts, cuboid rigs, procedural animation, server behaviors and bounded item drops"), CreatureLibrary.serializer()) { library, raw ->
            ContentContribution(library.creatures.mapIndexed { i, creature ->
                val path = "creatures.creatures[$i]"
                ContentEntry(ContentKey("creature", creature.id), "creatures", path,
                    creature.spawn.biomes.mapIndexed { j, biome -> ContentReference(ContentKey("biome", biome), "$path.spawn.biomes[$j]") } +
                        listOfNotNull(creature.ability?.let { ContentReference(ContentKey("ability", it.program), "$path.ability.program") }) +
                        creature.abilityBindings.mapIndexed { j, binding -> ContentReference(ContentKey("ability", binding.program), "$path.abilityBindings[$j].program") } +
                        localBlockReferences(raw.getValue("creatures").jsonArray[i], path),
                    assets = listOf(creature.model.texture), nativeReferences = nativeReferences(raw.getValue("creatures").jsonArray[i]))
            }, diagnostics = CustomCreatureValidator.validate(library).map { it.copy(path = "creatures.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("mechanics", listOf("mechanic", "mechanic_rule"), listOf(1),
            compileAfter = listOf("blocks", "items", "biomes", "creatures", "abilities"), requirements = listOf(
                ContentRequirement("mechanics.anchor_interactions", 1, ContentLifecycle.WORLD_BINDING),
            ), description = "Bounded event-driven block patterns, anchor state transitions, explicit hand offerings and transactional native actions"), WorldMechanicLibrary.serializer()) { library, raw ->
            val entries = library.mechanics.flatMapIndexed { i, mechanic ->
                val path = "mechanics.mechanics[$i]"
                fun ruleKey(rule: WorldMechanicRule) = ContentKey("mechanic_rule", "${mechanic.id}/${rule.id}")
                listOf(ContentEntry(ContentKey("mechanic", mechanic.id), "mechanics", path,
                    mechanic.rules.mapIndexed { j, rule -> ContentReference(ruleKey(rule), "$path.rules[$j]") })) +
                    mechanic.rules.mapIndexed { j, rule ->
                        val at = "$path.rules[$j]"
                        val document = raw.getValue("mechanics").jsonArray[i].jsonObject.getValue("rules").jsonArray[j]
                        val references = rule.biomes.mapIndexed { k, biome -> ContentReference(ContentKey("biome", biome), "$at.biomes[$k]") } +
                            rule.actions.mapIndexedNotNull { k, action -> (action as? MechanicAction.SpawnCreature)?.let {
                                ContentReference(ContentKey("creature", it.creature), "$at.actions[$k].creature")
                            } } + rule.actions.mapIndexedNotNull { k, action -> (action as? MechanicAction.RunProgram)?.let {
                                ContentReference(ContentKey("ability", it.program), "$at.actions[$k].program")
                            } } + localBlockReferences(document, at)
                        ContentEntry(ruleKey(rule), "mechanics", at, references, nativeReferences = nativeReferences(document))
                    }
            }
            ContentContribution(entries, diagnostics = WorldMechanicValidation.validate(library).map { it.copy(path = "mechanics.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("theme", listOf("theme", "narrative_beat"), listOf(1),
            compileAfter = listOf("structures", "creatures", "blocks"), description = "One persistent world premise and narrative beats anchored to concrete content; not executable quests"), WorldTheme.serializer()) { theme, _ ->
            ContentContribution(listOf(ContentEntry(ContentKey("theme", theme.id), "theme", "theme",
                theme.beats.mapIndexed { i, beat -> ContentReference(ContentKey("narrative_beat", beat.id), "theme.beats[$i]") })) +
                theme.beats.mapIndexed { i, beat -> ContentEntry(ContentKey("narrative_beat", beat.id), "theme", "theme.beats[$i]",
                    beat.content.mapIndexed { j, key -> ContentReference(key, "theme.beats[$i].content[$j]") }) },
                diagnostics = WorldThemeValidation.validate(theme).map { it.copy(path = "theme.${it.path}") })
        },
        TypedModule(ContentModuleDescriptor("quests", listOf("quest"), listOf(2), compileAfter = listOf("theme", "mechanics", "story"), requirements = listOf(
            ContentRequirement("quests.server_progress", 2, ContentLifecycle.WORLD_BINDING),
            ContentRequirement("quests.client_journal", 2, ContentLifecycle.CLIENT_RESOURCES),
        ), description = "A discovered story DAG with explicit branch acceptance, optional goals, shared fact conditions and atomic once-only consequences"), QuestLibrary.serializer()) { library, raw ->
            ContentContribution(library.quests.mapIndexed { i, quest ->
                val path = "quests.quests[$i]"
                val document = raw.getValue("quests").jsonArray[i]
                val references = quest.prerequisites.mapIndexed { j, id -> ContentReference(ContentKey("quest", id), "$path.prerequisites[$j]") } +
                    quest.objectives.mapIndexedNotNull { j, objective ->
                        when (objective) {
                            is QuestObjective.KillCreature -> ContentReference(ContentKey("creature", objective.creature), "$path.objectives[$j].creature")
                            is QuestObjective.ActivateMechanic -> ContentReference(ContentKey("mechanic", objective.mechanic), "$path.objectives[$j].mechanic")
                            is QuestObjective.DeliverItem, is QuestObjective.Fact -> null
                        }
                    } + listOfNotNull(quest.themeBeat?.let { ContentReference(ContentKey("narrative_beat", it), "$path.themeBeat") }) +
                    listOfNotNull(quest.destination?.let { ContentReference(ContentKey("place", it), "$path.destination") }) +
                    (com.wjz.worldsmith.core.story.StoryConditions.references(quest.discoverWhen) +
                        com.wjz.worldsmith.core.story.StoryConditions.references(quest.availableWhen) +
                        quest.objectives.filterIsInstance<QuestObjective.Fact>().flatMap { com.wjz.worldsmith.core.story.StoryConditions.references(it.condition) } +
                        (quest.onAccept + quest.onClaim).map { it.fact }).distinct().map {
                        ContentReference(ContentKey("story_fact", it.id), "$path.storyConditions")
                    } + localBlockReferences(document, path)
                ContentEntry(ContentKey("quest", quest.id), "quests", path, references, nativeReferences = nativeReferences(document))
            }, diagnostics = QuestValidation.validate(library).map { it.copy(path = "quests.${it.path}") })
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
    ).apply {
        put("items", WorldsmithJson.format.encodeToJsonElement(pack.items).jsonObject)
        put("quests", WorldsmithJson.format.encodeToJsonElement(pack.quests).jsonObject)
        put("mechanics", WorldsmithJson.format.encodeToJsonElement(pack.mechanics).jsonObject)
        put("abilities", WorldsmithJson.format.encodeToJsonElement(pack.abilities).jsonObject)
        put("story", WorldsmithJson.format.encodeToJsonElement(pack.story).jsonObject)
    }, pack.manifest.assets)

    /** Names describe installed adapter capabilities, not a successful native activation receipt. */
    fun nativeCapabilities() = listOf("terrain", "features", "biomes", "structures").associate { "worldgen.$it" to 1 } + mapOf(
        "custom_blocks.native_hosts" to 1, "world_content.client_resources" to 1, "world_content.world_binding" to 1,
        "creatures.native_hosts" to 1, "assets.entity_models" to 1, "creatures.world_behaviors" to 1,
        "custom_items.native_host" to 1, "custom_items.world_stacks" to 1,
        "custom_items.equipment" to 1, "custom_items.actions" to 1,
        "mechanics.anchor_interactions" to 1, "abilities.runtime" to 1, "story.runtime" to 1, "story.client" to 1,
        "quests.server_progress" to 2, "quests.client_journal" to 2, "quests.native_advancements" to 2,
    )

    val plannedModules = listOf(
        ContentModuleDescriptor("achievements", listOf("achievement"), emptyList(), description = "Independent achievement authoring is not installed or accepted; existing quests already project into native world-specific advancements"),
    )

    private fun nativeReferences(value: JsonElement): List<NativeContentReference> {
        val result = linkedSetOf<NativeContentReference>()
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key in setOf("block", "item", "particle") && child is JsonPrimitive && child.isString && ':' in child.content && !child.content.startsWith(LOCAL_BLOCK_PREFIX) && !child.content.startsWith(LOCAL_ITEM_PREFIX))
                        result += NativeContentReference(key, child.content)
                    if (key == "preferredIds" && child is JsonArray) child.forEach { item ->
                        (item as? JsonPrimitive)?.contentOrNull?.takeUnless { it.startsWith(LOCAL_BLOCK_PREFIX) || it.startsWith(LOCAL_ITEM_PREFIX) }?.let { result += NativeContentReference("block", it) }
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
    const val LOCAL_ITEM_PREFIX = "worldsmith:item/"
    private fun localBlockReferences(value: JsonElement, path: String): List<ContentReference> {
        val refs = mutableListOf<ContentReference>()
        fun visit(value: JsonElement, at: String, field: String = "") {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, "$at.$key", key) }
                is JsonArray -> value.forEachIndexed { i, child -> visit(child, "$at[$i]", field) }
                is JsonPrimitive -> if (value.isString && field in setOf("block", "item", "preferredIds")) {
                    if (value.content.startsWith(LOCAL_BLOCK_PREFIX))
                        refs += ContentReference(ContentKey(if (field == "item") "block_item" else "block", value.content.removePrefix(LOCAL_BLOCK_PREFIX).substringBefore('[')), at)
                    else if (value.content.startsWith(LOCAL_ITEM_PREFIX))
                        refs += ContentReference(ContentKey("item", value.content.removePrefix(LOCAL_ITEM_PREFIX)), at)
                }
            }
        }
        visit(value, path)
        return refs
    }

    private fun logicalAliasDiagnostics(document: JsonElement, path: String): List<Diagnostic> = buildList {
        fun visit(value: JsonElement, at: String, field: String = "") {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, "$at.$key", key) }
                is JsonArray -> value.forEachIndexed { i, child -> visit(child, "$at[$i]", field) }
                is JsonPrimitive -> if (value.isString && field in setOf("block", "item", "preferredIds", "particle")) {
                    val text = value.content
                    if (text.startsWith("worldsmith:content/item/") || text.startsWith("worldsmith:content/block/"))
                        add(Diagnostic(at, "CONTENT_NATIVE_HOST_FORBIDDEN", DiagnosticSeverity.ERROR, "Authored content uses logical item or block ids, never reserved native hosts"))
                    if (text.startsWith(LOCAL_ITEM_PREFIX)) {
                        if (field != "item") add(Diagnostic(at, "CONTENT_REFERENCE_KIND", DiagnosticSeverity.ERROR, "An ordinary item alias belongs in an item field, not a block material or particle field"))
                        if (!CustomItemValidation.validId(text.removePrefix(LOCAL_ITEM_PREFIX)))
                            add(Diagnostic(at, "CONTENT_ITEM_ALIAS_INVALID", DiagnosticSeverity.ERROR, "Use worldsmith:item/<localId> without native slot ids, state properties or reserved path sequences"))
                    }
                }
            }
        }
        visit(document, path)
    }
}
