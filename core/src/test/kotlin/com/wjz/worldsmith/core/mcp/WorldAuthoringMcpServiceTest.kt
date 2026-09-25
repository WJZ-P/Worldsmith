package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAsset
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.content.CustomItemDefinition
import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.drawhost.DrawingHost
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Real local handlers and durable stores; no worker execution, native activation or pack publication. */
class WorldAuthoringMcpServiceTest {
    @TempDir lateinit var root: Path

    private inner class Harness(label: String, existingId: String? = null) {
        val directory: Path = root.resolve(label)
        val sessions = WorkflowSessions(directory = directory.resolve("sessions"))
        val id = existingId ?: sessions.begin(WorldAuthoringPolicyFixtures.prompt, WorkflowMode.COMPLETE_WORLD).id
        val current: WorkflowSession get() = requireNotNull(sessions.find(id))
        val tools = (WorldAuthoringMcpService(sessions).tools() +
            WorldContentMcpService(ManagedPackStore(directory.resolve("packs")), false, sessions).tools())
            .map { WorldAuthoringFlow.guard(it, sessions) }.associateBy { it.name }

        fun call(name: String, payload: JsonObject = JsonObject(emptyMap()), revision: Long? = null): McpToolResult {
            val tool = tools.getValue(name)
            return tool.handler(buildJsonObject {
                put("sessionId", id)
                if ("expectedRevision" in tool.inputSchema.getValue("properties").jsonObject) put("expectedRevision", revision ?: current.revision)
                payload.forEach { (key, value) -> put(key, value) }
            })
        }

        fun saveBible(bible: WorldBible = WorldAuthoringPolicyFixtures.bible()) =
            call("worldsmith_put_world_bible", buildJsonObject { put("bible", McpJson.encode(bible)) })

        fun context(subject: String = "world_bible") = ok(call("worldsmith_get_authoring_review_context",
            buildJsonObject { put("subjectId", subject) })).structuredContent

        fun review(report: AuthoringReview) = if (report.subjectId == "world_bible")
            call("worldsmith_review_world_bible", buildJsonObject { put("review", McpJson.encode(report)) })
        else call("worldsmith_review_world_alignment", buildJsonObject { put("subjectId", report.subjectId); put("review", McpJson.encode(report)) })

        fun reviewedBible(bible: WorldBible = WorldAuthoringPolicyFixtures.bible()) {
            ok(saveBible(bible)); ok(review(report(context(), "bible_initial")))
            assertTrue(WorldAuthoringPolicy.hasCurrentBibleReview(current))
        }

        fun plan(plan: WorldDesignPlan = requireNotNull(WorldAuthoringPolicyFixtures.session().designPlan)) =
            call("worldsmith_put_world_design_plan", buildJsonObject { put("plan", McpJson.encode(plan)) })

        fun briefs(briefs: List<ModuleBrief>) = call("worldsmith_put_module_briefs", buildJsonObject { put("briefs", McpJson.encode(briefs)) })

        fun productionReady(withContent: Boolean = false): WorkflowSession {
            val fixture = WorldAuthoringPolicyFixtures.session()
            reviewedBible(requireNotNull(fixture.authoring?.bible)); ok(plan(requireNotNull(fixture.designPlan)))
            ok(briefs(requireNotNull(fixture.authoring).briefs.map { it.brief }))
            assertTrue(WorldAuthoringPolicy.productionProblems(current).isEmpty(), WorldAuthoringPolicy.productionProblems(current).toString())
            if (withContent) {
                // These are already-authored in-memory fixtures, not claims of generated/played instances.
                sessions.putContent(id, current.revision, fixture.contentModules.filterKeys { it != "structures" }, fixture.contentAssets)
                fixture.structures.values.forEach { sessions.putStructure(id, it) }
                fixture.architecture?.let { sessions.planArchitecture(id, it) }
            }
            return current
        }
    }

