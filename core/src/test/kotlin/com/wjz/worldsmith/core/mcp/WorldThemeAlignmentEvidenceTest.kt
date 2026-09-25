package com.wjz.worldsmith.core.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Theme provenance and configured relationships only: these tests do not start Minecraft or judge art. */
class WorldThemeAlignmentEvidenceTest {
    private fun session(vararg edges: Pair<String, List<String>>): WorkflowSession {
        val session = WorldAuthoringPolicyFixtures.session()
        val state = requireNotNull(session.authoring)
        val replacements = edges.toMap()
        val updated = state.copy(briefs = state.briefs.map { record ->
            record.copy(brief = record.brief.copy(dependencies = replacements[record.brief.id] ?: record.brief.dependencies))
        })
        return session.copy(authoring = updated.copy(briefs = updated.briefs.map { record ->
            record.copy(basisDigest = WorldAuthoringPolicy.basisDigest(updated, record.brief))
        }))
    }

    private fun withEvidence(session: WorkflowSession, paths: List<String>, subject: String = "structures", status: ReviewCheckStatus = ReviewCheckStatus.PASS): AuthoringReview =
        WorldAuthoringPolicyFixtures.review(session, subject, status).let { report ->
            report.copy(checks = report.checks.map { it.copy(evidencePaths = paths) })
        }

