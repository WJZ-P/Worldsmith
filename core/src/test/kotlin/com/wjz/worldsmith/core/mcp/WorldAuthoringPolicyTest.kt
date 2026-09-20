package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** In-memory authoring-policy fixtures, not a fully Core-validated pack or a played-world claim. */
internal object WorldAuthoringPolicyFixtures {
    const val prompt = "A quiet world of living stone"
    val textureBytes = byteArrayOf(1, 2, 3)
    val textureId = MessageDigest.getInstance("SHA-256").digest(textureBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun bible() = WorldBible("Living stone", "Stone carries old memories", "A patient keeper", "Recover the lost memories",
        requirements = listOf(WorldRequirement("quiet", "The world is quiet", "quiet world")),
        nodes = listOf(WorldBibleNode("shore", WorldBibleNodeKind.REGION, "Shore", "The shore holds memory stones"),
            WorldBibleNode("inland", WorldBibleNodeKind.REGION, "Inland", "The inland forests are undisturbed")))

    fun pack(): WorldsmithPack {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val hall = WorldStructureDefinition("hall", StructureBlueprint(id = "hall", palette = mapOf("stone" to BuildMaterial("minecraft:stone")),
            build = listOf(BuildOperation.SetBlock("floor", BuildPos(0, 0, 0), "stone"))), StructurePlacement(listOf(base.biomes.biomes.first().id)))
        val keeper = CreatureDefinition("keeper", "Keeper", CreatureCategory.PASSIVE,
            CreatureModel(textureId, 16, 16, listOf(CreatureBone("body", cubes = listOf(CreatureCube(CreatureVector(), CreatureVector(1f, 1f, 1f)))))))
        return base.copy(manifest = base.manifest.copy(displayName = bible().title), theme = base.theme.copy(title = bible().title),
            structures = StructureLibrary(structures = listOf(hall)),
            blocks = CustomBlockLibrary(blocks = listOf(CustomBlockDefinition("stone", "Living stone", appearance = com.wjz.worldsmith.core.content.BlockAppearance.uniform(textureId)))),
            creatures = CreatureLibrary(creatures = listOf(keeper)),
            items = CustomItemLibrary(items = listOf(CustomItemDefinition("token", "Memory token", textureId))),
            quests = QuestLibrary(quests = listOf(Quest("return", "Remember", "Return the remembered branch",
                objectives = listOf(QuestObjective.DeliverItem("minecraft:stick")), themeBeat = base.theme.beats.first().id))),
            assets = mapOf(textureId to textureBytes))
    }

    fun session(): WorkflowSession {
        val pack = pack()
        val groups = linkedMapOf(
            "biomes" to pack.biomes.biomes.map { ContentKey("biome", it.id) },
            "structures" to listOf(ContentKey("structure", "hall")),
            "blocks" to listOf(ContentKey("block", "stone")),
            "creatures" to listOf(ContentKey("creature", "keeper")),
            "items" to listOf(ContentKey("item", "token")),
            "quests" to listOf(ContentKey("quest", "return")),
            "terrain" to listOf(ContentKey("terrain", "main")),
            "theme" to listOf(ContentKey("theme", pack.theme.id)),
            "features" to pack.features.features.map { ContentKey("feature", it.id) },
        ).filterValues { it.isNotEmpty() }
        val targets = groups.values.flatten()
        val plan = WorldDesignPlan(goal = prompt, targets = targets.map { DesignTarget(it, it.id, "A concrete role in this quiet world") },
            links = targets.map { DesignLink(ContentKey("theme", pack.theme.id), it, DesignRelation.THEME_ANCHOR) })
        val briefs = groups.map { (id, owned) -> ModuleBrief(id, owned, "Implement the $id of this quiet world",
            basisRefs = listOf("node/shore") + if (id == "items") listOf("requirement/quiet") else emptyList(),
            criteria = owned.mapIndexed { i, target -> BriefCriterion("check_$i", "This target expresses its assigned role", target) }) }
        val initial = WorldAuthoringState(bible = bible(), bibleRevision = 1,
            briefs = briefs.map { BriefRecord(it, 1, "0".repeat(64)) })
        val authoring = initial.copy(briefs = briefs.map { BriefRecord(it, 1, WorldAuthoringPolicy.basisDigest(initial, it)) })
        return WorkflowSession("1".repeat(32), prompt, mode = WorkflowMode.COMPLETE_WORLD, designPlan = plan, authoring = authoring,
            contentModules = ExistingWorldContentModules.input(pack).modules - "structures",
            structures = pack.structures.structures.associateBy { it.id },
            contentAssets = mapOf(textureId to ContentAsset(textureId, textureId, "image/png", textureBytes.size.toLong())))
    }

    fun review(session: WorkflowSession, subjectId: String, status: ReviewCheckStatus = ReviewCheckStatus.PASS, id: String = "review"): AuthoringReview {
        val context = WorldAuthoringPolicy.reviewContext(session, subjectId)
        val roots = context.getValue("evidenceRoots").jsonObject
        val blocked = context.getValue("blockedEvidenceRoots").jsonObject
        val allowed = context.getValue("allowedBasisRefs").jsonArray.map { it.jsonPrimitive.content }
        val checks = context.getValue("requiredCheckIds").jsonArray.map { value ->
            val criterion = value.jsonPrimitive.content
            val paths = roots[criterion]?.jsonArray.orEmpty()
            val fallback = blocked[criterion]?.jsonArray.orEmpty()
            val evidence = (if (paths.isNotEmpty()) paths else fallback).firstOrNull()?.jsonPrimitive?.content ?: "/modules"
            val source = if (criterion.startsWith("requirement/") || subjectId == "world_bible" && criterion.startsWith("node/")) criterion else allowed.first()
            ReviewCheck(criterion, listOf(source), "Compare the assigned role with the cited current input", listOf(evidence),
                if (status == ReviewCheckStatus.PASS) "The fixture report finds the criterion expressed in the cited definition" else "The fixture report identifies a mismatch that needs revision", status)
        }
        return AuthoringReview(id, subjectId, context.getValue("expectedBasisDigest").jsonPrimitive.content,
            context.getValue("expectedContentDigest").jsonPrimitive.content, "A deterministic report fixture, not an independent semantic proof", checks)
    }

    fun approveBible(session: WorkflowSession): WorkflowSession = session.copy(authoring = requireNotNull(session.authoring).copy(bibleReview = review(session, "world_bible")))
    fun approveAll(session: WorkflowSession): WorkflowSession {
        val bibleReady = approveBible(session)
        return bibleReady.copy(authoring = requireNotNull(bibleReady.authoring).copy(
            alignmentReviews = requireNotNull(bibleReady.authoring).briefs.map { review(bibleReady, it.brief.id) }))
    }
}

class WorldAuthoringPolicyTest {
    private fun session() = WorldAuthoringPolicyFixtures.session()
    private fun ready() = WorldAuthoringPolicyFixtures.approveBible(session())
    private fun state(session: WorkflowSession) = requireNotNull(session.authoring)
    private fun brief(session: WorkflowSession, id: String = "items") = state(session).briefs.single { it.brief.id == id }.brief
    private fun review(session: WorkflowSession, subject: String = "items", status: ReviewCheckStatus = ReviewCheckStatus.PASS) = WorldAuthoringPolicyFixtures.review(session, subject, status)