    private fun ok(result: McpToolResult): McpToolResult = result.also { assertFalse(it.isError, it.text) }
    private fun codes(result: McpToolResult): Set<String> = result.structuredContent["diagnostics"]?.jsonArray.orEmpty()
        .map { it.jsonObject.getValue("code").jsonPrimitive.content }.toSet()

    /** A supplied AI-report fixture: the handler validates this evidence, not its literary truth. */
    private fun report(context: JsonObject, id: String, status: ReviewCheckStatus = ReviewCheckStatus.PASS): AuthoringReview {
        assertTrue(context.getValue("ready").jsonPrimitive.boolean, context.toString())
        val allowed = context.getValue("allowedBasisRefs").jsonArray.map { it.jsonPrimitive.content }
        val roots = context.getValue("evidenceRoots").jsonObject
        val fallbacks = context.getValue("blockedEvidenceRoots").jsonObject
        return AuthoringReview(id, context.getValue("subjectId").jsonPrimitive.content,
            context.getValue("expectedBasisDigest").jsonPrimitive.content, context.getValue("expectedContentDigest").jsonPrimitive.content,
            "The report compares each required claim with the supplied current input fields.",
            context.getValue("requiredCheckIds").jsonArray.map { value ->
                val criterion = value.jsonPrimitive.content
                val available = roots[criterion]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.ifEmpty {
                    if (status == ReviewCheckStatus.BLOCKED) fallbacks[criterion]?.jsonArray.orEmpty().map { it.jsonPrimitive.content } else emptyList()
                }
                require(available.isNotEmpty()) { "Fixture has no $status evidence for $criterion" }
                val promptCheck = context.getValue("subjectId").jsonPrimitive.content == "world_bible" && criterion == "prompt_alignment"
                ReviewCheck(criterion, listOf(if (promptCheck) "world/original_prompt" else if (criterion in allowed) criterion else "world/premise"),
                    "The referenced current field implements the stated world-design criterion.", if (promptCheck) listOf("/originalPrompt", "/bible") else listOf(available.first()),
                    "The cited current input provides the evidence for this explicit test finding.", status)
            })
    }

    @Test fun `real begin handler enables authoring only for new complete worlds`() {
        val sessions = WorkflowSessions(directory = root.resolve("begin_sessions"))
        DrawingHost(root.resolve("begin_jobs")).use { host ->
            val begin = WorldsmithMcpTools(root.resolve("begin_packs"), sessions = sessions, drawings = host).all()
                .single { it.name == WorldsmithWorkflow.BEGIN_TOOL }
            for (mode in WorkflowMode.entries) {
                val result = ok(begin.handler(buildJsonObject {
                    put("prompt", WorldAuthoringPolicyFixtures.prompt); put("mode", mode.name); put("detail", "summary")
                }))
                val saved = requireNotNull(sessions.find(result.structuredContent.getValue("sessionId").jsonPrimitive.content))
                assertEquals(mode, saved.mode)
                if (mode == WorkflowMode.COMPLETE_WORLD) assertEquals(WorldAuthoringState(), saved.authoring) else assertNull(saved.authoring)
                assertFalse(saved.finished)
            }
        }
    }

