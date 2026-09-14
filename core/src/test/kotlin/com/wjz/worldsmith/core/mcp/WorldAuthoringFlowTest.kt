package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.drawhost.DrawingHost
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Navigation checks on policy-only drafts; these fixtures do not establish Core publication or gameplay. */
class WorldAuthoringFlowTest {
    @TempDir lateinit var root: Path

    private fun pending() = WorldAuthoringPolicyFixtures.approveBible(WorldAuthoringPolicyFixtures.session()).copy(revision = 19)
    private fun reviewed() = WorldAuthoringPolicyFixtures.approveAll(WorldAuthoringPolicyFixtures.session()).copy(revision = 19)
    private fun authoring(session: WorkflowSession) = requireNotNull(session.authoring)

    // Keep the ordering identical to WorldGenerationProgress without letting unrelated Core-fixture
    // deficiencies pretend that this authoring-only test has completed geometry or supply validation.
    private fun orderedAuthoring(session: WorkflowSession) = WorldAuthoringFlow.issues(session)
        .sortedWith(compareBy({ it.priority }, { it.code }, { it.message }))

    private fun blocked(session: WorkflowSession, subject: String, conclusion: String): WorkflowSession {
        val report = WorldAuthoringPolicyFixtures.review(session, subject, ReviewCheckStatus.BLOCKED, "blocked_$subject")
            .let { it.copy(checks = it.checks.map { check -> check.copy(conclusion = conclusion) }) }
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, report).isEmpty())
        return session.copy(authoring = authoring(session).copy(
            alignmentReviews = authoring(session).alignmentReviews.filter { it.subjectId != subject } + report))
    }

    private fun receipt(session: WorkflowSession, subject: String, inline: List<String> = emptyList()): WorkflowSession = session.copy(
        lastWriteFailure = PackValidationReceipt.bounded(session.revision,
            listOf(Diagnostic("authoring.briefs.$subject.review", "AUTHORING_ALIGNMENT_REVIEW_REQUIRED", DiagnosticSeverity.ERROR,
                "The rejected frozen snapshot needs current evidence for the $subject brief.")),
            "Living stone", "The current authoring fixture", inline),
    )

    @Test fun `declared authoring targets with missing alignment reports navigate to existing owner review rather than brief recreation`() {
        val session = pending()
        val inventory = WorldDesignCoverage.draft(session)
        assertTrue(inventory.moduleErrors.isEmpty())
        assertTrue(authoring(session).briefs.all { record -> record.brief.targets.all { it in inventory.symbols } })
        assertTrue(authoring(session).alignmentReviews.isEmpty())
        val ordered = orderedAuthoring(session)
        assertTrue(ordered.isNotEmpty())
        assertEquals("worldsmith_get_authoring_review_context", ordered.first().nextTool)
        assertFalse(ordered.any { it.nextTool == "worldsmith_put_module_briefs" }, ordered.toString())
        val requirement = ordered.single { it.code == "AUTHORING_ALIGNMENT_REQUIREMENT_UNCOVERED" }
        assertEquals("items", requirement.arguments.getValue("subjectId").jsonPrimitive.content)
        assertEquals("worldsmith_get_authoring_review_context", requirement.nextTool)
    }

    @Test fun `current blocked item and architecture reports navigate to actual content repair and preserve their conclusions`() {
        val baseline = reviewed()
        for ((subject, tool) in listOf("items" to "worldsmith_put_content_modules", "structures" to "worldsmith_put_architecture_draft")) {
            val conclusion = "The $subject definition expresses a fortress role instead of the promised quiet shelter."
            val session = blocked(baseline, subject, conclusion)
            val issues = orderedAuthoring(session)
            assertTrue(issues.isNotEmpty())
            assertTrue(issues.all { it.nextTool == tool }, issues.toString())
            assertTrue(issues.all { it.message.contains(conclusion) }, "The repair must carry the actual saved finding")
            assertTrue(issues.all { it.message.contains("/modules/") }, "The repair retains the reviewed evidence pointers")
            assertTrue(issues.all { it.arguments.getValue("expectedRevision").jsonPrimitive.long == session.revision })
            assertTrue(issues.none { "subjectId" in it.arguments })
            val required = issues.first().requiresAuthoring
            if (subject == "items") assertEquals(listOf("modules.items"), required)
            else assertEquals(listOf("architecture", "structures"), required)
        }
    }

    @Test fun `missing and stale implementation reports request a fresh review rather than replaying an old blocked finding`() {
        val baseline = reviewed()
        val missing = baseline.copy(authoring = authoring(baseline).copy(
            alignmentReviews = authoring(baseline).alignmentReviews.filter { it.subjectId != "items" }))
        val oldBlocked = blocked(baseline, "items", "The old token design used the wrong material identity.")
        val originalItems = McpJson.decode<CustomItemLibrary>(oldBlocked.contentModules.getValue("items"))
        val changed = oldBlocked.copy(contentModules = oldBlocked.contentModules + ("items" to McpJson.encode(
            originalItems.copy(items = originalItems.items.map { it.copy(displayName = "Restored memory token") })).jsonObject))
        for (session in listOf(missing, changed)) {
            val issues = orderedAuthoring(session)
            assertTrue(issues.isNotEmpty())
            assertTrue(issues.all { it.nextTool == "worldsmith_get_authoring_review_context" }, issues.toString())
            assertTrue(issues.all { it.arguments.getValue("subjectId").jsonPrimitive.content == "items" })
            assertTrue(issues.none { "expectedRevision" in it.arguments })
            assertTrue(issues.none { it.message.contains("The old token design used the wrong material identity.") })
        }
    }

    @Test fun `authoring failure receipts without inline inputs select the diagnosed brief even when other reviews are also missing`() {
        val baseline = pending()
        for (subject in listOf("items", "structures")) {
            val session = receipt(baseline, subject)
            val progress = WorldGenerationProgress.inspect(session)
            assertTrue(progress.writeFailureCurrent)
            assertEquals("worldsmith_get_authoring_review_context", progress.nextTool)
            assertEquals(subject, progress.nextArguments.getValue("subjectId").jsonPrimitive.content,
                "A frozen-write finding must not be replaced by the first unrelated missing brief")
            assertEquals(setOf("sessionId", "subjectId"), progress.nextArguments.keys)
            assertNotEquals("worldsmith_get_content_draft", progress.nextTool)
        }
    }

    @Test fun `authoring failure receipts commit unsaved inline modules or architecture with CAS before requesting another review`() {
        val baseline = pending()
        val cases = listOf(
            Triple(listOf("items", "theme"), "worldsmith_put_content_modules", listOf("modules.items", "modules.theme")),
            Triple(listOf("structures", "architecture"), "worldsmith_put_architecture_draft", listOf("architecture", "structures")),
            Triple(listOf("items", "structures"), "worldsmith_put_content_modules", listOf("modules.items")),
        )
        for ((inline, tool, required) in cases) {
            val session = receipt(baseline, "items", inline)
            val progress = WorldGenerationProgress.inspect(session)
            assertEquals(tool, progress.nextTool)
            assertEquals(setOf("sessionId", "expectedRevision"), progress.nextArguments.keys)
            assertEquals(session.revision, progress.nextArguments.getValue("expectedRevision").jsonPrimitive.long)
            assertEquals(required, progress.requiredAuthoring)
            assertTrue(progress.nextInstruction.contains("unsaved inline inputs"))
            inline.forEach { assertTrue(progress.nextInstruction.contains(it)) }
        }
    }

    @Test fun `authoring navigation arguments contain only fields declared by the destination public tool schema`() {
        val baseline = pending()
        val ready = reviewed()
        val itemRepair = blocked(ready, "items", "The token's recorded role needs to express peaceful restoration.")
        val architectureRepair = blocked(ready, "structures", "The hall's role needs to express an open shelter.")
        val snapshots = listOf(
            baseline.copy(authoring = WorldAuthoringState()),
            WorldAuthoringPolicyFixtures.session(),
            baseline.copy(designPlan = null),
            baseline.copy(authoring = authoring(baseline).copy(briefs = emptyList())),
            baseline, itemRepair, architectureRepair,
        )
        DrawingHost(root.resolve("schema_jobs")).use { host ->
            val catalog = WorldsmithMcpTools(root.resolve("schema_packs"), drawings = host).all().associateBy { it.name }
            fun schemaMatches(tool: String, arguments: JsonObject) {
                val properties = catalog.getValue(tool).inputSchema.getValue("properties").jsonObject.keys
                assertTrue(properties.containsAll(arguments.keys), "$tool received undeclared fields ${arguments.keys - properties}")
            }
            val helperTools = listOf("worldsmith_get_world_bible", "worldsmith_get_authoring_review_context", "worldsmith_put_world_bible",
                "worldsmith_review_world_bible", "worldsmith_put_module_briefs", "worldsmith_review_world_alignment",
                "worldsmith_upgrade_world_authoring", "worldsmith_put_world_design_plan", "worldsmith_put_content_modules",
                "worldsmith_put_architecture_draft", "worldsmith_write_pack")
            helperTools.forEach { tool -> schemaMatches(tool, WorldAuthoringFlow.arguments(baseline, tool, "items")) }
            snapshots.flatMap { WorldAuthoringFlow.issues(it) }.forEach { schemaMatches(it.nextTool, it.arguments) }
            for ((tool, subject) in listOf("worldsmith_get_authoring_review_context" to "items", "worldsmith_put_world_bible" to "world_bible",
                "worldsmith_put_content_modules" to "items", "worldsmith_put_architecture_draft" to "structures")) {
                val result = WorldAuthoringMcpService.blocked(baseline, listOf(Diagnostic("authoring", "AUTHORING_TEST", DiagnosticSeverity.ERROR,
                    "A current authoring fixture needs repair.")), tool, subject)
                schemaMatches(result.structuredContent.getValue("nextTool").jsonPrimitive.content,
                    result.structuredContent.getValue("nextArguments").jsonObject)
            }
            assertEquals(setOf("sessionId", "subjectId"), WorldAuthoringFlow.arguments(baseline, "worldsmith_get_authoring_review_context", "items").keys)
            for (tool in listOf("worldsmith_put_world_bible", "worldsmith_put_content_modules")) {
                assertEquals(setOf("sessionId", "expectedRevision"), WorldAuthoringFlow.arguments(baseline, tool, "items").keys)
            }
        }
    }

    @Test fun `focused modes retain their original navigation and asset delegates without world bible or brief input`() {
        val sessions = WorkflowSessions()
        var calls = 0
        val probe = McpTool("worldsmith_build_drawing", "Focused probe", "Observe only the existing delegate boundary",
            McpJson.schema(mapOf("sessionId" to McpJson.type("string")), listOf("sessionId")), false,
            handler = { calls++; McpToolResult.success(buildJsonObject { put("entered", true) }) })
        val guarded = WorldAuthoringFlow.guard(probe, sessions)
        for (mode in listOf(WorkflowMode.WORLDGEN_ONLY, WorkflowMode.STANDALONE)) {
            val session = sessions.begin("A focused fixture request", mode)
            assertNull(session.authoring)
            assertTrue(WorldAuthoringFlow.issues(session).isEmpty())
            val progress = WorldGenerationProgress.inspect(session)
            assertFalse(progress.nextTool.contains("world_bible"))
            assertFalse(progress.nextTool.contains("module_briefs"))
            assertFalse(progress.nextTool.contains("authoring_review"))
            assertFalse(progress.requiredAuthoring.any { it == "bible" || it == "briefs" || it == "briefIds" })
            assertFalse(guarded.handler(buildJsonObject { put("sessionId", session.id) }).isError)
        }
        assertEquals(2, calls)
    }
}
