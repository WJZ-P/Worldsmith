package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Authoring provenance gates, not a semantic oracle. An external authoring AI supplies the findings;
 * Core checks their coverage, real input evidence and freshness, then retains the existing pack gates.
 * No model call, source execution, asset I/O or native activation takes place here.
 */
object WorldAuthoringPolicy {
    private const val BIBLE = "world_bible"

    fun bibleProblems(session: WorkflowSession): List<Diagnostic> {
        if (!enabled(session)) return emptyList()
        val state = requireNotNull(session.authoring)
        val bible = state.bible ?: return listOf(problem("authoring.bible", "WORLD_BIBLE_REQUIRED", "Author the world bible before deriving content"))
        val result = WorldAuthoringModel.validateBible(bible, session.prompt).map { it.copy(code = "WORLD_${it.code}") }.toMutableList()
        if (state.contractVersion != 1) result += problem("authoring.contractVersion", "WORLD_BIBLE_CONTRACT_VERSION", "Unsupported authoring contract version")
        if (bible.openDecisions.isNotEmpty()) result += problem("authoring.bible.openDecisions", "WORLD_BIBLE_OPEN_DECISIONS", "Resolve the recorded setting decisions before automatic production")
        val review = state.bibleReview
        if (review == null) result += problem("authoring.bibleReview", "WORLD_BIBLE_REVIEW_REQUIRED", "The authoring AI must review the current setting before continuing")
        else {
            if (review.subjectId != BIBLE) result += problem("authoring.bibleReview.subjectId", "WORLD_BIBLE_REVIEW_SUBJECT", "This report must review world_bible")
            result += validateReviewForSession(session, review)
            if (review.checks.any { it.status == ReviewCheckStatus.BLOCKED }) result += problem("authoring.bibleReview", "WORLD_BIBLE_REVIEW_BLOCKED", "The current setting review has unresolved findings; repair and review it again")
        }
        return result
    }

    /** Production can repair existing content: implementation-review failures are deliberately absent. */
    fun productionProblems(session: WorkflowSession, targets: Set<ContentKey> = emptySet()): List<Diagnostic> {
        if (!enabled(session)) return emptyList()
        val result = bibleProblems(session).toMutableList()
        val state = requireNotNull(session.authoring)
        val bible = state.bible ?: return result
        val plan = session.designPlan
        if (plan == null) result += problem("designPlan", "AUTHORING_BRIEF_PLAN_REQUIRED", "Commit the named design plan before producing its targets")
        else result += WorldDesignPlans.validate(plan, true, bible.requiresBoss).map { it.copy(code = "AUTHORING_BRIEF_${it.code}") }
        result += WorldAuthoringModel.validateBriefs(state.briefs.map { it.brief }, bible).map { diagnostic ->
            diagnostic.copy(code = "AUTHORING_BRIEF_${diagnostic.code}", path = Regex("authoring\\.briefs\\[(\\d+)]").replace(diagnostic.path) { match ->
                briefPath(state.briefs.getOrNull(match.groupValues[1].toInt())?.brief?.id ?: match.groupValues[1])
            })
        }
        val owners = state.briefs.flatMap { record -> record.brief.targets.map { it to record.brief.id } }.groupBy({ it.first }, { it.second })
        (plan?.targets.orEmpty().map { it.key }.toSet() + targets).forEach { target ->
            if (owners[target].orEmpty().size != 1) result += problem("authoring.briefs", "AUTHORING_BRIEF_TARGET_COVERAGE", "${key(target)} needs exactly one owning brief")
        }
        state.briefs.take(WorldAuthoringModel.MAX_BRIEFS).forEach { record ->
            val path = briefPath(record.brief.id)
            if (record.brief.id == BIBLE) result += problem(path, "AUTHORING_BRIEF_RESERVED_ID", "world_bible is the setting review subject, not a module brief ID")
            if (record.brief.openDecisions.isNotEmpty()) result += problem("$path.openDecisions", "AUTHORING_BRIEF_OPEN_DECISIONS", "Resolve this brief's decisions before producing its content")
            if (record.bibleRevision < 0 || record.bibleRevision > state.bibleRevision || record.basisDigest != basisDigest(state, record.brief))
                result += problem(path, "AUTHORING_BRIEF_STALE", "This brief's setting or explicit dependencies changed; revise or reaffirm the brief against the current basis")
        }
        return result
    }