    @Test fun `module brief schema accepts object documents while ID selections remain string arrays`() {
        val catalog = WorldAuthoringMcpService(WorkflowSessions()).tools().associateBy { it.name }
        fun property(tool: String, name: String) = catalog.getValue(tool).inputSchema.getValue("properties").jsonObject.getValue(name).jsonObject
        val briefs = property("worldsmith_put_module_briefs", "briefs")
        assertEquals("array", briefs.getValue("type").jsonPrimitive.content)
        assertEquals("object", briefs.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(WorldAuthoringModel.MAX_BRIEFS, briefs.getValue("maxItems").jsonPrimitive.int)
        for ((tool, field) in listOf("worldsmith_put_module_briefs" to "removeIds", "worldsmith_get_world_bible" to "nodeIds")) {
            val ids = property(tool, field)
            assertEquals("array", ids.getValue("type").jsonPrimitive.content)
            assertEquals("string", ids.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    private fun installLegacy(label: String): Pair<Harness, WorkflowSession> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/authoring/legacy-session.json")).use { it.readBytes() }
        val raw = WorldsmithJson.format.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertFalse("authoring" in raw, "This must be the old persisted shape, not a new DTO serialized with a null field")
        val legacy = WorldsmithJson.decode<WorkflowSession>(bytes.toString(Charsets.UTF_8))
        val directory = root.resolve(label).resolve("sessions")
        Files.createDirectories(directory); Files.write(directory.resolve("${legacy.id}.json"), bytes)
        return Harness(label, legacy.id) to legacy
    }

    @Test fun `old JSON restores without new gates and opt in preserves modules assets prompt and revision lineage`() {
        val (h, legacy) = installLegacy("legacy")
        assertNull(h.current.authoring); assertEquals(legacy, h.current)
        assertTrue(h.sessions.recoveryDiagnostics.isEmpty())
        assertTrue(WorldAuthoringFlow.issues(h.current).isEmpty())
        val upgraded = ok(h.call("worldsmith_upgrade_world_authoring"))
        assertEquals(legacy.revision + 1, h.current.revision)
        assertEquals(WorldAuthoringState(), h.current.authoring)
        assertEquals(legacy.prompt, h.current.prompt)
        assertEquals(legacy.contentModules, h.current.contentModules)
        assertEquals(legacy.contentAssets, h.current.contentAssets)
        assertFalse(upgraded.structuredContent.getValue("userApprovalClaimed").jsonPrimitive.boolean)
        val revision = h.current.revision
        ok(h.call("worldsmith_upgrade_world_authoring")); assertEquals(revision, h.current.revision)
        val restored = WorkflowSessions(directory = h.directory.resolve("sessions"))
        assertEquals(h.current, restored.find(h.id))
    }

    @Test fun `bible writes use durable CAS and exact repeat is idempotent across restart`() {
        val h = Harness("bible_cas")
        val originalPrompt = h.current.prompt
        ok(h.saveBible()); assertEquals(1L, h.current.revision); assertEquals(1L, h.current.authoring!!.bibleRevision)
        val first = h.current
        ok(h.saveBible()); assertEquals(first, h.current)
        val conflict = assertThrows(IllegalArgumentException::class.java) {
            h.call("worldsmith_put_world_bible", buildJsonObject { put("bible", McpJson.encode(WorldAuthoringPolicyFixtures.bible())) }, revision = 0)
        }
        assertTrue(conflict.message.orEmpty().contains("DRAFT_REVISION_CONFLICT")); assertEquals(first, h.current)
        val restored = WorkflowSessions(directory = h.directory.resolve("sessions"))
        assertEquals(first, restored.find(h.id))
        h.sessions.archive(h.id); assertNotNull(h.sessions.resume(h.id))
        assertEquals(first.authoring, h.current.authoring)
        assertEquals(originalPrompt, h.current.prompt)
        val markdown = ok(h.call("worldsmith_get_world_bible", buildJsonObject { put("format", "markdown") })).structuredContent
        assertEquals(WorldAuthoringModel.markdown(requireNotNull(first.authoring?.bible)), markdown.getValue("markdown").jsonPrimitive.content)
    }

    @Test fun `bible handler rejects invented prompt quotations without committing any draft`() {
        val h = Harness("quote")
        val bible = WorldAuthoringPolicyFixtures.bible().let { it.copy(requirements = it.requirements.map { r -> r.copy(promptQuote = "An invented user request for a mandatory Boss") }) }
        val before = h.current
        val result = h.saveBible(bible)
        assertTrue(result.isError)
        assertTrue("BIBLE_REQUIREMENT_QUOTE" in codes(result))
        assertEquals(before, h.current)
    }

    @Test fun `flow does not invoke plan or high cost delegates before their current authoring prerequisites`() {
        val h = Harness("guard")
        var calls = 0
        fun guarded(name: String) = WorldAuthoringFlow.guard(McpTool(name, "Probe", "Observe whether production was entered",
            JsonObject(emptyMap()), false, handler = { calls++; McpToolResult.success(buildJsonObject { put("entered", true) }) }), h.sessions)
        fun args() = buildJsonObject { put("sessionId", h.id); put("expectedRevision", h.current.revision) }
        val plan = guarded("worldsmith_put_world_design_plan")
        assertTrue(plan.handler(args()).isError); assertEquals(0, calls)
        ok(h.saveBible())
        assertTrue(plan.handler(args()).isError); assertEquals(0, calls)
        ok(h.review(report(h.context(), "bible_ready")))
        assertFalse(plan.handler(args()).isError); assertEquals(1, calls)
        for (name in listOf("worldsmith_build_drawing", "worldsmith_create_pixel_texture", "worldsmith_put_structure")) {
            assertTrue(guarded(name).handler(args()).isError, name)
        }
        assertEquals(1, calls)
        ok(h.plan())
        assertTrue(guarded("worldsmith_build_drawing").handler(args()).isError); assertEquals(1, calls)
        ok(h.briefs(requireNotNull(WorldAuthoringPolicyFixtures.session().authoring).briefs.map { it.brief }))
        assertTrue(guarded("worldsmith_build_drawing").handler(args()).isError); assertEquals(1, calls)
        val selected = JsonObject(args() + ("briefIds" to McpJson.encode(listOf("structures"))))
        assertFalse(guarded("worldsmith_build_drawing").handler(selected).isError); assertEquals(2, calls)
    }

    @Test fun `opaque production tools expose optional brief ID schema but enforce current selection only for new worlds`() {
        val modern = Harness("opaque_modern")
        modern.productionReady()
        val (legacy, _) = installLegacy("opaque_legacy")
        val selectedId = modern.current.authoring!!.briefs.first().brief.id
        val originalSchema = McpJson.schema(mapOf("sessionId" to McpJson.type("string"), "sourceRef" to McpJson.type("object")), listOf("sessionId"))
        for (name in listOf("worldsmith_build_drawing", "worldsmith_build_texture", "worldsmith_import_texture_file",
            "worldsmith_put_texture_asset", "worldsmith_create_pixel_texture")) {
            var calls = 0
            var received: JsonObject? = null
            val probe = McpTool(name, "Opaque probe", "Record entry without running a worker or producing an asset", originalSchema, false,
                handler = { args -> calls++; received = args; McpToolResult.success(buildJsonObject { put("entered", true) }) })
            val guarded = WorldAuthoringFlow.guard(probe, modern.sessions)
            val properties = guarded.inputSchema.getValue("properties").jsonObject
            val selectionSchema = properties.getValue("briefIds").jsonObject
            assertEquals("array", selectionSchema.getValue("type").jsonPrimitive.content, name)
            assertEquals("string", selectionSchema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content, name)
            assertEquals(WorldAuthoringModel.MAX_BRIEFS, selectionSchema.getValue("maxItems").jsonPrimitive.int, name)
            assertEquals(originalSchema.getValue("required"), guarded.inputSchema.getValue("required"), "Legacy calls retain their original required fields")
            assertEquals(originalSchema.getValue("properties").jsonObject.getValue("sourceRef"), properties.getValue("sourceRef"))
            assertFalse("briefIds" in probe.inputSchema.getValue("properties").jsonObject, "Guarding must not mutate the original tool schema")

            val legacyArgs = buildJsonObject { put("sessionId", legacy.id) }
            ok(WorldAuthoringFlow.guard(probe, legacy.sessions).handler(legacyArgs))
            assertEquals(1, calls); assertEquals(legacyArgs, received)
            assertFalse("briefIds" in requireNotNull(received))

            val modernArgs = buildJsonObject { put("sessionId", modern.id) }
            val before = modern.current
            val invalidSelections = listOf<JsonElement?>(null, McpJson.encode(emptyList<String>()), McpJson.encode(listOf("missing_brief")),
                McpJson.encode(listOf(selectedId, selectedId)), McpJson.encode(List(WorldAuthoringModel.MAX_BRIEFS + 1) { "extra_$it" }))
            for (selection in invalidSelections) {
                val args = if (selection == null) modernArgs else JsonObject(modernArgs + ("briefIds" to selection))
                val rejected = guarded.handler(args)
                assertTrue(rejected.isError, "$name accepted invalid brief selection: $selection")
                assertTrue("AUTHORING_BRIEF_SELECTION_REQUIRED" in codes(rejected), rejected.text)
                assertEquals(1, calls, "$name must not enter its delegate before binding a current brief")
                assertEquals(before, modern.current)
            }
            val selected = JsonObject(modernArgs + ("briefIds" to McpJson.encode(listOf(selectedId))))
            ok(guarded.handler(selected)); assertEquals(2, calls); assertEquals(modernArgs, received)
            assertFalse("briefIds" in requireNotNull(received), "The orchestration-only selection must not leak into a strict delegate DTO")
        }
    }

    @Test fun `reviewed drawing build selection is consumed before strict request decoding`() {
        val h = Harness("strict_drawing_delegate")
        h.productionReady()
        val selectedId = h.current.authoring!!.briefs.first().brief.id
        var decoded: com.wjz.worldsmith.core.drawhost.DrawingRequest? = null
        val probe = McpTool("worldsmith_build_drawing", "Strict drawing delegate", "Decode the same request type as the real drawing service",
            McpJson.schema(mapOf("sessionId" to McpJson.type("string")), listOf("sessionId")), false,
            handler = { args ->
                decoded = McpJson.decode<com.wjz.worldsmith.core.drawhost.DrawingRequest>(JsonObject(args - "sessionId"))
                McpToolResult.success(buildJsonObject { put("decoded", true) })
            })
        val guarded = WorldAuthoringFlow.guard(probe, h.sessions)
        val arguments = buildJsonObject {
            put("sessionId", h.id); put("briefIds", McpJson.encode(listOf(selectedId)))
            put("name", "hall"); put("requestId", "strict-drawing-check"); put("entryClass", "Hall")
            put("sources", buildJsonObject { put("Hall.java", "class Hall {}") })
            put("parameters", buildJsonObject { put("kind", "hall") })
        }
        ok(guarded.handler(arguments))
        assertEquals("Hall", decoded!!.entryClass)
        assertEquals(mapOf("kind" to "hall"), decoded!!.parameters)
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            guarded.handler(JsonObject(arguments + ("unexpectedWorkerField" to JsonPrimitive(true))))
        }
    }

    @Test fun `valid reviewed non Boss design passes real plan handler while old session retains Boss requirement`() {
        val modern = Harness("no_boss")
        modern.reviewedBible(); val plan = requireNotNull(WorldAuthoringPolicyFixtures.session().designPlan)
        assertTrue(plan.bosses.isEmpty()); assertFalse(requireNotNull(modern.current.authoring?.bible).requiresBoss)
        ok(modern.plan(plan)); assertEquals(plan, modern.current.designPlan)
        val (legacy, before) = installLegacy("legacy_boss")
        val rejected = legacy.plan(plan)
        assertTrue(rejected.isError); assertTrue("DESIGN_BOSS_MISSING" in codes(rejected))
        assertEquals(before, legacy.current)
    }

    @Test fun `review submission requires current input evidence and records AI rather than user approval`() {
        val h = Harness("evidence")
        ok(h.saveBible())
        val context = h.context()
        assertEquals("AUTHORING_AI", context.getValue("source").jsonPrimitive.content)
        assertFalse(context.getValue("semanticCorrectnessProven").jsonPrimitive.boolean)
        val valid = report(context, "current_review")
        val wrongEvidence = valid.copy(id = "bad_evidence", checks = valid.checks.map { it.copy(evidencePaths = listOf("/modules/items/items/0")) })
        val before = h.current
        val rejected = h.review(wrongEvidence)
        assertTrue(rejected.isError); assertTrue(codes(rejected).any { it.endsWith("EVIDENCE_INVALID") }); assertEquals(before, h.current)
        val forged = JsonObject(McpJson.encode(valid).jsonObject + ("source" to JsonPrimitive("USER")))
        assertThrows(IllegalArgumentException::class.java) { h.call("worldsmith_review_world_bible", buildJsonObject { put("review", forged) }) }
        assertEquals(before, h.current)
        val accepted = ok(h.review(valid))
        assertEquals(AuthoringReviewSource.AUTHORING_AI, h.current.authoring!!.bibleReview!!.source)
        assertFalse(accepted.structuredContent.getValue("userApprovalClaimed").jsonPrimitive.boolean)
        assertTrue(accepted.structuredContent.getValue("automaticAfterAiReview").jsonPrimitive.boolean)
        assertEquals("worldsmith_put_world_design_plan", accepted.structuredContent.getValue("nextTool").jsonPrimitive.content)
        val revision = h.current.revision
        ok(h.review(valid)); assertEquals(revision, h.current.revision)
    }

    @Test fun `stale world review is rejected even with the latest shared CAS revision`() {
        val h = Harness("stale_bible")
        ok(h.saveBible()); val stale = report(h.context(), "stale_report")
        ok(h.saveBible(WorldAuthoringPolicyFixtures.bible().copy(title = "A newly named living coast")))
        val current = h.current
        val rejected = h.review(stale)
        assertTrue(rejected.isError); assertTrue(codes(rejected).any { it.endsWith("REVIEW_STALE") })
        assertEquals(current, h.current)
    }

    @Test fun `three findings for the same blocked criterion pause even when the explanation is rephrased`() {
        val h = Harness("repeat")
        ok(h.saveBible())
        repeat(3) { index ->
            val current = report(h.context(), "blocked_$index")
            val blocked = current.copy(checks = current.checks.map { if (it.criterionId == "global")
                it.copy(status = ReviewCheckStatus.BLOCKED, conclusion = "Inspection ${index + 1}: the same global setting decision remains unresolved.") else it })
            val result = ok(h.review(blocked))
            assertEquals(index == 2, result.structuredContent.getValue("requiresUserAction").jsonPrimitive.boolean)
        }
        assertTrue(WorldAuthoringPolicy.repeatedBlocker(h.current, "world_bible"))
        assertTrue(WorldAuthoringFlow.issues(h.current).any { it.requiresUserAction })
        val resumed = ok(h.review(report(h.context(), "repaired_pass")))
        assertFalse(WorldAuthoringPolicy.repeatedBlocker(h.current, "world_bible"))
        assertFalse(resumed.structuredContent["requiresUserAction"]?.jsonPrimitive?.boolean ?: false)
        assertTrue(h.current.authoring!!.reviewAttempts.isEmpty())
    }

    @Test fun `changing the reviewed world clears a repeated blocker for the old inputs without deleting the report`() {
        val h = Harness("changed_blocker")
        ok(h.saveBible())
        repeat(3) { index -> ok(h.review(report(h.context(), "blocked_$index", ReviewCheckStatus.BLOCKED))) }
        assertTrue(WorldAuthoringPolicy.repeatedBlocker(h.current, "world_bible"))
        val oldReport = h.current.authoring!!.bibleReview
        val response = ok(h.saveBible(WorldAuthoringPolicyFixtures.bible().copy(mainConflict = "Settlements cooperate to restore their common stone supply.")))
        assertFalse(WorldAuthoringPolicy.repeatedBlocker(h.current, "world_bible"))
        assertFalse(response.structuredContent["requiresUserAction"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(oldReport, h.current.authoring!!.bibleReview)
    }

    @Test fun `changing the companion issue set does not reset an independently repeated blocked criterion`() {
        val h = Harness("mixed_blockers")
        ok(h.saveBible())
        val companions = listOf("node/shore", "node/inland", "requirement/quiet")
        companions.forEachIndexed { index, companion ->
            val current = report(h.context(), "mixed_report_$index")
            val mixed = current.copy(checks = current.checks.map { check ->
                if (check.criterionId == "global" || check.criterionId == companion) check.copy(
                    status = ReviewCheckStatus.BLOCKED,
                    conclusion = "Review ${index + 1} identifies the same criterion-specific mismatch alongside a different additional finding.",
                ) else check
            })
            val response = ok(h.review(mixed))
            assertEquals(index == 2, response.structuredContent.getValue("requiresUserAction").jsonPrimitive.boolean)
        }
        val attempts = h.current.authoring!!.reviewAttempts.filter { it.subjectId == "world_bible" }
        assertEquals(listOf(1, 3), attempts.map { it.count }.sorted(), "Track the persistent global issue separately from the newly observed companion")
        assertTrue(WorldAuthoringPolicy.repeatedBlocker(h.current, "world_bible"))
        assertTrue(WorldAuthoringFlow.issues(h.current).any { it.requiresUserAction })
    }

    @Test fun `alignment handler binds real module evidence and rejects it after those definitions change`() {
        val h = Harness("alignment")
        h.productionReady(withContent = true)
        val brief = h.current.authoring!!.briefs.first { record -> record.brief.targets.any { it.kind == "item" } }.brief
        val valid = report(h.context(brief.id), "alignment_current")
        ok(h.review(valid))
        assertEquals(valid, h.current.authoring!!.alignmentReviews.single { it.subjectId == brief.id })
        assertTrue(WorldAuthoringPolicy.currentReviewedBriefCount(h.current) >= 1)
        val items = McpJson.decode<CustomItemLibrary>(h.current.contentModules.getValue("items"))
        val changed = items.copy(items = items.items.map { it.copy(displayName = it.displayName + " revised") })
        h.sessions.putContent(h.id, h.current.revision, mapOf("items" to McpJson.encode(changed).jsonObject))
        val before = h.current
        val rejected = h.review(valid.copy(id = "alignment_outdated"))
        assertTrue(rejected.isError); assertTrue(codes(rejected).any { it.endsWith("REVIEW_STALE") }); assertEquals(before, h.current)
    }

    private fun assertBudgetRejected(h: Harness, block: () -> McpToolResult) {
        val before = h.current
        val outcome = runCatching(block)
        val message = outcome.exceptionOrNull()?.message ?: outcome.getOrThrow().text
        assertTrue(outcome.isFailure || outcome.getOrThrow().isError, "An oversized authoring document was accepted")
        assertTrue(listOf("budget", "exceeds", "KiB", "MiB").any { message.contains(it, ignoreCase = true) }, message)
        assertEquals(before, h.current, "Budget rejection must precede durable mutation")
    }

    @Test fun `MCP bible brief and review byte budgets reject oversized otherwise structured documents`() {
        val hugeBible = WorldAuthoringPolicyFixtures.bible().copy(nodes = List(128) { index ->
            WorldBibleNode("region_$index", WorldBibleNodeKind.REGION, "Region $index", "石".repeat(8192))
        })
        val bibleHarness = Harness("bible_budget")
        assertTrue(WorldAuthoringModel.validateBible(hugeBible, bibleHarness.current.prompt).isEmpty())
        assertBudgetRejected(bibleHarness) { bibleHarness.saveBible(hugeBible) }

        val briefHarness = Harness("brief_budget")
        briefHarness.reviewedBible(); ok(briefHarness.plan())
        val hugeBriefs = List(3) { index ->
            val target = ContentKey("item", "budget_item_$index")
            ModuleBrief("budget_$index", listOf(target), "Describe the item family's material role.", listOf("world/premise"),
                criteria = List(128) { criterion -> BriefCriterion("criterion_$criterion", "石".repeat(4096), target) })
        }
        assertTrue(WorldAuthoringModel.validateBriefs(hugeBriefs, requireNotNull(briefHarness.current.authoring?.bible)).isEmpty())
        assertBudgetRejected(briefHarness) { briefHarness.briefs(hugeBriefs) }

        val reviewHarness = Harness("review_budget")
        ok(reviewHarness.saveBible(hugeBible.copy(nodes = hugeBible.nodes.map { it.copy(description = "This region expresses a distinct living-stone habitat.") })))
        val hugeReview = report(reviewHarness.context(), "large_review").let { report -> report.copy(checks = report.checks.map {
            it.copy(claim = "石".repeat(4096), conclusion = "石".repeat(4096))
        }) }
        assertTrue(WorldAuthoringModel.validateReview(hugeReview).isEmpty())
        assertBudgetRejected(reviewHarness) { reviewHarness.review(hugeReview) }
    }

    @Test fun `aggregate authoring state is bounded even when every submitted report is individually under its limit`() {
        val h = Harness("state_budget")
        h.reviewedBible()
        val fixture = WorldAuthoringPolicyFixtures.session()
        val originalPlan = requireNotNull(fixture.designPlan)
        val originalItem = originalPlan.targets.first { it.key.kind == "item" }.key
        val itemKeys = listOf(originalItem) + (1..4).map { ContentKey("item", "budget_item_$it") }
        val extraTargets = itemKeys.drop(1).map { DesignTarget(it, "Stone token ${it.id}", "A distinct material witness for this review fixture.") }
        val plan = originalPlan.copy(targets = originalPlan.targets + extraTargets,
            links = originalPlan.links + extraTargets.map { DesignLink(ContentKey("theme", "main"), it.key, DesignRelation.THEME_ANCHOR) })
        ok(h.plan(plan))
        val otherBriefs = originalPlan.targets.filter { it.key != originalItem }.mapIndexed { index, target ->
            ModuleBrief("other_$index", listOf(target.key), target.purpose, listOf("world/premise"),
                criteria = listOf(BriefCriterion("present", "The actual definition expresses its planned purpose.", target.key)))
        }
        val wideBriefs = itemKeys.mapIndexed { index, key -> ModuleBrief("wide_$index", listOf(key),
            "Compare this actual item's fields with each stated setting criterion.", listOf("requirement/quiet", "world/premise"),
            criteria = List(126) { criterion -> BriefCriterion("criterion_$criterion", "The item expresses the living stone premise in its current fields.", key) }) }
        ok(h.briefs(otherBriefs + wideBriefs))
        val texture = "a".repeat(64)
        val items = CustomItemLibrary(items = itemKeys.map { CustomItemDefinition(it.id, "Living stone ${it.id}", texture, description = "A quiet living-stone artifact.") })
        h.sessions.putContent(h.id, h.current.revision, mapOf("items" to McpJson.encode(items).jsonObject),
            mapOf(texture to ContentAsset(texture, texture, "image/png", 68)))
        var accepted = 0
        var rejected = false
        for (brief in wideBriefs) {
            val current = report(h.context(brief.id), "review_${brief.id}").let { report -> report.copy(checks = report.checks.map {
                it.copy(claim = "Current item field evidence: " + "a".repeat(1600), conclusion = "Comparison against the stated source: " + "b".repeat(1600))
            }) }
            assertTrue(WorldsmithJson.encode(current).toByteArray(Charsets.UTF_8).size < 512 * 1024)
            val before = h.current
            val outcome = runCatching { h.review(current) }
            if (outcome.isFailure || outcome.getOrThrow().isError) {
                val message = outcome.exceptionOrNull()?.message ?: outcome.getOrThrow().text
                assertTrue(message.contains("state", true) && (message.contains("MiB") || message.contains("budget", true)), message)
                assertEquals(before, h.current)
                rejected = true; break
            }
            accepted++
        }
        assertTrue(accepted >= 3, "The aggregate limit should not reject a few individually bounded reports")
        assertTrue(rejected, "Five wide reports must not grow the authoring state beyond 2 MiB")
        assertEquals(accepted, h.current.authoring!!.alignmentReviews.size)
    }
}
