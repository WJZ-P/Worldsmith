package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.content.CreatureLibrary
import com.wjz.worldsmith.core.content.CustomItemLibrary
import com.wjz.worldsmith.core.content.QuestLibrary
import com.wjz.worldsmith.core.mcp.WorkflowMode
import com.wjz.worldsmith.core.mcp.WorkflowSession
import com.wjz.worldsmith.core.mcp.WorldAuthoringState
import com.wjz.worldsmith.core.mcp.WorldBible
import com.wjz.worldsmith.core.mcp.WorldBibleNode
import com.wjz.worldsmith.core.mcp.WorldBibleNodeKind
import com.wjz.worldsmith.core.mcp.WorldRequirement
import com.wjz.worldsmith.core.model.WorldsmithModuleFile
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.StructureLibrary
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Always-available classpath ingredients and deterministic in-memory files; no machine-local pack paths. */
class WorldAuthoringPackCompatibilityTest {
    private data class Version(val format: Int, val schemas: Map<String, Int>)

    // Formats 3/4 have seven/eight modules and schema-1 creatures/items. Format 5 adds quests
    // and supports schema-2 structures/creatures. Format 6 also supports items 2 and voices 3.
    private val seven = setOf("theme", "terrain", "features", "biomes", "structures", "blocks", "creatures")
    private val eight = seven + "items"
    private val nine = eight + "quests"
    private val versions = listOf(
        Version(3, seven.associateWith { 1 }),
        Version(4, eight.associateWith { 1 }),
        Version(5, nine.associateWith { if (it in setOf("structures", "creatures")) 2 else 1 }),
        Version(6, nine.associateWith { when (it) { "structures", "items" -> 2; "creatures" -> 3; else -> 1 } }),
    )

    /** The checked-in Ashlands source is currently format 5; these are derived format fixtures, not historical golden archives. */
    private fun fixture(version: Version): WorldContentBundleFiles {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val manifest = base.manifest.copy(formatVersion = version.format, id = "0".repeat(64),
            modules = version.schemas.mapValues { (name, schema) -> WorldsmithModuleFile(schema, "$name.json") },
            assets = emptyList(), representativeContent = null)
        return WorldContentBundleIO.encode(base.copy(manifest = manifest, computedId = manifest.id,
            structures = StructureLibrary(schemaVersion = version.schemas.getValue("structures")),
            creatures = CreatureLibrary(schemaVersion = version.schemas.getValue("creatures")),
            items = CustomItemLibrary(schemaVersion = version.schemas["items"] ?: 1), quests = QuestLibrary(), assets = emptyMap()))
    }

    private fun load(files: WorldContentBundleFiles, reads: MutableList<String> = mutableListOf()) =
        WorldsmithPackLoader.load(object : WorldsmithPackSource {
            override fun readText(relativePath: String): String {
                reads += relativePath
                return if (relativePath == "worldsmith.json") WorldsmithJson.encode(files.manifest) else files.texts.getValue(relativePath)
            }
            override fun readBytes(relativePath: String): ByteArray {
                reads += relativePath
                return files.binaries.getValue(relativePath).copyOf()
            }
        })

    @Test fun `the checked-in source retains its real declared identity without an external fixture`() {
        val source = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        assertEquals(source.manifest.id, source.computedId)
        val encoded = WorldContentBundleIO.encode(source)
        assertEquals(source.manifest.id, encoded.manifest.id)
        val restored = load(encoded)
        assertEquals(source.manifest.formatVersion, restored.manifest.formatVersion)
        assertEquals(source.manifest.modules, restored.manifest.modules)
        assertEquals(source.computedId, restored.computedId)
    }

    @Test fun `formats three through six preserve their exact module sets schemas and hash domains on reencoding`() {
        val identities = mutableSetOf<String>()
        for (version in versions) {
            val first = fixture(version)
            assertEquals(version.format, first.manifest.formatVersion)
            assertEquals(version.schemas, first.manifest.modules.mapValues { it.value.schemaVersion })
            assertEquals(version.schemas.keys.map { "$it.json" }.toSet(), first.texts.keys)
            first.manifest.modules.forEach { (name, module) ->
                val raw = WorldsmithJson.format.parseToJsonElement(first.texts.getValue(module.path)).jsonObject
                assertEquals(version.schemas.getValue(name), raw.getValue("schemaVersion").jsonPrimitive.int)
            }
            val loaded = load(first)
            assertEquals(first.manifest.id, loaded.computedId)
            val again = WorldContentBundleIO.encode(loaded)
            assertEquals(first.manifest, again.manifest)
            assertEquals(first.texts, again.texts)
            assertEquals(first.binaries.keys, again.binaries.keys)
            first.binaries.forEach { (path, bytes) -> assertArrayEquals(bytes, again.binaries.getValue(path)) }
            assertEquals(first.manifest.id, fixture(version).manifest.id, "The fixture is deterministic, not a random golden hash")
            identities += first.manifest.id
        }
        assertEquals(versions.size, identities.size, "Different version/hash domains retain different content identities")
    }

    @Test fun `session-only authoring metadata leaves every saved pack independently readable and identically encoded`() {
        val prompt = "A quiet survey of the Ashlands"
        val bible = WorldBible("Survey notes", "The survey follows the reclaimed land", "A patient surveyor", "Read the lost history",
            requirements = listOf(WorldRequirement("quiet", "The survey is quiet", "quiet survey")),
            nodes = listOf(WorldBibleNode("coast", WorldBibleNodeKind.REGION, "Reclaimed coast", "Old coasts expose the prior civilization")))
        for (version in versions) {
            val files = fixture(version)
            val legacy = WorkflowSession(version.format.toString().repeat(32), prompt, packId = files.manifest.id,
                finished = true, mode = WorkflowMode.COMPLETE_WORLD)
            val restoredLegacy = WorldsmithJson.decode<WorkflowSession>(WorldsmithJson.encode(legacy))
            assertNull(restoredLegacy.authoring)
            // This changes only a separate saved session record, never the immutable pack or its manifest.
            val withMetadata = restoredLegacy.copy(authoring = WorldAuthoringState(bible = bible, bibleRevision = 1))
            val restored = WorldsmithJson.decode<WorkflowSession>(WorldsmithJson.encode(withMetadata))
            assertEquals(files.manifest.id, restored.packId)
            assertEquals(bible, restored.authoring?.bible)
            assertNull(restored.authoring?.bibleReview, "Old pack loading requires no fabricated AI review")
            val reads = mutableListOf<String>()
            val loaded = load(files, reads)
            val encoded = WorldContentBundleIO.encode(loaded)
            assertEquals(restored.packId, loaded.computedId)
            assertEquals(files.manifest, encoded.manifest)
            assertEquals(files.texts, encoded.texts)
            assertEquals(version.schemas.keys, encoded.manifest.modules.keys)
            assertFalse(encoded.texts.values.any { it.contains("Survey notes") })
            assertEquals(setOf("worldsmith.json") + files.texts.keys + files.binaries.keys, reads.toSet())
            assertTrue(reads.none { it.contains("authoring") || it.contains("session") || it.contains("world_bible") },
                "The pack reader must not request session provenance or install an additional runtime module")
        }
    }
}
