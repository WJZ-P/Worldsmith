package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.TerrainPlan
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorldContentAuthoringTest {
    @TempDir lateinit var root: Path
    private val sessions by lazy { WorkflowSessions(directory = root.resolve("drafts")) }
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs"), sessions = sessions) }
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap()), target: WorldsmithMcpTools = tools) =
        target.all().single { it.name == name }.handler(args)
    private fun begin(): String = call(WorldsmithWorkflow.BEGIN_TOOL, buildJsonObject { put("prompt", "A moonstone civilization and its stone guardians") })
        .structuredContent.getValue("sessionId").jsonPrimitive.content
    private fun revision(id: String) = sessions.find(id)!!.revision
    private fun draft(id: String, target: WorldsmithMcpTools = tools) = call("worldsmith_get_content_draft", buildJsonObject { put("sessionId", id) }, target).structuredContent
    private fun texture(id: String, expected: Long = revision(id)) = call("worldsmith_create_pixel_texture", buildJsonObject {
        put("sessionId", id); put("expectedRevision", expected)
        put("palette", McpJson.encode(listOf("#675c8e", "#a4b9eb")))
        put("rows", McpJson.encode(List(32) { y -> List(32) { x -> if ((x + y) % 8 == 0) 1 else 0 } }))
    })
    private fun put(id: String, modules: Map<String, JsonObject>, expected: Long = revision(id)) = call("worldsmith_put_content_modules", buildJsonObject {
        put("sessionId", id); put("expectedRevision", expected); put("modules", JsonObject(modules))
    })
    private fun template() = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
    private fun textFiles(): Set<String> = if (!Files.isDirectory(root.resolve("content-assets"))) emptySet() else
        Files.walk(root.resolve("content-assets")).use { stream -> stream.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList().toSet() }

    private fun completeDraft(): Pair<String, String> {
        val id = begin()
        StructureTestWorld.install(tools, id)
        val made = texture(id)
        assertFalse(made.isError, made.text)
        assertEquals(1, made.images.size)
        val asset = McpJson.decode<ContentAsset>(made.structuredContent.getValue("asset"))
        assertEquals(asset.sha256, asset.id)
        val template = template()
        val terrain = McpJson.decode<TerrainPlan>(template.getValue("terrain"))
        val blocks = CustomBlockLibrary(blocks = listOf(CustomBlockDefinition("moonstone", "Moonstone", textureAsset = asset.id, light = 4)))
        val biome = template.getValue("biomes").jsonObject.getValue("biomes").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        val creature = CreatureDefinition("guardian", "Moonstone Guardian", CreatureCategory.HOSTILE,
            CreatureModel(asset.id, 32, 32, listOf(CreatureBone("body", pivot = CreatureVector(0f, 24f, 0f),
                cubes = listOf(CreatureCube(CreatureVector(-2f, -8f, -2f), CreatureVector(4f, 8f, 4f)))))),
            spawn = CreatureSpawn(biomes = listOf(biome)), themeRole = "Keeps the moonstone civic courts")
        val theme = WorldTheme(id = "moon_courts", title = "Moonstone Courts", premise = "The civic courts are cut from a living moonstone deposit.",
            playerRole = "A visitor learning how the material shaped the civilization.", worldRules = listOf("The same moonstone is found in the land and its guardians."),
            mainConflict = "Recover the courts' shared purpose before their guardians drive every traveler away.",
            beats = listOf(WorldNarrativeBeat("courts", "Find the moonstone courts", "Meet the guardians and read the landscape around the civic monument.", listOf(
                ContentKey("block", "moonstone"), ContentKey("creature", "guardian"), ContentKey("structure", StructureTestWorld.fixture().structures.first().id), ContentKey("biome", biome)))))
        val saved = put(id, mapOf("terrain" to McpJson.encode(terrain.copy(defaultBlock = terrain.defaultBlock.copy(preferredIds = listOf("worldsmith:content/moonstone")))).jsonObject,
            "biomes" to template.getValue("biomes").jsonObject, "features" to template.getValue("features").jsonObject,
            "theme" to McpJson.encode(theme).jsonObject, "blocks" to McpJson.encode(blocks).jsonObject,
            "creatures" to McpJson.encode(CreatureLibrary(creatures = listOf(creature))).jsonObject))
        assertFalse(saved.isError, saved.text)
        return id to asset.id
    }

    @Test fun `stale module and texture CAS fails without changing memory disk or attached assets`() {
        val id = begin()
        val first = put(id, mapOf("theme" to template().getValue("theme").jsonObject))
        assertFalse(first.isError)
        val before = sessions.find(id)!!
        val bytes = Files.readAllBytes(root.resolve("drafts/$id.json"))
        val paths = textFiles()
        val conflict = assertThrows(IllegalArgumentException::class.java) {
            put(id, mapOf("blocks" to McpJson.encode(CustomBlockLibrary()).jsonObject), expected = before.revision - 1)
        }
        assertTrue(conflict.message!!.contains("DRAFT_REVISION_CONFLICT"))
        assertThrows(IllegalArgumentException::class.java) { texture(id, expected = before.revision - 1) }
        assertEquals(before, sessions.find(id))
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("drafts/$id.json")))
        assertEquals(paths, textFiles(), "A rejected stale upload must not create even an unattached blob")
    }

    @Test fun `restart restores exact modules asset handles and shared revision without source execution`() {
        val id = begin()
        val made = texture(id)
        put(id, mapOf("theme" to template().getValue("theme").jsonObject))
        val before = draft(id)
        val recovered = WorkflowSessions(directory = root.resolve("drafts"))
        val restarted = WorldsmithMcpTools(root.resolve("packs"), sessions = recovered)
        assertTrue(recovered.recoveryDiagnostics.isEmpty())
        assertEquals(before, draft(id, restarted))
        val assetId = made.structuredContent.getValue("asset").jsonObject.getValue("id").jsonPrimitive.content
        val image = call("worldsmith_preview_texture_asset", buildJsonObject { put("sessionId", id); put("assetId", assetId) }, restarted)
        assertFalse(image.isError)
        assertArrayEquals(Base64.getDecoder().decode(made.images.single().data), Base64.getDecoder().decode(image.images.single().data))
        assertEquals(before, draft(id, restarted), "Preview is read-only after recovery")
    }

    @Test fun `pixel texture blocks creature and linked theme publish and load one immutable format3 world`() {
        val (id, assetId) = completeDraft()
        val plan = call("worldsmith_plan_world_content", buildJsonObject { put("sessionId", id) })
        assertFalse(plan.isError, plan.text)
        assertFalse(plan.structuredContent.getValue("activationVerified").jsonPrimitive.boolean)
        val written = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("sessionId", id); put("expectedRevision", revision(id)); put("displayName", "Moonstone world")
        })
        assertFalse(written.isError, written.text)
        val pack = WorldsmithPackLoader.loadDirectory(Path.of(written.structuredContent.getValue("path").jsonPrimitive.content))
        assertEquals(3, pack.manifest.formatVersion)
        assertEquals(pack.manifest.id, pack.computedId)
        assertEquals("moon_courts", pack.theme.id)
        assertEquals("moonstone", pack.blocks.blocks.single().id)
        assertEquals("guardian", pack.creatures.creatures.single().id)
        assertEquals(assetId, pack.manifest.assets.single().sha256)
        assertEquals(setOf(assetId), pack.assets.keys)
        val diagnostics = WorldsmithPackValidator.validate(pack)
        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        assertTrue(diagnostics.all { it.code == "CLEAR_REGION_REFILLED" }, "Only the fixture's known visible geometry warnings are expected")
        assertEquals(pack.manifest.id, sessions.find(id)!!.packId)
        assertFalse(written.structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
        val publishedRevision = written.structuredContent.getValue("revision").jsonPrimitive.long
        assertEquals(revision(id), publishedRevision)
        val repeated = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("sessionId", id); put("expectedRevision", publishedRevision); put("displayName", "Moonstone world")
        })
        assertFalse(repeated.isError, repeated.text)
        assertEquals(pack.manifest.id, repeated.structuredContent.getValue("id").jsonPrimitive.content)
        assertEquals(publishedRevision, repeated.structuredContent.getValue("revision").jsonPrimitive.long,
            "Identical frozen publication is idempotent at its current revision")
    }

    @Test fun `unknown quests module rejects the whole edit without partial theme mutation`() {
        val id = begin()
        val before = sessions.find(id)!!
        val bytes = Files.readAllBytes(root.resolve("drafts/$id.json"))
        val error = assertThrows(IllegalStateException::class.java) {
            put(id, linkedMapOf("theme" to template().getValue("theme").jsonObject, "quests" to buildJsonObject { put("schemaVersion", 1) }))
        }
        assertTrue(error.message!!.contains("quests"))
        assertEquals(before, sessions.find(id))
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("drafts/$id.json")))
    }

    @Test fun `corrupted immutable session PNG rejects save and leaves the draft unpublished`() {
        val (id, assetId) = completeDraft()
        val handle = sessions.find(id)!!.contentAssets.getValue(assetId)
        val file = root.resolve("content-assets").resolve(handle.path!!)
        val damaged = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(file, damaged)
        val before = sessions.find(id)!!
        val failure = assertThrows(IllegalArgumentException::class.java) {
            call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject { put("sessionId", id); put("expectedRevision", revision(id)); put("displayName", "Corrupted asset") })
        }
        assertTrue(failure.message!!.contains("hash mismatch"))
        assertEquals(before, sessions.find(id))
        assertNull(sessions.find(id)!!.packId)
        assertFalse(Files.isDirectory(root.resolve("packs")), "Asset verification must fail before publishing any pack directory")
        assertArrayEquals(damaged, Files.readAllBytes(file), "Corrupt evidence stays in place instead of being silently replaced")
    }

    @Test fun `write requires explicit session CAS and real theme rather than fixture fallback`() {
        val id = begin()
        val noRevision = assertThrows(IllegalArgumentException::class.java) {
            call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject { put("sessionId", id); put("displayName", "Missing CAS") })
        }
        assertTrue(noRevision.message!!.contains("expectedRevision"))
        val template = template()
        val noTheme = assertThrows(IllegalStateException::class.java) {
            call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
                put("displayName", "Missing theme"); listOf("terrain", "biomes", "features", "structures").forEach { put(it, template.getValue(it)) }
            })
        }
        assertTrue(noTheme.message!!.contains("theme"))
    }

    @Test fun `content contracts expose implemented fields and never invent future module support`() {
        for (module in listOf("theme", "blocks", "creatures")) {
            val result = call("worldsmith_get_content_contract", buildJsonObject { put("module", module) })
            assertFalse(result.isError)
            val content = result.structuredContent.getValue("contract").jsonPrimitive.content
            assertTrue(content.contains("expectedRevision"))
            assertTrue(content.contains("worldsmith_put_content_modules"))
            assertEquals(1, result.structuredContent.getValue("schemaVersion").jsonPrimitive.int)
        }
        assertThrows(IllegalArgumentException::class.java) { call("worldsmith_get_content_contract", buildJsonObject { put("module", "quests") }) }
    }

    @Test fun `two different publications at one draft revision have exactly one CAS winner`() {
        val initial = sessions.begin("Concurrent publications must not rebind one another")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = listOf("a".repeat(64), "b".repeat(64)).map { identity ->
                executor.submit<WorkflowSession?> {
                    ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                    sessions.recordPackAtRevision(initial.id, identity, initial.revision)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val result = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, result.count { it != null })
            val winner = result.filterNotNull().single()
            val current = sessions.find(initial.id)!!
            assertEquals(winner.packId, current.packId)
            assertEquals(initial.revision + 1, current.revision)
            assertNull(sessions.recordPackAtRevision(initial.id, "c".repeat(64), initial.revision))
            assertEquals(current, sessions.recordPackAtRevision(initial.id, current.packId!!, current.revision))
            assertEquals(current, WorkflowSessions(directory = root.resolve("drafts")).find(initial.id), "Winning CAS must be durable")
        }
    }

    @Test fun `detaching a texture is durable but preserves stored bytes and already published bundles`() {
        val (id, assetId) = completeDraft()
        val stored = sessions.find(id)!!.contentAssets.getValue(assetId)
        val blob = root.resolve("content-assets").resolve(stored.path!!)
        val originalBytes = Files.readAllBytes(blob)
        val published = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("sessionId", id); put("expectedRevision", revision(id)); put("displayName", "Frozen texture owner")
        })
        assertFalse(published.isError, published.text)
        val path = Path.of(published.structuredContent.getValue("path").jsonPrimitive.content)
        val frozenManifest = Files.readAllBytes(path.resolve("worldsmith.json"))
        val frozenId = published.structuredContent.getValue("id").jsonPrimitive.content
        val beforeRevision = revision(id)
        val detached = call("worldsmith_put_content_modules", buildJsonObject {
            put("sessionId", id); put("expectedRevision", beforeRevision)
            put("modules", JsonObject(emptyMap())); put("removeAssets", McpJson.encode(listOf(assetId)))
        })
        assertFalse(detached.isError, detached.text)
        assertEquals(beforeRevision + 1, detached.structuredContent.getValue("revision").jsonPrimitive.long)
        assertTrue(detached.structuredContent.getValue("assets").jsonArray.isEmpty())
        assertNull(sessions.find(id)!!.packId, "Detaching an asset invalidates only the mutable draft's publication binding")
        assertArrayEquals(originalBytes, Files.readAllBytes(blob))
        val recoveredSessions = WorkflowSessions(directory = root.resolve("drafts"))
        val recoveredTools = WorldsmithMcpTools(root.resolve("packs"), sessions = recoveredSessions)
        assertEquals(draft(id), draft(id, recoveredTools))
        assertTrue(recoveredSessions.find(id)!!.contentAssets.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            call("worldsmith_preview_texture_asset", buildJsonObject { put("sessionId", id); put("assetId", assetId) }, recoveredTools)
        }
        val incomplete = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("sessionId", id); put("expectedRevision", recoveredSessions.find(id)!!.revision); put("displayName", "Dangling texture draft")
        }, recoveredTools)
        assertTrue(incomplete.isError, "A repairable detached draft still needs referenced assets before the next publication")
        assertTrue(incomplete.structuredContent.getValue("diagnostics").jsonArray.any {
            it.jsonObject.getValue("code").jsonPrimitive.content == "CONTENT_ASSET_MISSING"
        })
        val retained = WorldsmithPackLoader.loadDirectory(path)
        assertEquals(frozenId, retained.computedId)
        assertArrayEquals(frozenManifest, Files.readAllBytes(path.resolve("worldsmith.json")))
        assertArrayEquals(originalBytes, retained.assets.getValue(assetId))
        assertTrue(WorldsmithPackValidator.validate(retained).none { it.severity == DiagnosticSeverity.ERROR })
        assertArrayEquals(originalBytes, Files.readAllBytes(blob), "Detaching a handle is not garbage collection")
    }

    @Test fun `unknown asset detach and stale detach CAS are atomic no mutation failures`() {
        val id = begin()
        val made = texture(id)
        val assetId = made.structuredContent.getValue("asset").jsonObject.getValue("id").jsonPrimitive.content
        val before = sessions.find(id)!!
        val persisted = Files.readAllBytes(root.resolve("drafts/$id.json"))
        val stored = before.contentAssets.getValue(assetId)
        val blob = root.resolve("content-assets").resolve(stored.path!!)
        val originalBytes = Files.readAllBytes(blob)
        assertThrows(IllegalArgumentException::class.java) {
            call("worldsmith_put_content_modules", buildJsonObject {
                put("sessionId", id); put("expectedRevision", before.revision); put("modules", JsonObject(emptyMap()))
                put("removeAssets", McpJson.encode(listOf(assetId, "f".repeat(64))))
            })
        }
        val stale = assertThrows(IllegalArgumentException::class.java) {
            call("worldsmith_put_content_modules", buildJsonObject {
                put("sessionId", id); put("expectedRevision", before.revision - 1); put("modules", JsonObject(emptyMap()))
                put("removeAssets", McpJson.encode(listOf(assetId)))
            })
        }
        assertTrue(stale.message!!.contains("DRAFT_REVISION_CONFLICT"))
        assertEquals(before, sessions.find(id))
        assertArrayEquals(persisted, Files.readAllBytes(root.resolve("drafts/$id.json")))
        assertArrayEquals(originalBytes, Files.readAllBytes(blob))
    }
}
