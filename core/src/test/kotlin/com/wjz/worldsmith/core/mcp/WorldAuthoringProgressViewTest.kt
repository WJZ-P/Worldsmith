package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.ContentAsset
import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** In-memory display fixtures only: no drawing worker, PNG reader, native host or UI automation. */
class WorldAuthoringProgressViewTest {
    @Test fun `legacy views deserialize without authoring and missing bibles do not invent a reading view`() {
        val legacy = WorkflowSession("a".repeat(32), "A focused artifact", mode = WorkflowMode.STANDALONE)
        val view = GenerationProgressViews.inspect(legacy)
        assertNull(view.authoring)
        val oldJson = JsonObject(McpJson.encode(view).jsonObject - "authoring")
        assertEquals(view, WorldsmithJson.decode<GenerationProgressView>(oldJson.toString()))
        val summary = GenerationProgressGateway({ listOf(legacy) }, { emptyList() }).snapshot(legacy.id).sessions.single()
        val oldSummary = JsonObject(McpJson.encode(summary).jsonObject - "authoringAvailable")
        assertFalse(WorldsmithJson.decode<GenerationSessionSummary>(oldSummary.toString()).authoringAvailable)
        val newWorld = legacy.copy(mode = WorkflowMode.COMPLETE_WORLD, authoring = WorldAuthoringState())
        val pending = GenerationProgressViews.inspect(newWorld)
        assertNull(pending.authoring)
        assertEquals("WORLD_BIBLE_DRAFT", pending.stage)
        assertFalse(pending.requiresUserAction, "An AI drafting gap is not user approval")
    }

    @Test fun `the selected bible projects deterministic markdown and actual root stage without native proof`() {
        val current = WorldAuthoringPolicyFixtures.session().copy(revision = 7)
        val state = requireNotNull(current.authoring)
        val bible = requireNotNull(state.bible)
        val view = GenerationProgressViews.inspect(current)
        val authoring = requireNotNull(view.authoring)
        assertEquals(current.id, view.sessionId)
        assertEquals(current.revision, view.revision)
        assertEquals(bible.title, view.title)
        assertEquals(state.bibleRevision, authoring.bibleRevision)
        assertEquals(bible.title, authoring.title)
        assertEquals(bible.premise, authoring.premise)
        assertEquals(WorldAuthoringModel.markdown(bible), authoring.markdown)
        assertFalse(authoring.markdownTruncated)
        assertEquals("WORLD_BIBLE_REVIEW", authoring.stage)
        assertEquals(view.stage, authoring.stage)
        assertFalse(authoring.aiReviewed)
        assertEquals(state.briefs.size, authoring.briefCount)
        assertEquals(0, authoring.reviewedBriefs)
        assertFalse(view.nativeActivationVerified)
        assertEquals(view, WorldsmithJson.decode<GenerationProgressView>(WorldsmithJson.encode(view)))
    }

    @Test fun `AI badges require current passing evidence and no PNG bytes are read by the projection`() {
        val initial = WorldAuthoringPolicyFixtures.session()
        val unreachableAssets = initial.contentAssets.mapValues { (_, asset) ->
            ContentAsset(asset.id, asset.sha256, asset.mediaType, asset.byteLength, "missing/on-purpose.blob")
        }
        val reviewed = WorldAuthoringPolicyFixtures.approveAll(initial.copy(contentAssets = unreachableAssets))
        val all = requireNotNull(GenerationProgressViews.inspect(reviewed).authoring)
        assertTrue(all.aiReviewed)
        assertEquals(all.briefCount, all.reviewedBriefs)
        assertFalse(GenerationProgressViews.inspect(reviewed).nativeActivationVerified)

        val items = McpJson.decode<CustomItemLibrary>(reviewed.contentModules.getValue("items"))
        val changed = reviewed.copy(contentModules = reviewed.contentModules + ("items" to McpJson.encode(
            items.copy(items = items.items.map { it.copy(displayName = "A revised item role") })).jsonObject))
        val staleContent = requireNotNull(GenerationProgressViews.inspect(changed).authoring)
        assertTrue(staleContent.aiReviewed, "An implementation edit does not rewrite setting facts")
        assertEquals(all.reviewedBriefs - 1, staleContent.reviewedBriefs)

        val state = requireNotNull(reviewed.authoring)
        val revisedBible = reviewed.copy(authoring = state.copy(bibleRevision = state.bibleRevision + 1,
            bible = requireNotNull(state.bible).copy(premise = "The old premise has materially changed")))
        val staleSetting = requireNotNull(GenerationProgressViews.inspect(revisedBible).authoring)
        assertFalse(staleSetting.aiReviewed, "A nonnull but stale report is not a current AI review")
        assertEquals(0, staleSetting.reviewedBriefs)

        val blocked = reviewed.copy(authoring = state.copy(bibleReview = requireNotNull(state.bibleReview).let { report ->
            report.copy(checks = report.checks.map { it.copy(status = ReviewCheckStatus.BLOCKED) })
        }))
        assertFalse(requireNotNull(GenerationProgressViews.inspect(blocked).authoring).aiReviewed)
    }

