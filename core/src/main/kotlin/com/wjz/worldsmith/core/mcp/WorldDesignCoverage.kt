package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*

/** Cheap configured joins in a draft; final coverage additionally reads the compiled frozen structure plans. */
data class DesignInventory(
    val symbols: Set<ContentKey>,
    val links: Set<DesignLink>,
    val textures: Map<ContentKey, Set<String>>,
    val drawingIds: Set<String>,
    val bosses: Set<String>,
    val naturalCreatures: Set<String>,
    val themedQuests: Set<String>,
    val moduleErrors: Map<String, String>,
    /** Per-structure upper bounds from frozen plans; mutually exclusive variants are not added together. */
    val structureSupplies: Map<String, Map<ContentKey, Long>> = emptyMap(),
    val structureCreatures: Set<String> = emptySet(),
    val mechanicCreatures: Set<String> = emptySet(),
    val reachableMechanics: Set<String> = emptySet(),
) {
    val encounterCreatures: Set<String> get() = naturalCreatures + structureCreatures + mechanicCreatures
}

object WorldDesignCoverage {
    fun draft(session: WorkflowSession): DesignInventory {
        val errors = linkedMapOf<String, String>()
        fun <T> read(id: String, deserialize: (JsonObject) -> T): T? = session.contentModules[id]?.let { document ->
            try { deserialize(document) } catch (failure: Exception) { errors[id] = failure.message.orEmpty().take(1024); null }
        }
        return inventory(
            read("terrain") { McpJson.decode<TerrainPlan>(it) }, read("biomes") { McpJson.decode<BiomePlan>(it) },
            read("features") { McpJson.decode<FeatureLibrary>(it) }, read("blocks") { McpJson.decode<CustomBlockLibrary>(it) },
            read("items") { McpJson.decode<CustomItemLibrary>(it) }, read("creatures") { McpJson.decode<CreatureLibrary>(it) },
            read("quests") { McpJson.decode<QuestLibrary>(it) }, read("theme") { McpJson.decode<WorldTheme>(it) },
            session.structureLibrary(), false, errors, read("mechanics") { McpJson.decode<WorldMechanicLibrary>(it) },
        )
    }

    fun frozen(pack: WorldsmithPack): DesignInventory {
        val raw = inventory(pack.terrain, pack.biomes, pack.features, pack.blocks,
            pack.items, pack.creatures, pack.quests, pack.theme, pack.structures, true, emptyMap(), pack.mechanics)
        val proof = WorldMechanicReachability.analyze(pack, raw)
        return raw.copy(mechanicCreatures = proof.creatures, reachableMechanics = proof.mechanics)
    }

    fun validate(pack: WorldsmithPack, plan: WorldDesignPlan, requireBoss: Boolean = true): List<Diagnostic> {
        val result = WorldDesignPlans.validate(plan, requireBoss = requireBoss).toMutableList()
        if (result.isNotEmpty()) return result
        val actual = try { frozen(pack) } catch (failure: Exception) {
            return listOf(Diagnostic("designPlan", "DESIGN_FROZEN_GEOMETRY_INVALID", DiagnosticSeverity.ERROR,
                "Complete-world coverage needs valid frozen structure plans: ${failure.message}"))
        }
        result += missing(plan, actual, true)
        result += WorldQuestReachability.validate(pack, actual, plan.targets.map { it.key }.filter { it.kind == "item" }.toSet())
        val attached = pack.assets.keys
        actual.textures.forEach { (key, ids) -> ids.filter { it !in attached }.forEach { id ->
            result += Diagnostic("designPlan.assets", "DESIGN_TEXTURE_BYTES_MISSING", DiagnosticSeverity.ERROR,
                "${key.kind}/${key.id} references texture $id, but this frozen pack does not contain its bytes")
        } }
        return result
    }

