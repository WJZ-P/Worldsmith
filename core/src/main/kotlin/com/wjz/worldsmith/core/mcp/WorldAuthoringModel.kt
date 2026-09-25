package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.content.WorldContentRegistry
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Session-only authoring provenance. This is not a runtime content module or a player approval. */
@Serializable
data class WorldAuthoringState(
    val contractVersion: Int = 1,
    val bible: WorldBible? = null,
    val bibleRevision: Long = 0,
    val bibleReview: AuthoringReview? = null,
    val briefs: List<BriefRecord> = emptyList(),
    val alignmentReviews: List<AuthoringReview> = emptyList(),
    val reviewAttempts: List<ReviewAttempt> = emptyList(),
)

@Serializable
data class WorldBible(
    val title: String,
    val premise: String,
    val playerRole: String,
    val mainConflict: String,
    val requirements: List<WorldRequirement> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val nodes: List<WorldBibleNode> = emptyList(),
    val requiresBoss: Boolean = false,
    val openDecisions: List<String> = emptyList(),
)

@Serializable data class WorldRequirement(val id: String, val text: String, val promptQuote: String)
@Serializable enum class WorldBibleNodeKind { RULE, HISTORY, REGION, CULTURE, ECOLOGY, RESOURCE, STYLE, EXPERIENCE }
@Serializable enum class WorldBibleTruth { FACT, LEGEND, BELIEF }

@Serializable
data class WorldBibleNode(
    val id: String,
    val kind: WorldBibleNodeKind,
    val title: String,
    val description: String,
    /** Local node IDs, not runtime ContentKeys or full authoring references. */
    val dependsOn: List<String> = emptyList(),
    val truth: WorldBibleTruth = WorldBibleTruth.FACT,
)

@Serializable
data class ModuleBrief(
    val id: String,
    val targets: List<ContentKey>,
    val purpose: String,
    val basisRefs: List<String>,
    val dependencies: List<String> = emptyList(),
    val criteria: List<BriefCriterion>,
    val openDecisions: List<String> = emptyList(),
)

@Serializable data class BriefCriterion(val id: String, val claim: String, val target: ContentKey)
/** The service stamps these fields; clients do not attest their own provenance. */
@Serializable data class BriefRecord(val brief: ModuleBrief, val bibleRevision: Long, val basisDigest: String)
@Serializable enum class ReviewCheckStatus { PASS, BLOCKED }
@Serializable enum class AuthoringReviewSource { AUTHORING_AI }

@Serializable
data class AuthoringReview(
    val id: String,
    val subjectId: String,
    val basisDigest: String,
    val contentDigest: String,
    val summary: String,
    val checks: List<ReviewCheck>,
    val source: AuthoringReviewSource = AuthoringReviewSource.AUTHORING_AI,
    val contractVersion: Int = 1,
)

@Serializable
data class ReviewCheck(
    val criterionId: String,
    val basisRefs: List<String>,
    val claim: String,
    val evidencePaths: List<String>,
    val conclusion: String,
    val status: ReviewCheckStatus,
)

@Serializable data class ReviewAttempt(val subjectId: String, val inputDigest: String, val issueDigest: String, val count: Int)

/** Pure structural checks and provenance; reference presence never proves semantic correctness. */
object WorldAuthoringModel {
    /** Bump when installed authoring/runtime capability contracts change; existing evidence then expires. */
    const val CAPABILITY_CONTRACT = "minecraft26.2-pack10-blocks2-items4-creatures6-abilities1-control1-events2-quests2-story2-structures3-activation1-mechanics1-authoring1-terrainrelief2-terrainblend1-reviewcontext2-visualevidence1"
    const val MAX_BIBLE_BYTES = 512 * 1024
    const val MAX_BRIEF_EDIT_BYTES = 1024 * 1024
    const val MAX_REVIEW_BYTES = 512 * 1024
    const val MAX_STATE_BYTES = 2 * 1024 * 1024
    const val MAX_REQUIREMENTS = 128
    const val MAX_NODES = 256
    const val MAX_BRIEFS = 128
    const val MAX_TARGETS_PER_BRIEF = 128
    const val MAX_CRITERIA = 128
    const val MAX_REVIEW_CHECKS = 512
    const val MAX_REFS = 512
    const val MAX_REVIEW_ATTEMPTS = 512
    private val localId = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val referenceId = Regex("[a-z0-9][a-z0-9_./-]{0,191}")
    private val sha256 = Regex("[a-f0-9]{64}")
    private val targetKinds = setOf("terrain", "anchor", "feature", "biome", "structure", "blueprint", "drawing", "block", "block_item", "item", "creature", "quest", "mechanic", "ability", "theme", "narrative_beat") + com.wjz.worldsmith.core.story.StoryContentModule.kinds