    @Test fun `only the markdown view is capped and its UTF16 boundary preserves complete characters`() {
        val bible = WorldAuthoringPolicyFixtures.bible().copy(nodes = List(12) { index ->
            WorldBibleNode("region_$index", WorldBibleNodeKind.REGION, "Region $index", "🌿".repeat(4096))
        })
        val session = WorkflowSession("c".repeat(32), WorldAuthoringPolicyFixtures.prompt,
            mode = WorkflowMode.COMPLETE_WORLD, authoring = WorldAuthoringState(bible = bible, bibleRevision = 4))
        assertTrue(WorldAuthoringModel.validateBible(bible, session.prompt).isEmpty())
        val full = WorldAuthoringModel.markdown(bible)
        val view = requireNotNull(GenerationProgressViews.inspect(session).authoring)
        assertTrue(full.length > GenerationProgressViews.MAX_AUTHORING_MARKDOWN)
        assertTrue(view.markdownTruncated)
        assertTrue(view.markdown.length <= GenerationProgressViews.MAX_AUTHORING_MARKDOWN)
        assertFalse(view.markdown.last().isHighSurrogate())
        assertTrue(full.startsWith(view.markdown))
        val restored = WorldsmithJson.decode<WorkflowSession>(WorldsmithJson.encode(session))
        assertEquals(bible, restored.authoring?.bible, "A shortened reading view must not truncate persisted facts")
        assertEquals(full, WorldAuthoringModel.markdown(requireNotNull(restored.authoring?.bible)))
    }

    @Test fun `manual non-active selection never borrows the active world's bible`() {
        val source = WorldAuthoringPolicyFixtures.session()
        fun named(id: Char, title: String) = source.copy(id = id.toString().repeat(32),
            authoring = requireNotNull(source.authoring).let { it.copy(bible = requireNotNull(it.bible).copy(title = title)) })
        val selected = named('a', "Selected coast")
        val active = named('b', "Active forest")
        val legacy = source.copy(id = "d".repeat(32), authoring = null)
        val live = mutableListOf(selected, active, legacy)
        val gateway = GenerationProgressGateway({ live.toList() }, { emptyList() })
        val empty = JsonObject(emptyMap())
        val activity = McpTool("worldsmith_put_world_bible", "World bible", "Writes a setting", empty, readOnly = false,
            handler = { McpToolResult.success(empty) })
        gateway.observe(activity).handler(buildJsonObject { put("sessionId", active.id) })
        assertEquals(active.id, gateway.snapshot(null).defaultSessionId)
        assertEquals("Active forest", gateway.snapshot(null).view?.authoring?.title)
        val pinned = gateway.snapshot(selected.id)
        assertEquals(selected.id, pinned.selectedSessionId)
        assertEquals("Selected coast", pinned.view?.authoring?.title)
        assertFalse(requireNotNull(pinned.view?.authoring).markdown.contains("Active forest"))
        assertNull(gateway.snapshot(legacy.id).view?.authoring)
        live.remove(selected)
        assertNull(gateway.snapshot(selected.id).view, "A vanished manual selection does not fall back to active authoring")
        assertEquals(active.id, gateway.snapshot(null).defaultSessionId, "Read-only display selection does not take activity focus")
    }