    /** Checks current session content; publication uses the same policy with frozen pack inputs. */
    fun alignmentProblems(session: WorkflowSession): List<Diagnostic> {
        if (!enabled(session)) return emptyList()
        val documents = modules(session)
        val readiness = productionProblems(session, actualTargets(documents))
        if (readiness.isNotEmpty()) return readiness
        val state = requireNotNull(session.authoring)
        val theme = documents["theme"]
        val themeId = (theme?.get("id") as? JsonPrimitive)?.contentOrNull
        if (theme != null && (theme["title"] as? JsonPrimitive)?.contentOrNull != requireNotNull(state.bible).title) {
            val owner = state.briefs.singleOrNull { record -> ContentKey("theme", themeId ?: "main") in record.brief.targets }?.brief?.id
            val path = owner?.let { "${briefPath(it)}.title" } ?: "theme.title"
            return listOf(problem(path, "AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH", "The runtime theme title must match the current WorldBible title; update the theme module, then review the changed content"))
        }
        val result = mutableListOf<Diagnostic>()
        val coveredRequirements = mutableSetOf<String>()
        state.briefs.take(WorldAuthoringModel.MAX_BRIEFS).forEach { record ->
            val subject = record.brief.id
            val review = state.alignmentReviews.lastOrNull { it.subjectId == subject }
            if (review == null) {
                result += problem("${briefPath(subject)}.review", "AUTHORING_ALIGNMENT_REVIEW_REQUIRED", "Review this brief against the actual implemented content")
                return@forEach
            }
            val context = context(session, subject, documents)
            val errors = validateReview(session, review, context)
            result += errors
            if (review.checks.any { it.status == ReviewCheckStatus.BLOCKED }) result += problem("${briefPath(subject)}.review", "AUTHORING_ALIGNMENT_REVIEW_BLOCKED", "This implementation review contains unresolved findings")
            if (errors.isEmpty()) coveredRequirements += review.checks.filter { it.status == ReviewCheckStatus.PASS && it.criterionId.startsWith("requirement/") }.map { it.criterionId }
        }
        requireNotNull(state.bible).requirements.forEach { requirement ->
            if ("requirement/${requirement.id}" !in coveredRequirements) result += problem("authoring.bible.requirements.${requirement.id}", "AUTHORING_ALIGNMENT_REQUIREMENT_UNCOVERED", "Hard requirement '${requirement.id}' needs a current passing implementation check with actual content evidence")
        }
        return result
    }

    /**
     * Never accepts a report for session drafts as proof of different inline/frozen publication content.
     * Package/theme titles project the Bible title. Manifest description remains ordinary display
     * metadata subject to the existing player-text policy, not an additional semantic-review subject.
     */
    fun publicationProblems(session: WorkflowSession, pack: WorldsmithPack): List<Diagnostic> {
        if (!enabled(session)) return emptyList()
        val documents = ExistingWorldContentModules.input(pack).modules +
            ("structures" to McpJson.encode(pack.structures.copy(artifacts = emptyMap(), sources = emptyMap())).jsonObject)
        val metadata = pack.manifest.assets.associateBy { it.id }
        val assets = pack.assets.mapValues { (id, bytes) ->
            ContentAsset(id, digestBytes(bytes), metadata[id]?.mediaType ?: "image/png", bytes.size.toLong())
        }
        val result = alignmentProblems(session.copy(contentModules = documents,
            structures = pack.structures.structures.associateBy { it.id }, architecture = pack.structures.architecture,
            contentAssets = assets)).toMutableList()
        session.authoring?.bible?.let { bible ->
            if (pack.manifest.displayName != bible.title) result += problem("manifest.displayName", "AUTHORING_ALIGNMENT_PACK_TITLE_MISMATCH",
                "The actual saved package displayName must match the current WorldBible title; use the setting title for publication")
        }
        return result
    }

