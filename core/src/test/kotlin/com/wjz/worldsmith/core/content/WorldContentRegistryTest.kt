package com.wjz.worldsmith.core.content

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.structure.StructureLibrary
import com.wjz.worldsmith.core.structure.StructurePlacement
import com.wjz.worldsmith.core.structure.StructureBlueprint
import com.wjz.worldsmith.core.structure.StructureDrawingSource
import com.wjz.worldsmith.core.structure.WorldStructureDefinition
import com.wjz.worldsmith.core.drawhost.DrawingArtifact
import com.wjz.worldsmith.core.drawhost.DrawingSourceRecord
import com.wjz.worldsmith.core.mcp.StructureTestWorld
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldContentRegistryTest {
    private val document = buildJsonObject { put("schemaVersion", 1) }
    private fun module(id: String, kind: String = id, after: List<String> = emptyList(),
        requirements: List<ContentRequirement> = emptyList(), entries: List<ContentEntry> = listOf(ContentEntry(ContentKey(kind, "one"), id, id)),
        assets: List<ContentAsset> = emptyList()) = object : WorldContentModule {
        override val descriptor = ContentModuleDescriptor(id, listOf(kind), listOf(1), after, requirements, "test module")
        override fun describe(document: JsonObject) = ContentContribution(entries, assets)
    }

    @Test fun `existing packs join a deterministic catalog without changing format or identity`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val identity = pack.manifest.id
        val registry = ExistingWorldContentModules.registry()
        val input = ExistingWorldContentModules.input(pack)
        val result = registry.plan(input, ExistingWorldContentModules.nativeCapabilities())
        assertTrue(result.catalogValid, result.diagnostics.toString())
        assertTrue(result.capabilitiesSatisfied)
        assertEquals(listOf("blocks", "terrain", "features", "biomes", "creatures", "structures", "theme"), result.compileOrder)
        assertEquals(pack.biomes.biomes.size, result.catalog.entries.count { it.key.kind == "biome" })
        assertEquals(pack.features.features.size, result.catalog.entries.count { it.key.kind == "feature" })
        assertTrue(result.catalog.entries.any { entry -> entry.nativeReferences.any { it.id == "minecraft:stone" } })
        assertEquals(result, registry.plan(input.copy(modules = input.modules.entries.reversed().associate { it.toPair() }), ExistingWorldContentModules.nativeCapabilities()))
        assertEquals(identity, WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").manifest.id)
        assertEquals(pack.manifest.formatVersion, WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands").manifest.formatVersion)
    }

    @Test fun `legacy blueprint names are scoped by their owning structure`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val original = StructureTestWorld.fixture().structures.first()
        val placement = StructurePlacement(listOf(base.biomes.biomes.first().id))
        val library = StructureLibrary(structures = listOf(original.copy(id = "first", placement = placement), original.copy(id = "second", placement = placement)))
        val plan = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(base.copy(structures = library)))
        assertTrue(plan.catalogValid, plan.diagnostics.toString())
        assertTrue(plan.catalog.entries.any { it.key == ContentKey("blueprint", "first/${original.blueprint.id}") })
        assertTrue(plan.catalog.entries.any { it.key == ContentKey("blueprint", "second/${original.blueprint.id}") })
    }

    @Test fun `frozen drawing descriptors link into the shared asset catalog without pretending to decode bytes`() {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val artifact = DrawingArtifact("a".repeat(64), "b".repeat(64), "c".repeat(64), 0, emptyMap(), 1, "session", "job", "drawing", 1)
        val blueprint = StructureBlueprint(id = "frozen", drawing = StructureDrawingSource(listOf(artifact.id)))
        val definition = WorldStructureDefinition("frozen", blueprint, StructurePlacement(listOf(base.biomes.biomes.first().id)))
        val library = StructureLibrary(schemaVersion = 2, structures = listOf(definition), artifacts = mapOf(artifact.id to artifact),
            sources = mapOf(artifact.sourceHash to DrawingSourceRecord(artifact.sourceHash, "Example", mapOf("Example.java" to "source must not appear in the catalog input"))))
        val input = ExistingWorldContentModules.input(base.copy(structures = library))
        assertFalse(input.modules.toString().contains("source must not appear"))
        assertEquals(1, library.sources.size, "The original archive remains intact")
        val plan = ExistingWorldContentModules.registry().plan(input)
        assertTrue(plan.catalogValid, plan.diagnostics.toString())
        assertEquals(artifact.dataHash, plan.catalog.assets.single().sha256)
        assertEquals(artifact.path, plan.catalog.assets.single().path)
        assertNull(plan.catalog.assets.single().byteLength)
        assertTrue(plan.catalog.entries.any { it.key == ContentKey("drawing", artifact.id) })
        assertTrue(plan.catalog.entries.single { it.key.kind == "blueprint" }.references.any { it.target == ContentKey("drawing", artifact.id) })
    }

    @Test fun `runtime reference cycles are valid while compile dependency cycles are diagnosed`() {
        val left = ContentEntry(ContentKey("left", "one"), "left", "left", listOf(ContentReference(ContentKey("right", "one"), "left.target")))
        val right = ContentEntry(ContentKey("right", "one"), "right", "right", listOf(ContentReference(ContentKey("left", "one"), "right.target")))
        val input = WorldContentInput("world", mapOf("left" to document, "right" to document))
        val valid = WorldContentRegistry(listOf(module("left", entries = listOf(left)), module("right", entries = listOf(right)))).plan(input)
        assertTrue(valid.catalogValid)
        val cycle = WorldContentRegistry(listOf(module("left", after = listOf("right"), entries = listOf(left)), module("right", after = listOf("left"), entries = listOf(right)))).plan(input)
        assertTrue(cycle.diagnostics.any { it.code == "CONTENT_COMPILE_CYCLE" })
        assertFalse(cycle.catalogValid)
    }

    @Test fun `unknown modules schemas missing dependencies and wrong ownership never disappear silently`() {
        val registry = WorldContentRegistry(listOf(module("first", after = listOf("second"))))
        val plan = registry.plan(WorldContentInput("world", mapOf("first" to document, "blocks" to document)))
        assertTrue(plan.diagnostics.any { it.code == "CONTENT_MODULE_UNAVAILABLE" })
        assertTrue(plan.diagnostics.any { it.code == "CONTENT_MODULE_DEPENDENCY_MISSING" })
        val schema = registry.plan(WorldContentInput("world", mapOf("first" to buildJsonObject { put("schemaVersion", 99) })))
        assertTrue(schema.diagnostics.any { it.code == "CONTENT_SCHEMA_UNSUPPORTED" })
        val owner = WorldContentRegistry(listOf(module("first", entries = listOf(ContentEntry(ContentKey("second", "one"), "second", "bad"))))).plan(WorldContentInput("world", mapOf("first" to document)))
        assertTrue(owner.diagnostics.any { it.code == "CONTENT_WRONG_OWNER" })
    }

    @Test fun `references stay in one world and native dependencies are not mistaken for local symbols`() {
        val node = ContentEntry(ContentKey("things", "one"), "things", "things", listOf(ContentReference(ContentKey("things", "two"), "things.target")), nativeReferences = listOf(NativeContentReference("block", "minecraft:stone")))
        val registry = WorldContentRegistry(listOf(module("things", entries = listOf(node))))
        val plan = registry.plan(WorldContentInput("world_b", mapOf("things" to document)))
        assertEquals("things.target", plan.diagnostics.single().path)
        assertEquals("CONTENT_REFERENCE_MISSING", plan.diagnostics.single().code)
        assertEquals("minecraft:stone", plan.catalog.entries.single().nativeReferences.single().id)
        val optional = WorldContentRegistry(listOf(module("things", entries = listOf(node.copy(references = node.references.map { it.copy(required = false) }))))).plan(WorldContentInput("world_a", mapOf("things" to document)))
        assertTrue(optional.catalogValid)
    }

    @Test fun `asset descriptors are deduplicated but conflicts and missing assets are errors`() {
        val asset = ContentAsset("skin", "a".repeat(64), "image/png", 32, "assets/skin.png")
        val node = ContentEntry(ContentKey("visual", "one"), "visual", "visual", assets = listOf("skin"))
        val registry = WorldContentRegistry(listOf(module("visual", entries = listOf(node), assets = listOf(asset))))
        val input = WorldContentInput("world", mapOf("visual" to document), listOf(asset))
        assertEquals(1, registry.plan(input).catalog.assets.size)
        assertTrue(registry.plan(input).catalogValid)
        val conflict = registry.plan(input.copy(assets = listOf(asset.copy(sha256 = "b".repeat(64)))))
        assertTrue(conflict.diagnostics.any { it.code == "CONTENT_ASSET_CONFLICT" })
        val missing = WorldContentRegistry(listOf(module("visual", entries = listOf(node)))).plan(input.copy(assets = emptyList()))
        assertTrue(missing.diagnostics.any { it.code == "CONTENT_ASSET_MISSING" })
        val unsafe = asset.copy(id = "other", path = "../outside.png")
        assertFalse(registry.plan(input.copy(assets = listOf(unsafe))).catalogValid)
    }

    @Test fun `capability versions and bootstrap requirements are separate from valid content`() {
        val requirement = ContentRequirement("entities.hosts", 2, ContentLifecycle.BOOTSTRAP)
        val registry = WorldContentRegistry(listOf(module("actors", requirements = listOf(requirement))))
        val input = WorldContentInput("world", mapOf("actors" to document))
        val unavailable = registry.plan(input, mapOf("entities.hosts" to 1))
        assertTrue(unavailable.catalogValid)
        assertFalse(unavailable.capabilitiesSatisfied)
        assertEquals(listOf(requirement), unavailable.missingCapabilities)
        assertTrue(registry.plan(input, mapOf("entities.hosts" to 2)).capabilitiesSatisfied)
    }

    @Test fun `duplicate symbols and oversized entry sets fail without overwriting earlier definitions`() {
        val one = ContentEntry(ContentKey("things", "one"), "things", "original")
        val duplicate = WorldContentRegistry(listOf(module("things", entries = listOf(one, one.copy(path = "later"))))).plan(WorldContentInput("world", mapOf("things" to document)))
        assertFalse(duplicate.catalogValid)
        assertEquals("original", duplicate.catalog.entries.single().path)
        val many = WorldContentRegistry(listOf(module("things", entries = List(WorldContentRegistry.MAX_ENTRIES + 1) { one }))).plan(WorldContentInput("world", mapOf("things" to document)))
        assertTrue(many.diagnostics.any { it.code == "CONTENT_CATALOG_LIMIT" })
    }

    @Test fun `bounded diagnostic samples retain errors that occur after many warnings`() {
        val entries = (0..300).map { i -> ContentEntry(ContentKey("many", "entry_$i"), "many", "many[$i]",
            listOf(ContentReference(ContentKey("many", "missing"), "many[$i].target", required = i == 300))) }
        val plan = WorldContentRegistry(listOf(module("many", entries = entries))).plan(WorldContentInput("world", mapOf("many" to document)))
        assertEquals(256, plan.diagnostics.size)
        assertEquals(301, plan.totalDiagnostics)
        assertEquals(1, plan.errorCount)
        assertFalse(plan.catalogValid)
    }

    @Test fun `symbol allocation from a previous world never resolves a later worlds missing definition`() {
        val implementation = object : WorldContentModule {
            override val descriptor = ContentModuleDescriptor("things", listOf("thing"), listOf(1), description = "test")
            override fun describe(document: JsonObject): ContentContribution {
                val entries = mutableListOf(ContentEntry(ContentKey("thing", "one"), "things", "one", listOf(ContentReference(ContentKey("thing", "two"), "one.target"))))
                if (document["includeTwo"]?.jsonPrimitive?.boolean == true) entries += ContentEntry(ContentKey("thing", "two"), "things", "two")
                return ContentContribution(entries)
            }
        }
        val registry = WorldContentRegistry(listOf(implementation))
        assertTrue(registry.plan(WorldContentInput("world_a", mapOf("things" to buildJsonObject { put("schemaVersion", 1); put("includeTwo", true) }))).catalogValid)
        assertFalse(registry.plan(WorldContentInput("world_b", mapOf("things" to document))).catalogValid)
    }
}