    @Test fun `current draft remains discoverable beside two selected saved packs without replacing their view`() {
        val base = WorldAuthoringPolicyFixtures.session()
        val saved = base.copy(id = "a".repeat(32), packId = "a".repeat(64), finished = true)
        val otherSaved = base.copy(id = "b".repeat(32), packId = "b".repeat(64), finished = true, authoring = null)
        val draft = base.copy(id = "c".repeat(32), revision = 12, packId = null, authoring = requireNotNull(base.authoring).let {
            it.copy(bible = requireNotNull(it.bible).copy(title = "Unpublished new world"))
        })
        val gateway = GenerationProgressGateway({ listOf(saved, otherSaved, draft) }, { emptyList() })
        val empty = JsonObject(emptyMap())
        val update = gateway.observe(McpTool("worldsmith_put_world_bible", "World bible", "Writes the current draft", empty, false,
            handler = { McpToolResult.success(empty) }))
        update.handler(buildJsonObject { put("sessionId", draft.id) })

        for (selected in listOf(saved, otherSaved)) {
            val packSnapshot = gateway.snapshot(selected.id)
            assertEquals(selected.id, packSnapshot.selectedSessionId)
            assertEquals(selected.packId, packSnapshot.view?.packId)
            val entry = requireNotNull(GenerationAuthoringDrafts.current(packSnapshot))
            assertEquals(draft.id, entry.sessionId)
            assertEquals("Unpublished new world", entry.title, "The bible title takes precedence over a previous runtime theme")
            assertTrue(entry.authoringAvailable)
            assertNull(entry.packId)
            assertNull(GenerationAuthoringDrafts.view(packSnapshot, draft.id, draft.revision),
                "The selected pack's projected view is not an active-draft snapshot")
            val draftSnapshot = gateway.snapshot(entry.sessionId)
            val draftView = requireNotNull(GenerationAuthoringDrafts.view(draftSnapshot, entry.sessionId, entry.revision))
            assertEquals(draft.id, draftView.sessionId)
            assertNull(draftView.packId)
            assertEquals("Unpublished new world", draftView.authoring?.title)
            assertEquals(selected.id, packSnapshot.selectedSessionId, "A separate draft read never mutates the existing pack snapshot")
            assertEquals(selected.packId, gateway.snapshot(selected.id).view?.packId)
        }
    }

    @Test fun `draft snapshot acceptance rejects changed live ownership ended sessions and stale revisions`() {
        val base = WorldAuthoringPolicyFixtures.session().copy(revision = 8)
        val next = base.copy(id = "2".repeat(32))
        val live = mutableListOf(base, next)
        val gateway = GenerationProgressGateway({ live.toList() }, { emptyList() })
        val empty = JsonObject(emptyMap())
        val update = gateway.observe(McpTool("worldsmith_put_world_bible", "World bible", "Writes a draft", empty, false,
            handler = { McpToolResult.success(empty) }))
        assertNull(GenerationAuthoringDrafts.current(gateway.snapshot(base.id)), "Historical iteration order is not current authoring")
        update.handler(buildJsonObject { put("sessionId", base.id) })
        val snapshot = gateway.snapshot(base.id)
        val view = requireNotNull(snapshot.view)
        assertNotNull(GenerationAuthoringDrafts.view(snapshot, base.id, base.revision))
        assertNull(GenerationAuthoringDrafts.view(snapshot, base.id, base.revision + 1))
        assertNull(GenerationAuthoringDrafts.view(snapshot.copy(view = view.copy(revision = base.revision - 1)), base.id, 0))
        assertNull(GenerationAuthoringDrafts.view(snapshot.copy(view = view.copy(sessionId = next.id)), base.id, 0))
        assertNull(GenerationAuthoringDrafts.view(snapshot.copy(selectedSessionId = next.id), base.id, 0))
        assertNull(GenerationAuthoringDrafts.view(snapshot.copy(view = view.copy(authoring = null)), base.id, 0))

        update.handler(buildJsonObject { put("sessionId", next.id) })
        assertNull(GenerationAuthoringDrafts.view(gateway.snapshot(base.id), base.id, base.revision),
            "A late read does not open a draft that no longer owns active authoring")
        live[1] = next.copy(finished = true)
        assertNull(GenerationAuthoringDrafts.current(gateway.snapshot(base.id)),
            "Do not silently substitute an older unfinished draft after the current session finishes")
        live[1] = next.copy(authoring = WorldAuthoringState())
        assertNull(GenerationAuthoringDrafts.current(gateway.snapshot(base.id)), "An empty bible has no reading entry")
    }
}