    /** Definition and dependency closure, never a global session revision or a previous stored digest. */
    fun basisDigest(state: WorldAuthoringState, brief: ModuleBrief): String {
        val closure = closure(state, brief)
        return digest(buildJsonObject {
            put("contract", "authoring-brief-basis-v1"); put("root", brief.id)
            put("briefs", JsonArray(closure.briefs.map { value -> buildJsonObject {
                put("brief", McpJson.encode(value))
                put("basis", state.bible?.let { WorldAuthoringModel.basisDigest(it, value.basisRefs) } ?: "<missing-bible>")
            } }))
            put("missing", strings(closure.missing.sorted())); put("cycles", strings(closure.cycles.sorted()))
        })
    }

    /** Conservative module-level invalidation, including explicit brief dependencies, not upload order. */
    fun contentDigest(session: WorkflowSession, brief: ModuleBrief): String = contentDigest(session, brief, modules(session))

    /** JSON Pointers resolve in evidenceDocument; an existing target subtree may supply deeper paths. */
    fun reviewContext(session: WorkflowSession, subjectId: String): JsonObject {
        val current = context(session, subjectId, if (subjectId == BIBLE) emptyMap() else modules(session))
        return buildJsonObject {
            put("sessionId", session.id); put("subjectId", subjectId)
            put("source", AuthoringReviewSource.AUTHORING_AI.name)
            put("semanticCorrectnessProven", false)
            if (current == null) {
                put("ready", false); put("message", "The requested world bible or unique brief is missing")
            } else {
                put("ready", true)
                put("expectedBasisDigest", current.basis); put("expectedContentDigest", current.content)
                put("expectedInputDigest", inputDigest(current))
                put("requiredCheckIds", strings(current.required.sorted()))
                put("allowedCheckIds", strings(current.allowedChecks.sorted()))
                put("allowedBasisRefs", strings(current.allowedRefs.sorted()))
                put("evidenceRoots", buildJsonObject { current.roots.filterKeys { it in current.required }.toSortedMap().forEach { (id, paths) -> put(id, strings(paths.take(16))) } })
                put("blockedEvidenceRoots", buildJsonObject { current.blockedRoots.filterKeys { it in current.required }.toSortedMap().forEach { (id, paths) -> put(id, strings(paths.take(16))) } })
                put("evidenceRootLimitPerCheck", 16)
                val fullEvidence = current.document.toString().length <= 96 * 1024
                put("evidenceDocumentIncluded", fullEvidence)
                if (fullEvidence) put("evidenceDocument", current.document)
                else put("evidencePreview", JsonArray(current.roots.values.flatten().distinct().take(16).map { pointer -> buildJsonObject {
                    put("path", pointer)
                    val raw = resolve(current.document, pointer)?.toString().orEmpty()
                    put("jsonExcerpt", raw.take(2048)); put("truncated", raw.length > 2048)
                } }))
                put("instruction", "Author a finding for every requiredCheckId using current digests, source references and JSON Pointer evidence. PASS and BLOCKED both need findings. The evidenceRoots list contains at most 16 example roots per required check; existing descendants are valid. Optional requirement checks may cite any actual target owned by this brief. blockedEvidenceRoots are only for BLOCKED absence findings. When evidenceDocumentIncluded=false, inspect the current content draft/structure definitions using the content tools; excerpts are not the complete evidence. Core validates provenance and coverage, not semantic truth. A passing setting review continues automatically without a user-approval step.")
            }
        }
    }

    /** BLOCKED is a legitimate report to persist; it blocks readiness, not report submission. */
    fun validateReviewForSession(session: WorkflowSession, review: AuthoringReview): List<Diagnostic> =
        validateReview(session, review, context(session, review.subjectId, if (review.subjectId == BIBLE) emptyMap() else modules(session)))

    /** UI projections inspect bounded session inputs only, never worker state or native resources. */
    fun hasCurrentBibleReview(session: WorkflowSession): Boolean = enabled(session) && session.authoring?.bibleReview != null && bibleProblems(session).isEmpty()