    @Test fun `building review can compare its actual placement with explicit terrain and transitive biome dependencies`() {
        val session = session("structures" to listOf("terrain"), "terrain" to listOf("biomes"))
        val context = WorldAuthoringPolicy.reviewContext(session, "structures")
        val dependencies = context.getValue("dependencyEvidenceRoots").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("/modules/terrain" in dependencies)
        assertTrue(dependencies.any { it.startsWith("/modules/biomes/biomes/") })
        assertFalse(dependencies.any { it.startsWith("/modules/creatures") })
        val report = withEvidence(session, listOf("/modules/structures/structures/0/placement", "/modules/terrain/shape", "/modules/biomes/biomes/0"))
        assertEquals(emptyList<Any>(), WorldAuthoringPolicy.validateReviewForSession(session, report))
        val terrain = session.contentModules.getValue("terrain")
        val changed = session.copy(contentModules = session.contentModules + ("terrain" to JsonObject(terrain + ("seaLevel" to JsonPrimitive(70)))))
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(changed, report).any { it.code.endsWith("REVIEW_STALE") })
    }

    @Test fun `dependency alone never proves the building even when that dependency is valid`() {
        val session = session("structures" to listOf("terrain"))
        val report = withEvidence(session, listOf("/modules/terrain/shape"))
        val problems = WorldAuthoringPolicy.validateReviewForSession(session, report)
        assertTrue(problems.any { it.code == "AUTHORING_ALIGNMENT_PRIMARY_EVIDENCE_REQUIRED" })
        assertFalse(problems.any { it.code.endsWith("EVIDENCE_INVALID") })
        val blocked = withEvidence(session, listOf("/modules/terrain/shape"), status = ReviewCheckStatus.BLOCKED)
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, blocked).any { it.code.endsWith("PRIMARY_EVIDENCE_REQUIRED") })
    }

    @Test fun `same-session content and source prose are not implicitly made valid comparison evidence`() {
        val session = session("structures" to listOf("terrain"))
        for (path in listOf("/modules/creatures/creatures/0", "/sourceContext/originalPrompt", "/bible", "/modules/terrain/missing", "/modules/terrain/shape/~2bad")) {
            val report = withEvidence(session, listOf("/modules/structures/structures/0", path))
            assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, report).any { it.code.endsWith("EVIDENCE_INVALID") }, path)
        }
        val noDependency = session()
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(noDependency, withEvidence(noDependency,
            listOf("/modules/structures/structures/0", "/modules/terrain/shape"))).any { it.code.endsWith("EVIDENCE_INVALID") })
    }

    @Test fun `review source context shows actual criterion claims and resolved world facts rather than ids alone`() {
        val session = session("structures" to listOf("terrain"))
        val source = WorldAuthoringPolicy.reviewContext(session, "structures").getValue("sourceContext").jsonObject
        assertEquals(session.prompt, source.getValue("originalPrompt").jsonPrimitive.content)
        assertEquals("structures", source.getValue("briefId").jsonPrimitive.content)
        assertFalse(source.getValue("implementationEvidence").jsonPrimitive.boolean)
        val entries = source.getValue("entries").jsonArray.map { it.jsonObject }
        val criterion = entries.single { it["kind"]?.jsonPrimitive?.content == "criterion" }
        assertEquals("This target expresses its assigned role", criterion.getValue("claim").jsonPrimitive.content)
        assertEquals("hall", criterion.getValue("target").jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(entries.any { it["id"]?.jsonPrimitive?.content == "node/shore" && "memory stones" in it.getValue("value").jsonPrimitive.content })
        assertTrue(entries.any { it["kind"]?.jsonPrimitive?.content == "dependencyBrief" && it["id"]?.jsonPrimitive?.content == "terrain" })
    }

    @Test fun `whole source entries page deterministically without changing evidence digests`() {
        val original = session()
        val state = requireNotNull(original.authoring)
        val bible = requireNotNull(state.bible)
        val extra = (0 until 48).map { index -> WorldBibleNode("source_$index", WorldBibleNodeKind.STYLE, "Style $index", "岭".repeat(7000)) }
        val session = original.copy(authoring = state.copy(bible = bible.copy(nodes = bible.nodes + extra), briefs = state.briefs.map { record ->
            if (record.brief.id == "structures") record.copy(brief = record.brief.copy(basisRefs = extra.map { "node/${it.id}" })) else record
        }))
        val first = WorldAuthoringPolicy.reviewContext(session, "structures")
        val expected = first.getValue("expectedInputDigest").jsonPrimitive.content
        var offset = 0
        val collected = mutableListOf<JsonObject>()
        do {
            val context = WorldAuthoringPolicy.reviewContext(session, "structures", offset, expected)
            assertEquals(first["expectedInputDigest"], context["expectedInputDigest"])
            val source = context.getValue("sourceContext").jsonObject
            val entries = source.getValue("entries").jsonArray
            assertTrue(entries.size in 1..32)
            assertTrue(entries.toString().toByteArray(Charsets.UTF_8).size <= 49 * 1024)
            collected += entries.map { it.jsonObject }
            val next = source["nextOffset"]?.jsonPrimitive?.int
            assertEquals(next != null, source.getValue("truncated").jsonPrimitive.boolean)
            if (next == null) {
                assertEquals(source.getValue("totalEntries").jsonPrimitive.int, collected.size)
                break
            }
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(collected.size, collected.map { it.getValue("kind") to it.getValue("id") }.distinct().size)
        assertEquals(48, collected.count { it["id"]?.jsonPrimitive?.content?.startsWith("node/source_") == true })
        assertThrows(IllegalArgumentException::class.java) { WorldAuthoringPolicy.reviewContext(session, "structures", -1) }
        assertThrows(IllegalArgumentException::class.java) { WorldAuthoringPolicy.reviewContext(session, "structures", Int.MAX_VALUE) }
    }

    @Test fun `Bible requires a two-sided original prompt comparison not just extracted requirements`() {
        val session = session()
        val report = WorldAuthoringPolicyFixtures.review(session, "world_bible")
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, report).isEmpty())
        val missing = report.copy(checks = report.checks.filterNot { it.criterionId == "prompt_alignment" })
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, missing).any { it.code == "WORLD_BIBLE_REVIEW_INCOMPLETE" })
        for (paths in listOf(listOf("/bible"), listOf("/originalPrompt"))) {
            val oneSided = report.copy(checks = report.checks.map { if (it.criterionId == "prompt_alignment") it.copy(evidencePaths = paths) else it })
            assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, oneSided).any { it.code == "WORLD_BIBLE_PROMPT_COMPARISON_REQUIRED" })
        }
        val otherPrompt = session.copy(prompt = session.prompt + ", with a dramatic mountain silhouette")
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(otherPrompt, report).any { it.code == "WORLD_BIBLE_REVIEW_STALE" })
    }

    @Test fun `long legal assumptions are paged as complete notes rather than one oversized entry`() {
        val original = session()
        val state = requireNotNull(original.authoring)
        val notes = (0 until 64).map { index -> "$index:${"山".repeat(2040)}" }
        val session = original.copy(authoring = state.copy(bible = requireNotNull(state.bible).copy(assumptions = notes)))
        val expected = WorldAuthoringPolicy.reviewContext(session, "structures").getValue("expectedInputDigest").jsonPrimitive.content
        var offset = 0
        val actual = mutableMapOf<Int, String>()
        do {
            val source = WorldAuthoringPolicy.reviewContext(session, "structures", offset, expected).getValue("sourceContext").jsonObject
            val entries = source.getValue("entries").jsonArray
            assertTrue(entries.toString().toByteArray(Charsets.UTF_8).size < 49 * 1024)
            entries.map { it.jsonObject }.filter { it["id"]?.jsonPrimitive?.content == "world/assumptions" }.forEach { entry ->
                assertEquals(64, entry.getValue("collectionSize").jsonPrimitive.int)
                assertNull(actual.put(entry.getValue("itemIndex").jsonPrimitive.int, entry.getValue("value").jsonPrimitive.content))
            }
            val next = source["nextOffset"]?.jsonPrimitive?.int ?: break
            assertTrue(next > offset); offset = next
        } while (true)
        assertEquals(notes.sorted(), actual.toSortedMap().values.toList())
    }

    @Test fun `source continuation rejects changed reviewed inputs but not unrelated revision changes`() {
        val original = session()
        val expected = WorldAuthoringPolicy.reviewContext(original, "structures").getValue("expectedInputDigest").jsonPrimitive.content
        assertThrows(IllegalArgumentException::class.java) { WorldAuthoringPolicy.reviewContext(original, "structures", 1) }
        val unchanged = WorldAuthoringPolicy.reviewContext(original.copy(revision = original.revision + 1), "structures", 1, expected)
        assertEquals(expected, unchanged.getValue("expectedInputDigest").jsonPrimitive.content)
        val state = requireNotNull(original.authoring)
        val changed = original.copy(authoring = state.copy(bible = requireNotNull(state.bible).let { bible ->
            bible.copy(nodes = bible.nodes.map { if (it.id == "shore") it.copy(description = "The observatory overlooks a newly sheltered bay") else it })
        }))
        val failure = assertThrows(IllegalArgumentException::class.java) { WorldAuthoringPolicy.reviewContext(changed, "structures", 1, expected) }
        assertTrue(failure.message!!.startsWith("AUTHORING_REVIEW_CONTEXT_CHANGED"))
        assertNotEquals(expected, WorldAuthoringPolicy.reviewContext(changed, "structures").getValue("expectedInputDigest").jsonPrimitive.content)
    }

    @Test fun `blocked missing building can cite absence and the planned environmental dependency without passing`() {
        val session = session("structures" to listOf("terrain")).copy(structures = emptyMap())
        val blocked = withEvidence(session, listOf("/modules/structures/structures", "/modules/terrain/shape"), status = ReviewCheckStatus.BLOCKED)
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, blocked).isEmpty())
        val passed = blocked.copy(checks = blocked.checks.map { it.copy(status = ReviewCheckStatus.PASS) })
        assertTrue(WorldAuthoringPolicy.validateReviewForSession(session, passed).any { it.code.endsWith("PRIMARY_EVIDENCE_REQUIRED") })
    }
}