    /** Does not assert visual quality, actual biome occupancy or in-game structure placement. */
    fun missing(plan: WorldDesignPlan, actual: DesignInventory, strictGeometry: Boolean): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        plan.targets.forEachIndexed { i, target ->
            val key = target.key
            if (key !in actual.symbols) error("designPlan.targets[$i]", "DESIGN_TARGET_MISSING", "Planned ${key.kind}/${key.id} '${target.title}' has no actual definition")
        }
        plan.links.forEachIndexed { i, link ->
            if (link !in actual.links && (strictGeometry || link.relation !in setOf(DesignRelation.USES_BLOCK, DesignRelation.CONTAINS_REWARD) || link.from.kind != "structure"))
                error("designPlan.links[$i]", "DESIGN_LINK_MISSING", "Promised ${link.relation}: ${link.from.kind}/${link.from.id} -> ${link.to.kind}/${link.to.id} is absent from the actual content")
        }
        // Drafts report configured relationships only; material/state reachability is proved on the frozen pack.
        fun hasEncounter(id: String) = id in actual.encounterCreatures || !strictGeometry && actual.links.any {
            it.relation == DesignRelation.SPAWNS_CREATURE && it.to == ContentKey("creature", id)
        }
        plan.targets.filter { it.key in actual.symbols }.forEach { target ->
            val key = target.key
            when (key.kind) {
                "mechanic" -> if (strictGeometry && key.id !in actual.reachableMechanics)
                    error("designPlan.targets[${plan.targets.indexOf(target)}]", "DESIGN_MECHANIC_UNREACHABLE", "Planned mechanic '${key.id}' has no reachable initial-state activation route with obtainable inputs; a rule declaration alone is not an executable encounter route")
                "block" -> if (strictGeometry && actual.links.none { it.relation == DesignRelation.USES_BLOCK && it.to == key })
                    error("designPlan.targets", "DESIGN_BLOCK_UNUSED", "Planned block '${key.id}' is not used by a world material or compiled structure voxel; an unused palette entry does not count")
                "creature" -> if (!hasEncounter(key.id))
                    error("designPlan.targets", "DESIGN_CREATURE_UNPLACED", "Planned creature '${key.id}' has no positive natural habitat, valid structure encounter or reachable mechanic summon")
                "item" -> {
                    val producers = actual.links.filter { it.to == key && it.relation in setOf(DesignRelation.DROPS_ITEM, DesignRelation.CONTAINS_REWARD, DesignRelation.QUEST_REWARD, DesignRelation.GRANTS_ITEM) }
                    if (strictGeometry && producers.isEmpty()) error("designPlan.targets", "DESIGN_ITEM_UNOBTAINABLE", "Planned item '${key.id}' has no configured creature, structure, quest or mechanic reward producer")
                    if (actual.links.none { it.to == key && it.relation in setOf(DesignRelation.DELIVERY_OBJECTIVE, DesignRelation.QUEST_REWARD, DesignRelation.THEME_ANCHOR, DesignRelation.CONSUMES_ITEM, DesignRelation.GRANTS_ITEM) })
                        error("designPlan.targets", "DESIGN_ITEM_UNCONNECTED", "Planned item '${key.id}' has no delivery objective, quest/mechanic reward or consumption role, or concrete theme anchor")
                }
                "quest" -> if (key.id !in actual.themedQuests)
                    error("designPlan.targets", "DESIGN_QUEST_UNTHEMED", "Planned quest '${key.id}' must bind to an existing narrative beat through themeBeat")
            }
        }
        plan.bosses.forEachIndexed { i, boss ->
            if (boss.creature !in actual.bosses) error("designPlan.bosses[$i]", "DESIGN_BOSS_PROFILE_MISSING", "'${boss.creature}' needs an actual schema-2 Boss profile; high health or a Boss-looking name is not a Boss profile")
            if (!hasEncounter(boss.creature)) error("designPlan.bosses[$i]", "DESIGN_BOSS_UNREACHABLE", "Boss '${boss.creature}' has no positive natural habitat, valid structure encounter or reachable mechanic summon")
            if (DesignLink(ContentKey("quest", boss.quest), ContentKey("creature", boss.creature), DesignRelation.KILL_OBJECTIVE) !in actual.links)
                error("designPlan.bosses[$i]", "DESIGN_BOSS_QUEST_MISSING", "Quest '${boss.quest}' does not actually require defeating Boss '${boss.creature}'")
        }
    }

    private fun inventory(terrain: TerrainPlan?, biomes: BiomePlan?, features: FeatureLibrary?, blocks: CustomBlockLibrary?,
        items: CustomItemLibrary?, creatures: CreatureLibrary?, quests: QuestLibrary?, theme: WorldTheme?, structures: StructureLibrary,
        frozen: Boolean, errors: Map<String, String>, mechanics: WorldMechanicLibrary?): DesignInventory {
        val symbols = linkedSetOf<ContentKey>(); val links = linkedSetOf<DesignLink>()
        val textures = linkedMapOf<ContentKey, Set<String>>(); val drawings = linkedSetOf<String>()
        val bosses = linkedSetOf<String>(); val spawned = linkedSetOf<String>(); val themed = linkedSetOf<String>()
        val structureSupplies = linkedMapOf<String, Map<ContentKey, Long>>()
        val structureCreatures = linkedSetOf<String>()
        fun key(kind: String, id: String) = ContentKey(kind, id)
        fun link(from: ContentKey, to: ContentKey, relation: DesignRelation) { links += DesignLink(from, to, relation) }
        fun item(reference: String): ContentKey? = when {
            reference.startsWith(ExistingWorldContentModules.LOCAL_ITEM_PREFIX) -> key("item", reference.removePrefix(ExistingWorldContentModules.LOCAL_ITEM_PREFIX))
            reference.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) -> key("block_item", reference.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX))
            else -> null
        }
        fun block(reference: String): ContentKey? = reference.takeIf { it.startsWith(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX) }
            ?.let { key("block", it.removePrefix(ExistingWorldContentModules.LOCAL_BLOCK_PREFIX).substringBefore('[')) }
        fun materials(owner: ContentKey, raw: JsonElement) {
            fun visit(value: JsonElement) {
                when (value) {
                    is JsonObject -> value.forEach { (name, child) ->
                        if (name == "block" && child is JsonPrimitive && child.isString) block(child.content)?.let { link(owner, it, DesignRelation.USES_BLOCK) }
                        // Only the first preferred id is a guaranteed selected declaration. Appending an unused fallback is not usage.
                        if (name == "preferredIds" && child is JsonArray) child.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(::block)?.let { link(owner, it, DesignRelation.USES_BLOCK) }
                        visit(child)
                    }
                    is JsonArray -> value.forEach(::visit)
                    else -> Unit
                }
            }
            visit(raw)
        }
        fun rewards(owner: ContentKey, interactions: List<StructureInteraction>) = interactions.filterIsInstance<StructureInteraction.Container>().forEach { container ->
            container.items.filter { it.count > 0 }.forEach { reward -> item(reward.item)?.let { link(owner, it, DesignRelation.CONTAINS_REWARD) } }
            if ((container.loot?.maxRolls ?: 0) > 0) container.loot?.entries?.filter { it.weight > 0 && it.maxCount > 0 }?.forEach { reward ->
                item(reward.item)?.let { link(owner, it, DesignRelation.CONTAINS_REWARD) }
            }
        }
        terrain?.let { value ->
            val owner = key("terrain", "main"); symbols += owner; materials(owner, McpJson.encode(value))
            (value.shape as? TerrainShape.Procedural)?.anchors.orEmpty().forEach { symbols += key("anchor", it.id) }
        }
        features?.features.orEmpty().forEach { value -> val owner = key("feature", value.id); symbols += owner; materials(owner, McpJson.encode(value)) }
        biomes?.biomes.orEmpty().forEach { value ->
            val owner = key("biome", value.id); symbols += owner; materials(owner, McpJson.encode(value.surface))
            value.features.forEach { link(owner, key("feature", it.feature), DesignRelation.USES_FEATURE) }
        }
        blocks?.blocks.orEmpty().forEach { value ->
            val owner = key("block", value.id); symbols += owner; symbols += key("block_item", value.id); textures[owner] = setOf(value.textureAsset)
        }
        items?.items.orEmpty().forEach { value -> val owner = key("item", value.id); symbols += owner; textures[owner] = setOf(value.textureAsset) }
        val biomeIds = biomes?.biomes.orEmpty().map { it.id }.toSet()
        creatures?.creatures.orEmpty().forEach { value ->
            val owner = key("creature", value.id); symbols += owner; textures[owner] = setOf(value.model.texture)
            value.spawn.biomes.forEach { link(owner, key("biome", it), DesignRelation.SPAWNS_IN_BIOME) }
            if (value.spawn.weight > 0 && value.spawn.minGroup > 0 && value.spawn.biomes.any { it in biomeIds }) spawned += value.id
            val profile = McpJson.encode(value).jsonObject["boss"]
            if (creatures?.schemaVersion in 2..3 && profile is JsonObject) bosses += value.id
            value.drops.filter { it.chance > 0 && it.maxCount > 0 }.forEach { drop -> item(drop.item)?.let { link(owner, it, DesignRelation.DROPS_ITEM) } }
        }
        mechanics?.mechanics.orEmpty().forEach { value ->
            val owner = key("mechanic", value.id); symbols += owner
            value.rules.forEach { rule ->
                rule.pattern.forEach { cell ->
                    block(cell.block.block)?.let { link(owner, it, DesignRelation.USES_BLOCK) }
                    if (cell.consume) item(cell.block.block)?.let { link(owner, it, DesignRelation.CONSUMES_ITEM) }
                }
                rule.heldItem?.let { cost -> item(cost.item)?.let { link(owner, it, DesignRelation.CONSUMES_ITEM) } }
                rule.actions.forEach { action -> when (action) {
                    is MechanicAction.SetBlock -> block(action.block.block)?.let { link(owner, it, DesignRelation.USES_BLOCK) }
                    is MechanicAction.SpawnCreature -> link(owner, key("creature", action.creature), DesignRelation.SPAWNS_CREATURE)
                    is MechanicAction.GiveItem -> if (action.count > 0) item(action.item)?.let { link(owner, it, DesignRelation.GRANTS_ITEM) }
                } }
            }
        }
        val beatIds = theme?.beats.orEmpty().map { it.id }.toSet()
        quests?.quests.orEmpty().forEach { value ->
            val owner = key("quest", value.id); symbols += owner
            value.prerequisites.forEach { link(owner, key("quest", it), DesignRelation.PREREQUISITE) }
            value.objectives.forEach { objective -> when (objective) {
                is QuestObjective.KillCreature -> link(owner, key("creature", objective.creature), DesignRelation.KILL_OBJECTIVE)
                is QuestObjective.ActivateMechanic -> link(owner, key("mechanic", objective.mechanic), DesignRelation.ACTIVATION_OBJECTIVE)
                is QuestObjective.DeliverItem -> item(objective.item)?.let { link(owner, it, DesignRelation.DELIVERY_OBJECTIVE) }
            } }
            value.rewards.filter { it.count > 0 }.forEach { reward -> item(reward.item)?.let { link(owner, it, DesignRelation.QUEST_REWARD) } }
            value.themeBeat?.takeIf { it in beatIds }?.let { themed += value.id; link(key("narrative_beat", it), owner, DesignRelation.THEME_ANCHOR) }
        }
        theme?.let { value ->
            val owner = key("theme", value.id); symbols += owner
            value.beats.forEach { beat ->
                val node = key("narrative_beat", beat.id); symbols += node
                beat.content.forEach { target -> link(owner, target, DesignRelation.THEME_ANCHOR); link(node, target, DesignRelation.THEME_ANCHOR) }
            }
        }
        val catalog = if (frozen) StructureCatalogCompiler.compile(structures) else null
        structures.structures.forEach { value ->
            val owner = key("structure", value.id); symbols += owner
            fun encounters(interactions: List<StructureInteraction>) {
                interactions.filterIsInstance<StructureInteraction.BossSpawner>().forEach { spawner ->
                    link(owner, key("creature", spawner.creatureId), DesignRelation.CONTAINS_ENCOUNTER)
                    val creature = creatures?.creatures?.find { it.id == spawner.creatureId }
                    if (structures.schemaVersion == 2 && creatures?.schemaVersion in 2..3 && creature?.boss != null &&
                        creature.category == CreatureCategory.HOSTILE && spawner.respawnTicks in 200..30000 &&
                        spawner.requiredPlayerRange in 8..32 && spawner.spawnRange in 1..8 &&
                        (value.placement.region?.chance ?: 1.0) > 0 && value.placement.biomes.any { it in biomeIds })
                        structureCreatures += spawner.creatureId
                }
            }
            value.placement.biomes.forEach { link(owner, key("biome", it), DesignRelation.PLACED_IN_BIOME) }
            val blueprints = listOf(value.blueprint) + value.assembly?.pieces.orEmpty().values
            blueprints.forEach { blueprint ->
                symbols += key("blueprint", "${value.id}/${blueprint.id}")
                drawings += blueprint.drawing?.variants.orEmpty()
                if (!frozen) { materials(owner, McpJson.encode(blueprint.palette)); rewards(owner, blueprint.interactions); encounters(blueprint.interactions) }
            }
            if (catalog != null) {
                val upperBounds = linkedMapOf<ContentKey, Long>()
                catalog.plans[value.id].orEmpty().forEach { plan ->
                    val supplies = linkedMapOf<ContentKey, Long>()
                    fun addSupply(key: ContentKey, count: Long) { supplies[key] = (supplies[key] ?: 0L) + count }
                    plan.parts.forEach { part ->
                        part.geometry.voxels.forEach { voxel -> block(voxel.material.block)?.let {
                            link(owner, it, DesignRelation.USES_BLOCK)
                            addSupply(key("block_item", it.id), 1L)
                        } }
                        rewards(owner, part.geometry.interactions)
                        encounters(part.geometry.interactions)
                        part.geometry.interactions.filterIsInstance<StructureInteraction.Container>().forEach { container ->
                            container.items.filter { it.count > 0 }.forEach { reward -> item(reward.item)?.let { addSupply(it, reward.count.toLong()) } }
                            container.loot?.let { loot ->
                                // This is a generous upper bound, not a guarantee that random loot will roll every item.
                                loot.entries.filter { it.weight > 0 && it.maxCount > 0 }.forEach { reward -> item(reward.item)?.let {
                                    addSupply(it, loot.maxRolls.toLong().coerceAtLeast(0) * reward.maxCount)
                                } }
                            }
                        }
                    }
                    supplies.forEach { (key, count) -> upperBounds[key] = maxOf(upperBounds[key] ?: 0L, count) }
                }
                structureSupplies[value.id] = upperBounds
            }
        }
        return DesignInventory(symbols, links, textures, drawings, bosses, spawned, themed, errors, structureSupplies, structureCreatures)
    }
}