    fun validateBible(bible: WorldBible, originalPrompt: String): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(diagnostic("authoring.bible.$path", code, message)) }
        for ((name, value) in listOf("title" to bible.title, "premise" to bible.premise, "playerRole" to bible.playerRole, "mainConflict" to bible.mainConflict)) {
            if (!text(value, if (name == "title") 160 else 8192)) error(name, "BIBLE_TEXT_INVALID", "Use nonblank bounded setting text")
        }
        if (bible.requirements.size !in 1..MAX_REQUIREMENTS) error("requirements", "BIBLE_REQUIREMENTS_LIMIT", "Record 1..$MAX_REQUIREMENTS explicit requirements grounded in the original prompt")
        if (bible.nodes.size !in 1..MAX_NODES) error("nodes", "BIBLE_NODES_LIMIT", "Use 1..$MAX_NODES stable setting nodes")
        if (!boundedNotes(bible.assumptions)) error("assumptions", "BIBLE_ASSUMPTIONS_INVALID", "Use at most 64 distinct nonblank assumptions of at most 2048 characters")
        if (!boundedNotes(bible.openDecisions)) error("openDecisions", "BIBLE_DECISIONS_INVALID", "Use at most 64 distinct nonblank decisions of at most 2048 characters")
        val requirements = bible.requirements.take(MAX_REQUIREMENTS)
        if (requirements.map { it.id }.distinct().size != requirements.size) error("requirements", "BIBLE_REQUIREMENT_DUPLICATE", "Requirement IDs must be unique")
        requirements.forEachIndexed { i, requirement ->
            if (!localId.matches(requirement.id)) error("requirements[$i].id", "BIBLE_REQUIREMENT_ID", "Use a stable lowercase local ID")
            if (!text(requirement.text, 4096)) error("requirements[$i].text", "BIBLE_REQUIREMENT_TEXT", "Describe the actual user constraint in bounded text")
            if (!text(requirement.promptQuote, 4096) || !originalPrompt.contains(requirement.promptQuote))
                error("requirements[$i].promptQuote", "BIBLE_REQUIREMENT_QUOTE", "Quote an exact nonblank excerpt of the original prompt; inferred choices belong in assumptions")
        }
        val nodes = bible.nodes.take(MAX_NODES)
        if (nodes.map { it.id }.distinct().size != nodes.size) error("nodes", "BIBLE_NODE_DUPLICATE", "Setting node IDs must be unique")
        val ids = nodes.map { it.id }.toSet()
        nodes.forEachIndexed { i, node ->
            if (!localId.matches(node.id)) error("nodes[$i].id", "BIBLE_NODE_ID", "Use a stable lowercase local ID")
            if (!text(node.title, 160) || !text(node.description, 8192)) error("nodes[$i]", "BIBLE_NODE_TEXT", "A node needs a bounded title and a concrete description")
            if (node.dependsOn.size > 64 || node.dependsOn.distinct().size != node.dependsOn.size || node.dependsOn.any { it !in ids || it == node.id })
                error("nodes[$i].dependsOn", "BIBLE_NODE_DEPENDENCY", "Use at most 64 distinct existing node IDs without self-dependencies")
        }
        if (cycle(nodes.associate { it.id to it.dependsOn.take(64) })) error("nodes", "BIBLE_NODE_CYCLE", "Setting dependencies must be acyclic")
    }

    fun validateBriefs(briefs: List<ModuleBrief>, bible: WorldBible): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(diagnostic("authoring.briefs$path", code, message)) }
        if (briefs.size !in 1..MAX_BRIEFS) error("", "BRIEFS_LIMIT", "Use 1..$MAX_BRIEFS module briefs")
        val bounded = briefs.take(MAX_BRIEFS)
        val ids = bounded.map { it.id }.toSet()
        val refs = references(bible).keys
        if (ids.size != bounded.size) error("", "BRIEF_ID_DUPLICATE", "Brief IDs must be unique")
        val owners = mutableMapOf<ContentKey, String>()
        bounded.forEachIndexed { i, brief ->
            if (!localId.matches(brief.id)) error("[$i].id", "BRIEF_ID_INVALID", "Use a stable lowercase local ID")
            if (brief.id == "world_bible") error("[$i].id", "BRIEF_ID_RESERVED", "world_bible is reserved for the setting self-check subject")
            if (!text(brief.purpose, 4096)) error("[$i].purpose", "BRIEF_PURPOSE_INVALID", "Describe this brief's concrete implementation role")
            if (brief.targets.size !in 1..MAX_TARGETS_PER_BRIEF || brief.targets.distinct().size != brief.targets.size || brief.targets.any { !validTarget(it) })
                error("[$i].targets", "BRIEF_TARGETS_INVALID", "Name distinct supported content targets with valid logical IDs")
            brief.targets.take(MAX_TARGETS_PER_BRIEF).forEach { target ->
                val previous = owners.putIfAbsent(target, brief.id)
                if (previous != null && previous != brief.id) error("[$i].targets", "BRIEF_TARGET_OWNER_DUPLICATE", "Each target has one owning brief; shared content is referenced through dependencies")
            }
            if (!boundedRefs(brief.basisRefs) || brief.basisRefs.any { it !in refs }) error("[$i].basisRefs", "BRIEF_BASIS_INVALID", "Use distinct existing world, requirement or node references")
            if (brief.dependencies.size > MAX_BRIEFS || brief.dependencies.distinct().size != brief.dependencies.size || brief.dependencies.any { it !in ids || it == brief.id })
                error("[$i].dependencies", "BRIEF_DEPENDENCY_INVALID", "Dependencies name distinct other existing briefs")
            if (brief.criteria.size !in 1..MAX_CRITERIA || brief.criteria.map { it.id }.distinct().size != brief.criteria.size)
                error("[$i].criteria", "BRIEF_CRITERIA_INVALID", "Use 1..$MAX_CRITERIA uniquely named acceptance criteria")
            brief.criteria.take(MAX_CRITERIA).forEachIndexed { j, criterion ->
                if (!localId.matches(criterion.id) || !text(criterion.claim, 4096) || criterion.target !in brief.targets)
                    error("[$i].criteria[$j]", "BRIEF_CRITERION_INVALID", "Each criterion needs a stable ID, a concrete claim and a target owned by this brief")
            }
            if (brief.targets.any { target -> brief.criteria.none { it.target == target } }) error("[$i].criteria", "BRIEF_TARGET_CRITERION_MISSING", "Every owned target needs at least one acceptance criterion")
            if (!boundedNotes(brief.openDecisions)) error("[$i].openDecisions", "BRIEF_DECISIONS_INVALID", "Use at most 64 bounded distinct unresolved decisions")
        }
        if (cycle(bounded.associate { it.id to it.dependencies.take(MAX_BRIEFS) })) error("", "BRIEF_DEPENDENCY_CYCLE", "Brief dependencies must be acyclic")
    }

    /** Shape validation only. The policy resolves evidence paths and required coverage against current inputs. */
    fun validateReview(review: AuthoringReview): List<Diagnostic> = buildList {
        fun error(path: String, code: String, message: String) { add(diagnostic("authoring.review.$path", code, message)) }
        if (review.contractVersion != 1) error("contractVersion", "AUTHORING_REVIEW_VERSION", "Review contract version must be 1")
        if (!localId.matches(review.id)) error("id", "AUTHORING_REVIEW_ID", "Use a stable lowercase local review ID")
        if (!referenceId.matches(review.subjectId)) error("subjectId", "AUTHORING_REVIEW_SUBJECT", "Name the reviewed Bible or brief using a stable subject ID")
        if (!sha256.matches(review.basisDigest) || !sha256.matches(review.contentDigest)) error("digests", "AUTHORING_REVIEW_DIGEST", "Bind the report to both actual SHA-256 input digests")
        if (!text(review.summary, 4096)) error("summary", "AUTHORING_REVIEW_SUMMARY", "Summarize actual findings rather than only attaching references")
        if (review.checks.size !in 1..MAX_REVIEW_CHECKS || review.checks.map { it.criterionId }.distinct().size != review.checks.size)
            error("checks", "AUTHORING_REVIEW_CHECKS", "Use 1..$MAX_REVIEW_CHECKS distinct criterion checks")
        review.checks.take(MAX_REVIEW_CHECKS).forEachIndexed { i, check ->
            if (!referenceId.matches(check.criterionId)) error("checks[$i].criterionId", "AUTHORING_REVIEW_CRITERION", "Name a concrete criterion or required Bible reference")
            if (!boundedRefs(check.basisRefs)) error("checks[$i].basisRefs", "AUTHORING_REVIEW_BASIS", "List bounded distinct source references")
            if (!text(check.claim, 4096) || !text(check.conclusion, 4096))
                error("checks[$i]", "AUTHORING_REVIEW_FINDING", "Both PASS and BLOCKED need an explicit claim and a reasoned conclusion")
            if (check.evidencePaths.size !in 1..64 || check.evidencePaths.distinct().size != check.evidencePaths.size || check.evidencePaths.any { !text(it, 512) })
                error("checks[$i].evidencePaths", "AUTHORING_REVIEW_EVIDENCE", "Both PASS and BLOCKED need distinct bounded paths to actual input evidence")
        }
    }

    /** Canonical facts indexed by authoring IDs; none of these add a runtime ContentKey kind. */
    fun references(bible: WorldBible): Map<String, String> = java.util.Collections.unmodifiableMap(sortedMapOf<String, String>().apply {
        put("world/title", bible.title)
        put("world/premise", bible.premise)
        put("world/player_role", bible.playerRole)
        put("world/conflict", bible.mainConflict)
        put("world/requires_boss", bible.requiresBoss.toString())
        put("world/assumptions", strings(bible.assumptions.sorted()).toString())
        put("world/open_decisions", strings(bible.openDecisions.sorted()).toString())
        bible.requirements.forEach { requirement -> put("requirement/${requirement.id}", buildJsonObject {
            put("id", requirement.id); put("text", requirement.text); put("promptQuote", requirement.promptQuote)
        }.toString()) }
        bible.nodes.forEach { node -> put("node/${node.id}", buildJsonObject {
            put("id", node.id); put("kind", node.kind.name); put("title", node.title); put("description", node.description)
            put("truth", node.truth.name); put("dependsOn", strings(node.dependsOn.sorted()))
        }.toString()) }
    })

    fun bibleDigest(bible: WorldBible): String = digest("world-bible-v1:$CAPABILITY_CONTRACT", references(bible))

    /** Hard requirements and core rules always affect all briefs, including dependencies of a rule. */
    fun globalDigest(bible: WorldBible): String {
        val all = references(bible)
        val global = all.keys.filter { it.startsWith("world/") || it.startsWith("requirement/") }.toMutableSet()
        global.addAll(expandNodeRefs(bible, bible.nodes.filter { it.kind == WorldBibleNodeKind.RULE }.map { "node/${it.id}" }))
        return digest("world-bible-global-v1:$CAPABILITY_CONTRACT", global.associateWith { all[it] ?: "<missing>" })
    }

    /** Missing references remain digestible so deleting a node marks old records stale instead of throwing. */
    fun basisDigest(bible: WorldBible, refs: Collection<String>): String {
        val all = references(bible)
        val values = expandNodeRefs(bible, refs).associateWith { all[it] ?: "<missing>" }.toMutableMap()
        values["@global"] = globalDigest(bible)
        return digest("world-bible-basis-v1", values)
    }

    /** A read-only projection, never a second editable copy of the world's facts. */
    fun markdown(bible: WorldBible): String = buildString {
        appendLine("# ${markdownText(bible.title)}")
        appendLine()
        appendLine("> Authoring world bible · generated view · not installed gameplay or user approval")
        for ((heading, value) in listOf("Premise" to bible.premise, "Player role" to bible.playerRole, "Main conflict" to bible.mainConflict)) {
            appendLine(); appendLine("## $heading"); appendLine(markdownText(value))
        }
        appendLine(); appendLine("## Requirements")
        bible.requirements.forEach {
            appendLine("- **${markdownText(it.id)}**: ${markdownText(it.text)}")
            appendLine("  - Prompt quote: ${markdownText(it.promptQuote)}")
        }
        appendLine(); appendLine("## Author assumptions")
        bible.assumptions.forEach { appendLine("- ${markdownText(it)}") }
        appendLine(); appendLine("## Setting nodes")
        bible.nodes.forEach {
            appendLine(); appendLine("### ${markdownText(it.title)} (${it.kind.name} · ${it.truth.name})")
            appendLine("- ID: ${markdownText(it.id)}")
            appendLine(markdownText(it.description))
            if (it.dependsOn.isNotEmpty()) appendLine("- Depends on: ${it.dependsOn.joinToString(", ") { id -> markdownText(id) }}")
        }
        appendLine(); appendLine("## Open decisions")
        bible.openDecisions.forEach { appendLine("- ${markdownText(it)}") }
        appendLine(); appendLine("- Requires a Boss: ${bible.requiresBoss}")
    }

    /** Copy every nested collection, including collections held through Kotlin's read-only List interface. */
    fun freezeState(state: WorldAuthoringState): WorldAuthoringState = state.copy(
        bible = state.bible?.let { bible -> bible.copy(
            requirements = immutable(bible.requirements), assumptions = immutable(bible.assumptions),
            nodes = immutable(bible.nodes.map { it.copy(dependsOn = immutable(it.dependsOn)) }),
            openDecisions = immutable(bible.openDecisions),
        ) },
        bibleReview = state.bibleReview?.let(::freezeReview),
        briefs = immutable(state.briefs.map { record -> record.copy(brief = record.brief.let { brief -> brief.copy(
            targets = immutable(brief.targets), basisRefs = immutable(brief.basisRefs), dependencies = immutable(brief.dependencies),
            criteria = immutable(brief.criteria), openDecisions = immutable(brief.openDecisions),
        ) }) }),
        alignmentReviews = immutable(state.alignmentReviews.map(::freezeReview)),
        reviewAttempts = immutable(state.reviewAttempts),
    )

    private fun freezeReview(review: AuthoringReview) = review.copy(checks = immutable(review.checks.map { it.copy(
        basisRefs = immutable(it.basisRefs), evidencePaths = immutable(it.evidencePaths),
    ) }))

    private fun expandNodeRefs(bible: WorldBible, refs: Collection<String>): Set<String> {
        val nodes = bible.nodes.associateBy { it.id }
        val result = refs.toMutableSet()
        val pending = java.util.ArrayDeque(refs)
        while (pending.isNotEmpty()) {
            val ref = pending.removeFirst()
            if (!ref.startsWith("node/")) continue
            nodes[ref.removePrefix("node/")]?.dependsOn?.forEach { dependency ->
                val next = "node/$dependency"
                if (result.add(next)) pending.addLast(next)
            }
        }
        return result
    }

    private fun cycle(graph: Map<String, List<String>>): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited || id !in graph) return false
            visiting.add(id)
            if (graph.getValue(id).any(::visit)) return true
            visiting.remove(id); visited.add(id)
            return false
        }
        return graph.keys.any(::visit)
    }

    private fun digest(namespace: String, entries: Map<String, String>): String {
        val canonical = buildJsonObject {
            put("contract", namespace)
            put("entries", buildJsonObject { entries.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }.toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun text(value: String, limit: Int) = value.isNotBlank() && value.length <= limit
    private fun boundedNotes(values: List<String>) = values.size <= 64 && values.distinct().size == values.size && values.all { text(it, 2048) }
    private fun boundedRefs(values: List<String>) = values.size in 1..MAX_REFS && values.distinct().size == values.size && values.all { referenceId.matches(it) }
    private fun validTarget(key: ContentKey) = key.kind in targetKinds && WorldContentRegistry.validName(key.id)
    private fun diagnostic(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
    private fun <T> immutable(values: List<T>): List<T> = java.util.List.copyOf(values)
    private fun markdownText(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\\', '`', '*', '_', '[', ']', '#', '|' -> { append('\\'); append(c) }
                '\r' -> Unit
                '\n' -> append("  \n")
                else -> append(c)
            }
        }
    }
}
