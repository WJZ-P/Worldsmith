package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.content.WorldContentRegistry
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Old sessions and callers remain worldgen-focused; complete-world coverage is an explicit promise. */
@Serializable enum class WorkflowMode { WORLDGEN_ONLY, COMPLETE_WORLD, STANDALONE }

@Serializable
data class WorldDesignPlan(
    val schemaVersion: Int = 1,
    val goal: String,
    val targets: List<DesignTarget>,
    val links: List<DesignLink>,
    val bosses: List<DesignBoss> = emptyList(),
)

@Serializable data class DesignTarget(val key: ContentKey, val title: String, val purpose: String)
@Serializable data class DesignLink(val from: ContentKey, val to: ContentKey, val relation: DesignRelation)
@Serializable data class DesignBoss(val creature: String, val quest: String)

@Serializable enum class DesignRelation {
    @SerialName("placed_in_biome") PLACED_IN_BIOME,
    @SerialName("spawns_in_biome") SPAWNS_IN_BIOME,
    @SerialName("uses_block") USES_BLOCK,
    @SerialName("uses_feature") USES_FEATURE,
    @SerialName("drops_item") DROPS_ITEM,
    @SerialName("contains_reward") CONTAINS_REWARD,
    @SerialName("contains_encounter") CONTAINS_ENCOUNTER,
    @SerialName("kill_objective") KILL_OBJECTIVE,
    @SerialName("delivery_objective") DELIVERY_OBJECTIVE,
    @SerialName("quest_reward") QUEST_REWARD,
    @SerialName("prerequisite") PREREQUISITE,
    @SerialName("theme_anchor") THEME_ANCHOR,
}

object WorldDesignPlans {
    const val MAX_TARGETS = 512
    const val MAX_LINKS = 2048
    private val requiredKinds = setOf("biome", "structure", "creature", "block", "item", "quest")
    private val targetKinds = requiredKinds + setOf("terrain", "anchor", "feature", "blueprint", "theme", "narrative_beat")
    private val ambientKinds = setOf("terrain", "anchor", "feature", "blueprint", "theme", "narrative_beat")