    fun currentReviewedBriefCount(session: WorkflowSession): Int {
        if (!enabled(session)) return 0
        val state = requireNotNull(session.authoring)
        val bible = state.bible ?: return 0
        if (!hasCurrentBibleReview(session) || WorldAuthoringModel.validateBriefs(state.briefs.map { it.brief }, bible).isNotEmpty()) return 0
        val documents = modules(session)
        return state.briefs.count { record ->
            val report = state.alignmentReviews.lastOrNull { it.subjectId == record.brief.id }
            record.brief.id != BIBLE && record.brief.openDecisions.isEmpty() && record.bibleRevision in 0L..state.bibleRevision &&
                record.basisDigest == basisDigest(state, record.brief) && report != null &&
                report.checks.all { it.status == ReviewCheckStatus.PASS } && validateReview(session, report, context(session, record.brief.id, documents)).isEmpty()
        }
    }

    fun repeatedBlocker(session: WorkflowSession, subjectId: String): Boolean {
        if (!enabled(session)) return false
        val state = requireNotNull(session.authoring)
        val current = context(session, subjectId, if (subjectId == BIBLE) emptyMap() else modules(session)) ?: return false
        val review = if (subjectId == BIBLE) state.bibleReview else state.alignmentReviews.lastOrNull { it.subjectId == subjectId }
        if (review == null || review.basisDigest != current.basis || review.contentDigest != current.content || review.checks.none { it.status == ReviewCheckStatus.BLOCKED } || validateReview(session, review, current).isNotEmpty()) return false
        return state.reviewAttempts.any { it.subjectId == subjectId && it.inputDigest == inputDigest(current) && it.count >= 3 }
    }

    private data class Closure(val briefs: List<ModuleBrief>, val missing: Set<String>, val cycles: Set<String>)