    @Test fun `legacy and focused sessions have no new gates`() {
        val current = session()
        for (old in listOf(current.copy(authoring = null), current.copy(mode = WorkflowMode.WORLDGEN_ONLY), current.copy(mode = WorkflowMode.STANDALONE))) {
            assertTrue(WorldAuthoringPolicy.bibleProblems(old).isEmpty())
            assertTrue(WorldAuthoringPolicy.productionProblems(old).isEmpty())
            assertTrue(WorldAuthoringPolicy.alignmentProblems(old).isEmpty())
            assertTrue(WorldAuthoringPolicy.publicationProblems(old, WorldAuthoringPolicyFixtures.pack()).isEmpty())
            assertFalse(WorldAuthoringPolicy.hasCurrentBibleReview(old))
        }
        val fresh = current.copy(authoring = WorldAuthoringState())
        assertEquals("WORLD_BIBLE_REQUIRED", WorldAuthoringPolicy.bibleProblems(fresh).single().code)
    }

    @Test fun `peaceful full scope passes production after current AI review without a Boss`() {
        val pending = session()
        assertTrue(WorldAuthoringPolicy.bibleProblems(pending).any { it.code == "WORLD_BIBLE_REVIEW_REQUIRED" })
        val current = WorldAuthoringPolicyFixtures.approveBible(pending)
        assertTrue(current.designPlan!!.bosses.isEmpty())
        assertTrue(WorldAuthoringPolicy.productionProblems(current).isEmpty(), WorldAuthoringPolicy.productionProblems(current).toString())
        assertTrue(WorldAuthoringPolicy.hasCurrentBibleReview(current))
        assertTrue(WorldAuthoringPolicy.alignmentProblems(current).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_REQUIRED" })
    }

    @Test fun `world review needs complete current findings and real setting evidence`() {
        val current = ready()
        val approved = state(current).bibleReview!!
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, approved).isEmpty())
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, approved.copy(checks = approved.checks.drop(1))).any { it.code.endsWith("REVIEW_INCOMPLETE") })
        val forged = approved.copy(checks = approved.checks.map { it.copy(evidencePaths = listOf("/bible/not_here")) })
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, forged).any { it.code.endsWith("EVIDENCE_INVALID") })
        val changed = current.copy(authoring = state(current).copy(bible = state(current).bible!!.copy(premise = "A different premise")))
        assertTrue(WorldAuthoringPolicy.bibleProblems(changed).any { it.code.endsWith("REVIEW_STALE") })
        val unresolved = current.copy(authoring = state(current).copy(bible = state(current).bible!!.copy(openDecisions = listOf("Choose the coast's cause"))))
        assertTrue(WorldAuthoringPolicy.bibleProblems(unresolved).any { it.code == "WORLD_BIBLE_OPEN_DECISIONS" })
    }

    @Test fun `local setting changes invalidate only direct and transitive brief dependencies`() {
        val bible = WorldAuthoringPolicyFixtures.bible()
        fun b(id: String, ref: String, dependencies: List<String> = emptyList()) = ModuleBrief(id, listOf(ContentKey("item", id)), "Implement $id", listOf(ref), dependencies,
            listOf(BriefCriterion("purpose", "Express its role", ContentKey("item", id))))
        val coast = b("coast", "node/shore")
        val inland = b("forest", "node/inland")
        val dependent = b("trade", "node/inland", listOf("coast"))
        val initial = WorldAuthoringState(bible = bible, bibleRevision = 1, briefs = listOf(coast, inland, dependent).map { BriefRecord(it, 1, "0".repeat(64)) })
        val changed = initial.copy(bible = bible.copy(nodes = bible.nodes.map { if (it.id == "shore") it.copy(description = "The shore is flooded") else it }), bibleRevision = 2)
        assertNotEquals(WorldAuthoringPolicy.basisDigest(initial, coast), WorldAuthoringPolicy.basisDigest(changed, coast))
        assertNotEquals(WorldAuthoringPolicy.basisDigest(initial, dependent), WorldAuthoringPolicy.basisDigest(changed, dependent))
        assertEquals(WorldAuthoringPolicy.basisDigest(initial, inland), WorldAuthoringPolicy.basisDigest(changed, inland))
        val global = initial.copy(bible = bible.copy(requirements = bible.requirements.map { it.copy(text = "A newly narrowed explicit requirement") }))
        assertNotEquals(WorldAuthoringPolicy.basisDigest(initial, inland), WorldAuthoringPolicy.basisDigest(global, inland))
    }

    @Test fun `brief cycles missing dependencies target ownership and stale bases are explicit gaps`() {
        val current = ready()
        val records = state(current).briefs
        val broken = records.map { if (it.brief.id == "items") it.copy(brief = it.brief.copy(dependencies = listOf("missing"))) else it }
        val missing = current.copy(authoring = state(current).copy(briefs = broken))
        assertTrue(WorldAuthoringPolicy.productionProblems(missing).any { it.code.contains("DEPENDENCY") })
        assertDoesNotThrow { WorldAuthoringPolicy.basisDigest(state(missing), brief(missing)) }
        val cyclic = current.copy(authoring = state(current).copy(briefs = records.map { when (it.brief.id) {
            "items" -> it.copy(brief = it.brief.copy(dependencies = listOf("creatures")))
            "creatures" -> it.copy(brief = it.brief.copy(dependencies = listOf("items")))
            else -> it
        } }))
        assertTrue(WorldAuthoringPolicy.productionProblems(cyclic).any { it.code.contains("DEPENDENCY_CYCLE") })
        assertDoesNotThrow { WorldAuthoringPolicy.basisDigest(state(cyclic), brief(cyclic)) }
        assertTrue(WorldAuthoringPolicy.productionProblems(current, setOf(ContentKey("item", "unplanned"))).any { it.code == "AUTHORING_BRIEF_TARGET_COVERAGE" })
        assertTrue(WorldAuthoringPolicy.productionProblems(current.copy(designPlan = null)).any { it.code == "AUTHORING_BRIEF_PLAN_REQUIRED" })
        val stale = current.copy(authoring = state(current).copy(briefs = records.map { if (it.brief.id == "items") it.copy(basisDigest = "f".repeat(64)) else it }))
        assertTrue(WorldAuthoringPolicy.productionProblems(stale).any { it.code == "AUTHORING_BRIEF_STALE" })
    }

    @Test fun `content digests normalize defaults and ignore unrelated uploads and modules`() {
        val current = ready()
        val before = WorldAuthoringPolicy.contentDigest(current, brief(current))
        val minimal = buildJsonObject { putJsonArray("items") { add(buildJsonObject {
            put("id", "token"); put("displayName", "Memory token"); put("textureAsset", WorldAuthoringPolicyFixtures.textureId)
        }) } }
        assertEquals(before, WorldAuthoringPolicy.contentDigest(current.copy(contentModules = current.contentModules + ("items" to minimal)), brief(current)))
        val unrelated = current.copy(contentAssets = current.contentAssets + ("f".repeat(64) to ContentAsset("f".repeat(64), "f".repeat(64), "image/png")),
            contentModules = current.contentModules + ("creatures" to McpJson.encode(CreatureLibrary()).jsonObject))
        assertEquals(before, WorldAuthoringPolicy.contentDigest(unrelated, brief(current)))
        val item = McpJson.decode<CustomItemLibrary>(current.contentModules.getValue("items"))
        val changed = current.copy(contentModules = current.contentModules + ("items" to McpJson.encode(item.copy(items = item.items.map { it.copy(displayName = "A changed role") })).jsonObject))
        assertNotEquals(before, WorldAuthoringPolicy.contentDigest(changed, brief(current)))
        val dependency = brief(current).copy(dependencies = listOf("creatures"))
        assertNotEquals(WorldAuthoringPolicy.contentDigest(current, dependency), WorldAuthoringPolicy.contentDigest(unrelated, dependency))
        val structure = brief(current, "structures")
        val envelope = current.copy(contentModules = current.contentModules + ("structures" to McpJson.encode(current.structureLibrary().copy(schemaVersion = 2)).jsonObject))
        assertEquals(WorldAuthoringPolicy.contentDigest(current, structure), WorldAuthoringPolicy.contentDigest(envelope, structure))
    }

    @Test fun `evidence must resolve under the criterion target rather than the plan or another entity`() {
        val current = ready()
        val approved = review(current)
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, approved).isEmpty())
        for (path in listOf("/designPlan/targets/0", "/modules/creatures/creatures/0", "/modules/items/items/9", "/modules/items/items/00", "/modules/items/items/0/not_here")) {
            val forged = approved.copy(checks = approved.checks.map { it.copy(evidencePaths = listOf(path)) })
            assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, forged).any { it.code.endsWith("EVIDENCE_INVALID") }, path)
        }
        val child = approved.copy(checks = approved.checks.map { it.copy(evidencePaths = listOf("/modules/items/items/0/displayName")) })
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, child).isEmpty())
    }

    @Test fun `blocked reports persist but neither permit publication nor prevent content repair`() {
        val current = WorldAuthoringPolicyFixtures.approveAll(session())
        val blocked = review(current, status = ReviewCheckStatus.BLOCKED)
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(current, blocked).isEmpty())
        val reported = current.copy(authoring = state(current).copy(alignmentReviews = state(current).alignmentReviews.filter { it.subjectId != "items" } + blocked))
        assertTrue(WorldAuthoringPolicy.alignmentProblems(reported).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_BLOCKED" })
        assertTrue(WorldAuthoringPolicy.productionProblems(reported, setOf(ContentKey("item", "token"))).isEmpty())
        val absent = current.copy(contentModules = current.contentModules + ("items" to McpJson.encode(CustomItemLibrary()).jsonObject))
        val absenceReport = review(absent, status = ReviewCheckStatus.BLOCKED)
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(absent, absenceReport).isEmpty())
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(absent, absenceReport.copy(checks = absenceReport.checks.map { it.copy(status = ReviewCheckStatus.PASS) })).any { it.code.endsWith("EVIDENCE_INVALID") })
    }

    @Test fun `publication overlays actual pack content and hard requirements need implementation evidence`() {
        val current = WorldAuthoringPolicyFixtures.approveAll(session())
        assertTrue(WorldAuthoringPolicy.alignmentProblems(current).isEmpty(), WorldAuthoringPolicy.alignmentProblems(current).toString())
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, WorldAuthoringPolicyFixtures.pack()).isEmpty())
        assertEquals(state(current).briefs.size, WorldAuthoringPolicy.currentReviewedBriefCount(current))
        val pack = WorldAuthoringPolicyFixtures.pack()
        val edited = pack.copy(items = pack.items.copy(items = pack.items.items.map { it.copy(displayName = "An inline replacement") }))
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, edited).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_STALE" })
        val unbriefed = pack.copy(items = pack.items.copy(items = pack.items.items + CustomItemDefinition("extra", "Extra", WorldAuthoringPolicyFixtures.textureId)))
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, unbriefed).any { it.code == "AUTHORING_BRIEF_TARGET_COVERAGE" })
        val withoutRequirement = current.copy(authoring = state(current).copy(alignmentReviews = state(current).alignmentReviews.map {
            it.copy(checks = it.checks.filterNot { check -> check.criterionId.startsWith("requirement/") })
        }))
        assertTrue(WorldAuthoringPolicy.alignmentProblems(withoutRequirement).any { it.code == "AUTHORING_ALIGNMENT_REQUIREMENT_UNCOVERED" })
    }

    @Test fun `the third same-input blocker stops retries and a changed input or passing report resets it`() {
        val current = ready()
        val blocked = review(current, status = ReviewCheckStatus.BLOCKED)
        val input = WorldAuthoringPolicy.reviewContext(current, "items").getValue("expectedInputDigest").jsonPrimitive.content
        fun attempts(count: Int) = current.copy(authoring = state(current).copy(alignmentReviews = listOf(blocked),
            reviewAttempts = listOf(ReviewAttempt("items", input, "a".repeat(64), count))))
        assertFalse(WorldAuthoringPolicy.repeatedBlocker(attempts(2), "items"))
        assertTrue(WorldAuthoringPolicy.repeatedBlocker(attempts(3), "items"))
        val old = attempts(3)
        val changed = old.copy(contentModules = old.contentModules + ("items" to McpJson.encode(CustomItemLibrary()).jsonObject))
        assertFalse(WorldAuthoringPolicy.repeatedBlocker(changed, "items"))
        val passed = old.copy(authoring = state(old).copy(alignmentReviews = listOf(review(old))))
        assertFalse(WorldAuthoringPolicy.repeatedBlocker(passed, "items"))
    }

    @Test fun `published and runtime titles follow the Bible while manifest description has no extra semantic receipt`() {
        val current = WorldAuthoringPolicyFixtures.approveAll(session())
        val pack = WorldAuthoringPolicyFixtures.pack()
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, pack).isEmpty())
        val wrongPackTitle = pack.copy(manifest = pack.manifest.copy(displayName = "An unrelated world"))
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, wrongPackTitle).any { it.code == "AUTHORING_ALIGNMENT_PACK_TITLE_MISMATCH" })
        val wrongTheme = pack.theme.copy(title = "An unrelated theme")
        val changedDraft = current.copy(contentModules = current.contentModules + ("theme" to McpJson.encode(wrongTheme).jsonObject))
        val issue = WorldAuthoringPolicy.alignmentProblems(changedDraft).single()
        assertEquals("AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH", issue.code)
        assertEquals("authoring.briefs.theme.title", issue.path)
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, pack.copy(theme = wrongTheme)).any { it.code == "AUTHORING_ALIGNMENT_THEME_TITLE_MISMATCH" })
        val displayOnly = pack.copy(manifest = pack.manifest.copy(description = "A display-only summary, not an additional reviewed module"))
        assertTrue(WorldAuthoringPolicy.publicationProblems(current, displayOnly).isEmpty())
        assertTrue(WorldAuthoringPolicy.publicationProblems(current.copy(authoring = null), wrongPackTitle).isEmpty())
    }
}
