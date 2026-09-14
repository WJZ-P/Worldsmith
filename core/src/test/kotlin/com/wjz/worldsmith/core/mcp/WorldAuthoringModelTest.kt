package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldAuthoringModelTest {
    private val prompt = "Build a quiet coastal world without a mandatory boss."
    private val item = ContentKey("item", "salt_compass")
    private val region = WorldBibleNode("coast", WorldBibleNodeKind.REGION, "Salt coast", "Salt-bearing tides supply the coastal workshops.", listOf("tides"))
    private val rule = WorldBibleNode("tides", WorldBibleNodeKind.RULE, "Returning tide", "Each low tide exposes salt deposits.")
    private val inland = WorldBibleNode("inland", WorldBibleNodeKind.REGION, "Quiet inland", "Pine woods shelter inland supply caches.")

    private fun bible() = WorldBible(
        title = "The Returning Coast", premise = "Communities survive between the tides.", playerRole = "A coastal cartographer",
        mainConflict = "Inland and coastal settlements need dependable supplies.",
        requirements = listOf(WorldRequirement("quiet", "Preserve a quiet exploration baseline.", "quiet coastal world"),
            WorldRequirement("no_boss", "Do not require a Boss encounter.", "without a mandatory boss")),
        assumptions = listOf("The coast has seasonal settlements."), nodes = listOf(rule, region, inland),
    )

    private fun brief(id: String = "coast_items", target: ContentKey = item) = ModuleBrief(
        id = id, targets = listOf(target), purpose = "Provide a useful navigation reward for coastal exploration.",
        basisRefs = listOf("requirement/quiet", "node/coast"),
        criteria = listOf(BriefCriterion("useful_reward", "The item is available from an early coastal producer.", target)),
    )

    private fun review(status: ReviewCheckStatus = ReviewCheckStatus.PASS) = AuthoringReview(
        id = "coast_review", subjectId = "brief/coast_items", basisDigest = "a".repeat(64), contentDigest = "b".repeat(64),
        summary = "The coastal reward matches the documented supply chain.",
        checks = listOf(ReviewCheck("useful_reward", listOf("node/coast"), "The coastal producer supplies the reward.",
            listOf("/modules/items/items/0"), "The saved item is connected to the coastal supply source.", status)),
    )

    @Test fun `valid model serializes without adding runtime content or user approval`() {
        val bible = bible()
        val current = WorldAuthoringState(bible = bible, bibleRevision = 2, bibleReview = review(),
            briefs = listOf(BriefRecord(brief(), 2, WorldAuthoringModel.basisDigest(bible, brief().basisRefs))))
        assertTrue(WorldAuthoringModel.validateBible(bible, prompt).isEmpty())
        assertTrue(WorldAuthoringModel.validateBriefs(listOf(brief()), bible).isEmpty())
        assertTrue(WorldAuthoringModel.validateReview(review()).isEmpty())
        assertEquals(current, McpJson.decode<WorldAuthoringState>(McpJson.encode(current)))
        assertEquals(AuthoringReviewSource.AUTHORING_AI, current.bibleReview!!.source)
        assertFalse(bible.requiresBoss)
    }

    @Test fun `requirements quote actual input and inferred choices stay separate`() {
        val invalid = bible().copy(requirements = listOf(WorldRequirement("quiet", "Require a final combat encounter.", "mandatory final combat")))
        val errors = WorldAuthoringModel.validateBible(invalid, prompt)
        assertTrue(errors.any { it.code == "BIBLE_REQUIREMENT_QUOTE" && it.path.endsWith("promptQuote") })
        assertTrue(WorldAuthoringModel.validateBible(bible().copy(requirements = emptyList()), prompt).any { it.code == "BIBLE_REQUIREMENTS_LIMIT" })
        assertTrue(WorldAuthoringModel.validateBible(bible().copy(requirements = listOf(WorldRequirement("quiet", "Silence", " "))), prompt)
            .any { it.code == "BIBLE_REQUIREMENT_QUOTE" })
    }

    @Test fun `node dependencies reject missing IDs duplicates self links and cycles`() {
        val invalid = bible().copy(nodes = listOf(rule.copy(dependsOn = listOf("coast")), region,
            inland.copy(dependsOn = listOf("inland", "missing", "missing")), inland))
        val codes = WorldAuthoringModel.validateBible(invalid, prompt).map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("BIBLE_NODE_DUPLICATE", "BIBLE_NODE_DEPENDENCY", "BIBLE_NODE_CYCLE")))
    }

    @Test fun `text and collection bounds return diagnostics`() {
        val invalid = bible().copy(title = "x".repeat(161), premise = " ", assumptions = List(65) { "Assumption $it" },
            nodes = List(WorldAuthoringModel.MAX_NODES + 1) { region.copy(id = "region_$it", dependsOn = emptyList()) },
            openDecisions = listOf("x".repeat(2049)))
        val codes = WorldAuthoringModel.validateBible(invalid, prompt).map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("BIBLE_TEXT_INVALID", "BIBLE_ASSUMPTIONS_INVALID", "BIBLE_NODES_LIMIT", "BIBLE_DECISIONS_INVALID")))
    }

    @Test fun `digests are canonical across order and contain explicit fact versus belief distinctions`() {
        val original = bible()
        val reordered = original.copy(nodes = original.nodes.reversed(), requirements = original.requirements.reversed())
        assertEquals(WorldAuthoringModel.bibleDigest(original), WorldAuthoringModel.bibleDigest(reordered))
        assertEquals(WorldAuthoringModel.basisDigest(original, listOf("node/coast", "requirement/quiet")),
            WorldAuthoringModel.basisDigest(reordered, listOf("requirement/quiet", "node/coast")))
        val belief = original.copy(nodes = original.nodes.map { if (it.id == "coast") it.copy(truth = WorldBibleTruth.BELIEF) else it })
        assertNotEquals(WorldAuthoringModel.bibleDigest(original), WorldAuthoringModel.bibleDigest(belief))
        assertTrue(WorldAuthoringModel.bibleDigest(original).matches(Regex("[a-f0-9]{64}")))
        assertEquals("false", WorldAuthoringModel.references(original)["world/requires_boss"])
        assertTrue(WorldAuthoringModel.references(original).getValue("node/coast").contains("FACT"))
    }

    @Test fun `local revisions invalidate related bases but not unrelated regions`() {
        val original = bible()
        val changed = original.copy(nodes = original.nodes.map { if (it.id == "coast") it.copy(description = "The coast is a salt marsh.") else it })
        assertEquals(WorldAuthoringModel.globalDigest(original), WorldAuthoringModel.globalDigest(changed))
        assertNotEquals(WorldAuthoringModel.basisDigest(original, listOf("node/coast")), WorldAuthoringModel.basisDigest(changed, listOf("node/coast")))
        assertEquals(WorldAuthoringModel.basisDigest(original, listOf("node/inland")), WorldAuthoringModel.basisDigest(changed, listOf("node/inland")))
    }

    @Test fun `global rules and prompt requirements invalidate every brief even without explicit refs`() {
        val original = bible()
        val changedRule = original.copy(nodes = original.nodes.map { if (it.id == "tides") it.copy(description = "The low tide now exposes copper.") else it })
        val changedRequirement = original.copy(requirements = original.requirements.map { it.copy(text = it.text + " Always.") })
        val changedBoss = original.copy(requiresBoss = true)
        for (changed in listOf(changedRule, changedRequirement, changedBoss)) {
            assertNotEquals(WorldAuthoringModel.globalDigest(original), WorldAuthoringModel.globalDigest(changed))
            assertNotEquals(WorldAuthoringModel.basisDigest(original, listOf("node/inland")), WorldAuthoringModel.basisDigest(changed, listOf("node/inland")))
        }
    }

    @Test fun `dependency changes propagate and removed refs become stale without a digest crash`() {
        val history = WorldBibleNode("history", WorldBibleNodeKind.HISTORY, "Old harbor", "The harbor predates the present settlements.")
        val original = bible().copy(nodes = listOf(rule, region.copy(dependsOn = listOf("history")), history, inland))
        val changed = original.copy(nodes = original.nodes.map { if (it.id == "history") it.copy(description = "The harbor was built after the first settlement.") else it })
        assertNotEquals(WorldAuthoringModel.basisDigest(original, listOf("node/coast")), WorldAuthoringModel.basisDigest(changed, listOf("node/coast")))
        assertEquals(WorldAuthoringModel.basisDigest(original, listOf("node/inland")), WorldAuthoringModel.basisDigest(changed, listOf("node/inland")))
        val removed = original.copy(nodes = original.nodes.filterNot { it.id == "coast" })
        assertNotEquals(WorldAuthoringModel.basisDigest(original, listOf("node/coast")), WorldAuthoringModel.basisDigest(removed, listOf("node/coast")))
        assertTrue(WorldAuthoringModel.validateBriefs(listOf(brief()), removed).any { it.code == "BRIEF_BASIS_INVALID" })
    }

    @Test fun `a rule dependency participates in the global digest`() {
        val original = bible().copy(nodes = listOf(rule.copy(dependsOn = listOf("inland")), region, inland))
        val changed = original.copy(nodes = original.nodes.map { if (it.id == "inland") it.copy(description = "The hills cause the tidal rain.") else it })
        assertNotEquals(WorldAuthoringModel.globalDigest(original), WorldAuthoringModel.globalDigest(changed))
    }

    @Test fun `brief criteria own targets and dependencies form a DAG`() {
        val first = brief().copy(dependencies = listOf("other"))
        val second = brief("other").copy(dependencies = listOf(first.id), basisRefs = listOf("node/missing"),
            criteria = listOf(BriefCriterion("wrong", "The object belongs elsewhere.", ContentKey("item", "other_item"))))
        val codes = WorldAuthoringModel.validateBriefs(listOf(first, second), bible()).map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("BRIEF_TARGET_OWNER_DUPLICATE", "BRIEF_BASIS_INVALID", "BRIEF_CRITERION_INVALID", "BRIEF_TARGET_CRITERION_MISSING", "BRIEF_DEPENDENCY_CYCLE")))
        assertTrue(WorldAuthoringModel.validateBriefs(emptyList(), bible()).any { it.code == "BRIEFS_LIMIT" })
    }

    @Test fun `both review outcomes require actual authored conclusion and evidence fields`() {
        for (status in ReviewCheckStatus.entries) {
            val report = review(status)
            assertTrue(WorldAuthoringModel.validateReview(report).isEmpty())
            val invalid = report.copy(checks = report.checks.map { it.copy(conclusion = " ", evidencePaths = emptyList()) })
            val codes = WorldAuthoringModel.validateReview(invalid).map { it.code }.toSet()
            assertTrue(codes.containsAll(setOf("AUTHORING_REVIEW_FINDING", "AUTHORING_REVIEW_EVIDENCE")))
        }
    }

    @Test fun `review shape rejects invalid digests duplicate checks and empty summaries`() {
        val report = review()
        val invalid = report.copy(contractVersion = 2, basisDigest = "unbound", summary = "", checks = report.checks + report.checks)
        val codes = WorldAuthoringModel.validateReview(invalid).map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("AUTHORING_REVIEW_VERSION", "AUTHORING_REVIEW_DIGEST", "AUTHORING_REVIEW_SUMMARY", "AUTHORING_REVIEW_CHECKS")))
    }

    @Test fun `shape checks do not pretend evidence paths or source refs prove semantic correctness`() {
        val report = review().copy(checks = review().checks.map { it.copy(
            basisRefs = listOf("node/not_checked_here"), evidencePaths = listOf("/modules/not_present"),
            conclusion = "This unverified assertion must still be checked by the session policy.",
        ) })
        // Resolution, coverage and source freshness belong to the policy, not this shape-only result.
        assertTrue(WorldAuthoringModel.validateReview(report).isEmpty())
    }

    @Test fun `markdown is a deterministic projection and escapes embedded markup`() {
        val source = bible().copy(title = "Coast <script>", premise = "A [link](https://invalid.example) and **claim**.",
            nodes = listOf(region.copy(truth = WorldBibleTruth.LEGEND)))
        val rendered = WorldAuthoringModel.markdown(source)
        assertEquals(rendered, WorldAuthoringModel.markdown(source))
        assertTrue(rendered.contains("&lt;script&gt;"))
        assertFalse(rendered.contains("<script>"))
        assertTrue(rendered.contains("\\[link\\]"))
        assertTrue(rendered.contains("REGION · LEGEND"))
        assertTrue(rendered.contains("without a mandatory boss"))
        assertTrue(rendered.contains("not installed gameplay or user approval"))
    }

    @Test fun `state snapshot deeply freezes all nested collection owners`() {
        val requirements = bible().requirements.toMutableList()
        val assumptions = bible().assumptions.toMutableList()
        val nodeDependencies = mutableListOf("tides")
        val nodes = mutableListOf(rule, region.copy(dependsOn = nodeDependencies))
        val decisions = mutableListOf("Confirm the harbor's material palette.")
        val basis = mutableListOf("node/coast")
        val targets = mutableListOf(item)
        val criteria = brief().criteria.toMutableList()
        val briefDependencies = mutableListOf("supplies")
        val evidence = mutableListOf("/modules/items/items/0")
        val checks = mutableListOf(review().checks.single().copy(basisRefs = basis, evidencePaths = evidence))
        val records = mutableListOf(BriefRecord(brief().copy(targets = targets, basisRefs = basis, criteria = criteria,
            dependencies = briefDependencies, openDecisions = decisions), 1, "a".repeat(64)))
        val reports = mutableListOf(review().copy(checks = checks))
        val attempts = mutableListOf(ReviewAttempt("brief/coast_items", "a".repeat(64), "b".repeat(64), 1))
        val frozen = WorldAuthoringModel.freezeState(WorldAuthoringState(
            bible = bible().copy(requirements = requirements, assumptions = assumptions, nodes = nodes, openDecisions = decisions),
            bibleRevision = 1, bibleReview = review().copy(checks = checks), briefs = records, alignmentReviews = reports, reviewAttempts = attempts,
        ))
        requirements.clear(); assumptions.clear(); nodeDependencies.clear(); nodes.clear(); decisions.clear()
        basis.clear(); targets.clear(); criteria.clear(); briefDependencies.clear(); evidence.clear(); checks.clear(); records.clear(); reports.clear(); attempts.clear()
        assertEquals(2, frozen.bible!!.requirements.size)
        assertEquals(1, frozen.bible.assumptions.size)
        assertEquals(listOf("tides"), frozen.bible.nodes.last().dependsOn)
        assertEquals(1, frozen.bible.openDecisions.size)
        assertEquals(listOf(item), frozen.briefs.single().brief.targets)
        assertEquals(1, frozen.briefs.single().brief.criteria.size)
        assertEquals(listOf("supplies"), frozen.briefs.single().brief.dependencies)
        assertEquals(listOf("node/coast"), frozen.briefs.single().brief.basisRefs)
        assertEquals(1, frozen.briefs.single().brief.openDecisions.size)
        assertEquals(listOf("/modules/items/items/0"), frozen.bibleReview!!.checks.single().evidencePaths)
        assertEquals(listOf("node/coast"), frozen.alignmentReviews.single().checks.single().basisRefs)
        assertEquals(1, frozen.reviewAttempts.size)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.bible.nodes.last().dependsOn as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.briefs as MutableList<BriefRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.bibleReview.checks.single().evidencePaths as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (WorldAuthoringModel.references(bible()) as MutableMap<String, String>)["node/added"] = "changed" }
    }
}