    private fun closure(state: WorldAuthoringState, root: ModuleBrief): Closure {
        val byId = state.briefs.take(WorldAuthoringModel.MAX_BRIEFS).associate { it.brief.id to it.brief } + (root.id to root)
        val done = linkedMapOf<String, ModuleBrief>(); val visiting = linkedSetOf<String>()
        val missing = linkedSetOf<String>(); val cycles = linkedSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) { cycles += id; return }
            if (id in done) return
            val brief = byId[id] ?: run { missing += id; return }
            visiting += id
            brief.dependencies.take(WorldAuthoringModel.MAX_BRIEFS).sorted().forEach(::visit)
            visiting -= id; done[id] = brief
        }
        visit(root.id)
        return Closure(done.toSortedMap().values.toList(), missing, cycles)
    }

    private data class ReviewContext(
        val basis: String, val content: String, val required: Set<String>, val allowedChecks: Set<String>,
        val allowedRefs: Set<String>, val roots: Map<String, List<String>>, val blockedRoots: Map<String, List<String>>,
        val document: JsonObject,
    )

    private fun context(session: WorkflowSession, subjectId: String, documents: Map<String, JsonObject>): ReviewContext? {
        val state = session.authoring ?: return null
        val bible = state.bible ?: return null
        val refs = WorldAuthoringModel.references(bible).keys
        if (subjectId == BIBLE) {
            val roots = bible.requirements.mapIndexed { i, requirement -> "requirement/${requirement.id}" to listOf("/bible/requirements/$i") }.toMap() +
                bible.nodes.mapIndexed { i, node -> "node/${node.id}" to listOf("/bible/nodes/$i") }.toMap() + ("global" to listOf("/bible"))
            val hash = WorldAuthoringModel.bibleDigest(bible)
            return ReviewContext(hash, hash, roots.keys, roots.keys, refs, roots, emptyMap(), buildJsonObject { put("bible", McpJson.encode(bible)) })
        }
        val brief = state.briefs.singleOrNull { it.brief.id == subjectId }?.brief ?: return null
        val requirements = refs.filter { it.startsWith("requirement/") }.toSet()
        val required = brief.criteria.map { it.id }.toSet() + brief.basisRefs.filter { it in requirements }
        val content = contentDigest(session, brief, documents)
        val document = evidenceDocument(session, documents)
        val targetRoots = brief.targets.associateWith { target -> targetRoots(target, documents) }
        fun withAssets(paths: List<String>): List<String> = (paths + paths.flatMap { path -> textureIds(resolve(document, path)).filter { it in session.contentAssets }.map { "/assets/$it" } }).distinct()
        val allRoots = withAssets(targetRoots.values.flatten())
        val fallbacks = brief.targets.associateWith { target -> listOf(fallbackRoot(target, document)) }
        val roots = brief.criteria.associate { it.id to withAssets(targetRoots[it.target].orEmpty()) } + requirements.associateWith { allRoots }
        val blockedRoots = brief.criteria.associate { it.id to fallbacks.getValue(it.target) } + requirements.associateWith { fallbacks.values.flatten().distinct() }
        val basisRefs = closure(state, brief).briefs.flatMap { it.basisRefs }.toMutableSet()
        // Core requirements and rules are part of every basis digest, even when not individually named.
        basisRefs += refs.filter { it.startsWith("world/") || it.startsWith("requirement/") }
        basisRefs += bible.nodes.filter { it.kind == WorldBibleNodeKind.RULE }.map { "node/${it.id}" }
        val nodes = bible.nodes.associateBy { it.id }
        val pending = java.util.ArrayDeque(basisRefs)
        while (pending.isNotEmpty()) nodes[pending.removeFirst().removePrefix("node/")]?.dependsOn.orEmpty().forEach { id ->
            if (basisRefs.add("node/$id")) pending.addLast("node/$id")
        }
        return ReviewContext(basisDigest(state, brief), content, required, brief.criteria.map { it.id }.toSet() + requirements,
            basisRefs.intersect(refs), roots, blockedRoots, document)
    }

    private fun validateReview(session: WorkflowSession, review: AuthoringReview, current: ReviewContext?): List<Diagnostic> {
        val prefix = if (review.subjectId == BIBLE) "WORLD_BIBLE" else "AUTHORING_ALIGNMENT"
        val path = if (review.subjectId == BIBLE) "authoring.bibleReview" else "${briefPath(review.subjectId)}.review"
        val result = WorldAuthoringModel.validateReview(review).map { it.copy(path = "$path.${it.path.substringAfterLast('.')}", code = "${prefix}_${it.code}") }.toMutableList()
        if (current == null) return result + problem(path, "${prefix}_CONTEXT_MISSING", "Review an existing world bible or unique current brief")
        if (session.authoring?.contractVersion != review.contractVersion) result += problem(path, "${prefix}_REVIEW_CONTRACT", "The report uses a different authoring contract")
        if (review.basisDigest != current.basis || review.contentDigest != current.content) result += problem(path, "${prefix}_REVIEW_STALE", "This report was authored against different setting or implementation inputs")
        val present = review.checks.map { it.criterionId }.toSet()
        if (!present.containsAll(current.required)) result += problem(path, "${prefix}_REVIEW_INCOMPLETE", "Missing checks: ${(current.required - present).sorted().joinToString()}")
        review.checks.take(WorldAuthoringModel.MAX_REVIEW_CHECKS).forEachIndexed { i, check ->
            val at = "$path.checks[$i]"
            if (check.criterionId !in current.allowedChecks) result += problem(at, "${prefix}_CHECK_UNKNOWN", "This check does not name a current criterion or hard requirement")
            if (check.basisRefs.any { it !in current.allowedRefs }) result += problem(at, "${prefix}_BASIS_REFERENCE", "Review findings must cite existing sources in this subject's setting/dependency basis")
            if ((check.criterionId.startsWith("requirement/") || review.subjectId == BIBLE && check.criterionId.startsWith("node/")) && check.criterionId !in check.basisRefs)
                result += problem(at, "${prefix}_CHECK_BASIS", "This finding must cite the requirement or setting node it evaluates")
            val roots = current.roots[check.criterionId].orEmpty() + if (check.status == ReviewCheckStatus.BLOCKED) current.blockedRoots[check.criterionId].orEmpty() else emptyList()
            check.evidencePaths.forEach { pointer ->
                if (resolve(current.document, pointer) == null || roots.none { within(pointer, it) })
                    result += problem(at, "${prefix}_EVIDENCE_INVALID", "Evidence '$pointer' must exist in the current inputs and belong to this check's actual target; plans are not implementation evidence")
            }
        }
        return result
    }

    private fun contentDigest(session: WorkflowSession, brief: ModuleBrief, documents: Map<String, JsonObject>): String {
        val related = session.authoring?.let { closure(it, brief).briefs } ?: listOf(brief)
        val selected = related.flatMap { it.targets }.mapNotNull { module(it.kind) }.distinct().sorted()
        val values = selected.associateWith { id ->
            val raw = documents[id]
            // Freeze adds the drawing-capable library envelope; target blueprint schemas and drawing
            // identities remain in the digest. This transport envelope is independently Core-validated.
            if (id == "structures" && raw != null) JsonObject(raw.filterKeys { it != "schemaVersion" }) else raw ?: JsonNull
        }
        val referencedAssets = values.values.flatMap(::textureIds).toSet()
        return digest(buildJsonObject {
            put("contract", "authoring-implementation-v1")
            put("modules", JsonObject(values))
            put("assets", buildJsonObject { referencedAssets.sorted().forEach { id -> put(id, session.contentAssets[id]?.sha256 ?: "<missing>") } })
        })
    }

    /** Normalize typed defaults so a draft and its unchanged frozen pack receive the same digest. */
    private fun modules(session: WorkflowSession): Map<String, JsonObject> = buildMap {
        session.contentModules.forEach { (id, raw) ->
            if (id != "structures") put(id, runCatching { when (id) {
                "theme" -> McpJson.encode(McpJson.decode<WorldTheme>(raw))
                "terrain" -> McpJson.encode(McpJson.decode<TerrainPlan>(raw))
                "biomes" -> McpJson.encode(McpJson.decode<BiomePlan>(raw))
                "features" -> McpJson.encode(McpJson.decode<FeatureLibrary>(raw))
                "blocks" -> McpJson.encode(McpJson.decode<CustomBlockLibrary>(raw))
                "creatures" -> McpJson.encode(McpJson.decode<CreatureLibrary>(raw))
                "items" -> McpJson.encode(McpJson.decode<CustomItemLibrary>(raw))
                "quests" -> McpJson.encode(McpJson.decode<QuestLibrary>(raw))
                "mechanics" -> McpJson.encode(McpJson.decode<WorldMechanicLibrary>(raw))
                else -> raw
            }.jsonObject }.getOrDefault(raw))
        }
        val structures = session.contentModules["structures"]?.let { raw -> runCatching { McpJson.decode<StructureLibrary>(raw) }.getOrNull() } ?: session.structureLibrary()
        // Drawing variant IDs are content-addressed geometry+metadata identities. Existing Core pack
        // checks verify their archived bytes. Session ownership/source bookkeeping is not creative content.
        put("structures", McpJson.encode(structures.copy(artifacts = emptyMap(), sources = emptyMap())).jsonObject)
    }

    private fun evidenceDocument(session: WorkflowSession, documents: Map<String, JsonObject>) = buildJsonObject {
        put("modules", JsonObject(documents))
        put("assets", buildJsonObject { session.contentAssets.toSortedMap().forEach { (id, asset) -> put(id, buildJsonObject { put("sha256", asset.sha256) }) } })
    }

    private fun module(kind: String): String? = when (kind) {
        "terrain", "anchor" -> "terrain"
        "biome" -> "biomes"
        "feature" -> "features"
        "structure", "blueprint", "drawing" -> "structures"
        "block", "block_item" -> "blocks"
        "creature" -> "creatures"
        "item" -> "items"
        "quest" -> "quests"
        "mechanic" -> "mechanics"
        "theme", "narrative_beat" -> "theme"
        else -> null
    }

    /** Nested anchors/beats/blueprints remain inside their owner's reviewed definition and digest. */
    private fun actualTargets(documents: Map<String, JsonObject>): Set<ContentKey> = buildSet {
        if ("terrain" in documents) add(ContentKey("terrain", "main"))
        documents["theme"]?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { add(ContentKey("theme", it)) }
        mapOf("biomes" to "biome", "features" to "feature", "blocks" to "block", "creatures" to "creature", "items" to "item", "quests" to "quest", "mechanics" to "mechanic", "structures" to "structure").forEach { (module, kind) ->
            (documents[module]?.get(module) as? JsonArray).orEmpty().forEach { value ->
                ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.let { add(ContentKey(kind, it)) }
            }
        }
    }

    private fun targetRoots(target: ContentKey, documents: Map<String, JsonObject>): List<String> {
        val module = module(target.kind) ?: return emptyList()
        val document = documents[module] ?: return emptyList()
        val base = "/modules/$module"
        fun entries(array: JsonElement?, path: String, id: String = target.id): List<String> = (array as? JsonArray).orEmpty().mapIndexedNotNull { i, value ->
            if (((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == id) "$path/$i" else null
        }
        return when (target.kind) {
            "terrain" -> if (target.id == "main") listOf(base) else emptyList()
            "theme" -> if ((document["id"] as? JsonPrimitive)?.contentOrNull == target.id) listOf(base) else emptyList()
            "anchor" -> entries((document["shape"] as? JsonObject)?.get("anchors"), "$base/shape/anchors")
            "narrative_beat" -> entries(document["beats"], "$base/beats")
            "blueprint" -> {
                val structure = target.id.substringBefore('/'); val blueprint = target.id.substringAfter('/', "")
                val root = entries(document["structures"], "$base/structures", structure).singleOrNull() ?: return emptyList()
                val owner = (document["structures"] as? JsonArray)?.get(root.substringAfterLast('/').toInt()) as? JsonObject ?: return emptyList()
                buildList {
                    if (((owner["blueprint"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == blueprint) add("$root/blueprint")
                    ((owner["assembly"] as? JsonObject)?.get("pieces") as? JsonObject)?.forEach { (name, value) ->
                        if (((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == blueprint) add("$root/assembly/pieces/${escape(name)}")
                    }
                }
            }
            "drawing" -> buildList {
                fun visit(value: JsonElement, path: String) { when (value) {
                    is JsonObject -> value.forEach { (name, child) -> visit(child, "$path/${escape(name)}") }
                    is JsonArray -> value.forEachIndexed { i, child ->
                        if (path.endsWith("/drawing/variants") && child is JsonPrimitive && child.contentOrNull == target.id) add("$path/$i")
                        else visit(child, "$path/$i")
                    }
                    else -> Unit
                } }
                visit(document, base)
            }
            else -> entries(document[module], "$base/$module")
        }
    }

    private fun fallbackRoot(target: ContentKey, document: JsonObject): String {
        val id = module(target.kind) ?: return "/modules"
        val candidates = listOf("/modules/$id/$id", "/modules/$id", "/modules")
        return candidates.first { resolve(document, it) != null }
    }

    private fun textureIds(value: JsonElement?): Set<String> = buildSet {
        fun visit(node: JsonElement?) { when (node) {
            is JsonObject -> node.forEach { (name, child) ->
                if (name in setOf("texture", "textureAsset") && child is JsonPrimitive && child.isString && WorldContentRegistry.SHA256.matches(child.content)) add(child.content)
                visit(child)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        } }
        visit(value)
    }

    private fun resolve(document: JsonElement, pointer: String): JsonElement? {
        if (!pointer.startsWith('/') || pointer.length > 512) return null
        var value = document
        for (encoded in pointer.substring(1).split('/')) {
            if (Regex("~(?![01])").containsMatchIn(encoded)) return null
            val part = encoded.replace("~1", "/").replace("~0", "~")
            value = when (val current = value) {
                is JsonObject -> current[part]
                is JsonArray -> part.takeIf { it == "0" || it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return value
    }

    private fun enabled(session: WorkflowSession) = session.mode == WorkflowMode.COMPLETE_WORLD && session.authoring != null
    private fun within(path: String, root: String) = path == root || path.startsWith("$root/")
    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")
    private fun key(value: ContentKey) = "${value.kind}/${value.id}"
    private fun briefPath(id: String) = "authoring.briefs.$id"
    private fun problem(path: String, code: String, message: String) = Diagnostic(path, code, DiagnosticSeverity.ERROR, message)
    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun inputDigest(context: ReviewContext) = digest(buildJsonObject { put("basisDigest", context.basis); put("contentDigest", context.content) })
    private fun digest(value: JsonElement): String = digestBytes(canonical(value).toString().toByteArray(Charsets.UTF_8))
    private fun digestBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