    fun validate(plan: WorldDesignPlan, completeWorld: Boolean = true): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(Diagnostic(path, code, DiagnosticSeverity.ERROR, message)) }
        if (plan.schemaVersion != 1) error("designPlan.schemaVersion", "DESIGN_SCHEMA_UNSUPPORTED", "World design plan schema must be 1")
        if (plan.goal.isBlank() || plan.goal.length > 8192) error("designPlan.goal", "DESIGN_GOAL_INVALID", "Record the player's world goal in 1..8192 characters")
        if (plan.targets.size !in 1..MAX_TARGETS || plan.links.size > MAX_LINKS || plan.bosses.size > 16) {
            error("designPlan", "DESIGN_PLAN_LIMIT", "Use 1..$MAX_TARGETS targets, at most $MAX_LINKS real relationship promises and at most 16 bosses")
            return@buildList
        }
        val targets = plan.targets.map { it.key }.toSet()
        if (targets.size != plan.targets.size) error("designPlan.targets", "DESIGN_TARGET_DUPLICATE", "Each logical target is named exactly once")
        plan.targets.forEachIndexed { i, target ->
            if (target.key.kind !in targetKinds || !WorldContentRegistry.validName(target.key.id)) error("designPlan.targets[$i].key", "DESIGN_TARGET_INVALID", "Use a supported logical content kind and local id")
            if (target.title.isBlank() || target.title.length > 160 || target.purpose.isBlank() || target.purpose.length > 2048)
                error("designPlan.targets[$i]", "DESIGN_TARGET_INTENT", "Every named target needs a concise title and concrete role in this world's gameplay or landscape")
        }
        if (completeWorld) {
            (requiredKinds - targets.map { it.kind }.toSet()).sorted().forEach { kind ->
                error("designPlan.targets", "DESIGN_CATEGORY_MISSING", "A complete world must explicitly name at least one $kind; an empty module does not meet that promise")
            }
            if (plan.bosses.isEmpty()) error("designPlan.bosses", "DESIGN_BOSS_MISSING", "A complete-world plan names at least one actual Boss encounter and its kill quest")
        }
        if (plan.links.distinct().size != plan.links.size) error("designPlan.links", "DESIGN_LINK_DUPLICATE", "Do not repeat an identical relationship promise")
        fun declared(key: ContentKey) = key in targets || key.kind in ambientKinds || key.kind == "block_item" && ContentKey("block", key.id) in targets
        plan.links.forEachIndexed { i, link ->
            if (!declared(link.from) || !declared(link.to)) error("designPlan.links[$i]", "DESIGN_LINK_UNDECLARED", "Name both substantive endpoints in targets; theme/terrain/feature anchors may be implicit")
            if (!validRelation(link)) error("designPlan.links[$i]", "DESIGN_RELATION_KIND", "Relationship ${link.relation} does not connect those content kinds")
            if (!WorldContentRegistry.validName(link.from.id) || !WorldContentRegistry.validName(link.to.id)) error("designPlan.links[$i]", "DESIGN_LINK_ID", "Relationship endpoints use normalized local ids")
        }
        if (completeWorld) plan.targets.forEachIndexed { i, target ->
            if (plan.links.none { touches(it, target.key) }) error("designPlan.targets[$i]", "DESIGN_TARGET_UNCONNECTED", "Connect ${target.key.kind}/${target.key.id} to actual habitat, material use, reward, objective or theme content")
        }
        if (plan.bosses.map { it.creature }.distinct().size != plan.bosses.size) error("designPlan.bosses", "DESIGN_BOSS_DUPLICATE", "A Boss creature is declared once")
        plan.bosses.forEachIndexed { i, boss ->
            if (ContentKey("creature", boss.creature) !in targets || ContentKey("quest", boss.quest) !in targets)
                error("designPlan.bosses[$i]", "DESIGN_BOSS_TARGETS", "A Boss must name a planned creature and a planned quest")
            if (DesignLink(ContentKey("quest", boss.quest), ContentKey("creature", boss.creature), DesignRelation.KILL_OBJECTIVE) !in plan.links)
                error("designPlan.bosses[$i]", "DESIGN_BOSS_KILL_LINK", "Declare the actual kill_objective link from this main-line quest to its Boss")
        }
    }

    fun freeze(plan: WorldDesignPlan) = plan.copy(targets = java.util.List.copyOf(plan.targets), links = java.util.List.copyOf(plan.links), bosses = java.util.List.copyOf(plan.bosses))
    fun touches(link: DesignLink, key: ContentKey): Boolean = link.from == key || link.to == key ||
        key.kind == "block" && (link.from == ContentKey("block_item", key.id) || link.to == ContentKey("block_item", key.id))

    private fun validRelation(link: DesignLink): Boolean {
        val from = link.from.kind; val to = link.to.kind
        return when (link.relation) {
            DesignRelation.PLACED_IN_BIOME -> from == "structure" && to == "biome"
            DesignRelation.SPAWNS_IN_BIOME -> from == "creature" && to == "biome"
            DesignRelation.USES_BLOCK -> from in setOf("terrain", "biome", "feature", "structure") && to == "block"
            DesignRelation.USES_FEATURE -> from == "biome" && to == "feature"
            DesignRelation.DROPS_ITEM -> from == "creature" && to in setOf("item", "block_item")
            DesignRelation.CONTAINS_REWARD -> from == "structure" && to in setOf("item", "block_item")
            DesignRelation.CONTAINS_ENCOUNTER -> from == "structure" && to == "creature"
            DesignRelation.KILL_OBJECTIVE -> from == "quest" && to == "creature"
            DesignRelation.DELIVERY_OBJECTIVE, DesignRelation.QUEST_REWARD -> from == "quest" && to in setOf("item", "block_item")
            DesignRelation.PREREQUISITE -> from == "quest" && to == "quest"
            DesignRelation.THEME_ANCHOR -> from in setOf("theme", "narrative_beat") && to in targetKinds + "block_item"
        }
    }
}
