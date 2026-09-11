package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Small deterministic proofs only: no drawing worker, game reload or generated-world sampling. */
class WorldGenerationPolishTest {
    @TempDir lateinit var root: Path
    private val base by lazy { WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands") }
    private val relic = ContentKey("item", "relic")
    private val emptyInventory = DesignInventory(emptySet(), emptySet(), emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())
    private fun delivery(count: Int) = QuestObjective.DeliverItem("worldsmith:item/relic", count)
    private fun quest(id: String, previous: String? = null, objectives: List<QuestObjective> = listOf(delivery(1)), rewards: List<QuestReward> = emptyList()) =
        Quest(id, id, "A focused reachability fixture", previous?.let(::listOf).orEmpty(), objectives, rewards)
    private fun diagnostics(quests: List<Quest>, inventory: DesignInventory = emptyInventory) =
        WorldQuestReachability.validate(base.copy(quests = QuestLibrary(quests = quests)), inventory)

    @Test fun `earlier rewards are consumed across repeated objectives in graph order`() {
        val first = quest("first", objectives = listOf(QuestObjective.DeliverItem("minecraft:stick")), rewards = listOf(QuestReward("worldsmith:item/relic", 5)))
        val second = quest("second", "first", listOf(delivery(4), delivery(2)))
        val error = diagnostics(listOf(second, first)).single()
        assertEquals("DESIGN_DELIVERY_FINITE_SUPPLY_INSUFFICIENT", error.code)
        assertEquals("quests.quests[0].objectives[1]", error.path)
        assertTrue(error.message.contains("at most 1 unconsumed"))
        assertTrue(diagnostics(listOf(first, second.copy(objectives = listOf(delivery(3), delivery(2))))).isEmpty())
    }

    @Test fun `self and future rewards are not delivery inputs`() {
        assertEquals("DESIGN_DELIVERY_SELF_LOCK", diagnostics(listOf(quest("self", rewards = listOf(QuestReward("worldsmith:item/relic"))))).single().code)
        assertEquals("DESIGN_DELIVERY_FUTURE_LOCK", diagnostics(listOf(quest("first"), quest("later", "first",
            listOf(QuestObjective.DeliverItem("minecraft:stick")), listOf(QuestReward("worldsmith:item/relic"))))).single().code)
    }

    private fun structureWorld(anchorPlacement: AnchorPlacement = AnchorPlacement.Fixed(0, 0), chance: Double = 1.0): WorldsmithPack {
        val chest = StructureBlueprint(id = "supply", size = BuildPos(1, 1, 1), palette = mapOf("chest" to BuildMaterial("minecraft:chest")),
            build = listOf(BuildOperation.SetBlock("chest", BuildPos(0, 0, 0), "chest")), variation = StructureVariation(count = 2),
            interactions = listOf(StructureInteraction.Container(BuildPos(0, 0, 0), items = listOf(StructureItem(0, "worldsmith:item/relic", 2)))))
        val shape = (base.terrain.shape as TerrainShape.Procedural).copy(anchors = listOf(Anchor("supply", anchorPlacement, 100, 0.0)))
        val structure = WorldStructureDefinition("supply", chest, StructurePlacement(listOf(base.biomes.biomes.first().id),
            anchor = StructureAnchorTarget("supply"), region = StructureRegion("supply", chance = chance)))
        return base.copy(terrain = base.terrain.copy(shape = shape), structures = StructureLibrary(structures = listOf(structure)),
            quests = QuestLibrary(quests = listOf(quest("first"), quest("second", "first", listOf(delivery(2))))))
    }

    @Test fun `fixed and line structure stock is finite and alternatives are not summed`() {
        for (placement in listOf(AnchorPlacement.Fixed(0, 0), AnchorPlacement.Line(0, 0, 100, 0))) {
            val pack = structureWorld(placement)
            val inventory = WorldDesignCoverage.frozen(pack)
            assertEquals(2L, inventory.structureSupplies.getValue("supply").getValue(relic))
            val error = WorldQuestReachability.validate(pack, inventory).single()
            assertEquals("DESIGN_DELIVERY_FINITE_SUPPLY_INSUFFICIENT", error.code)
            assertTrue(error.message.contains("at most 1 unconsumed"))
        }
    }

    @Test fun `scattered structures repeat but zero chance is not a source`() {
        val repeatable = structureWorld(AnchorPlacement.Scattered(1024))
        assertTrue(WorldQuestReachability.validate(repeatable, WorldDesignCoverage.frozen(repeatable)).isEmpty())
        val disabled = structureWorld(chance = 0.0)
        assertEquals("DESIGN_DELIVERY_NO_PRODUCER", WorldQuestReachability.validate(disabled, WorldDesignCoverage.frozen(disabled)).single().code)
    }

    @Test fun `planned item production is checked even without a quest line`() {
        assertEquals("DESIGN_ITEM_NO_REACHABLE_PRODUCER", WorldQuestReachability.validate(base.copy(quests = QuestLibrary()), emptyInventory, setOf(relic)).single().code)
    }

    @Test fun `only enabled compiled Boss encounters add a repeatable creature route`() {
        val source = structureWorld()
        val definition = source.structures.structures.single()
        val spawner = definition.copy(blueprint = definition.blueprint.copy(palette = mapOf("spawner" to BuildMaterial("minecraft:spawner")),
            build = listOf(BuildOperation.SetBlock("spawner", BuildPos(0, 0, 0), "spawner")),
            interactions = listOf(StructureInteraction.BossSpawner(BuildPos(0, 0, 0), "warden"))))
        val warden = CreatureDefinition("warden", "Warden", CreatureCategory.HOSTILE,
            CreatureModel("0".repeat(64), 16, 16, listOf(CreatureBone("body", cubes = listOf(CreatureCube(CreatureVector(), CreatureVector(1f, 1f, 1f)))))),
            drops = listOf(CreatureDrop("worldsmith:item/relic")),
            boss = CreatureBossProfile(listOf(CreatureBossPhase("First", 1.0), CreatureBossPhase("Second", 0.5, speedMultiplier = 1.2))))
        val pack = source.copy(structures = StructureLibrary(schemaVersion = 2, structures = listOf(spawner)),
            creatures = CreatureLibrary(schemaVersion = 2, creatures = listOf(warden)),
            quests = QuestLibrary(quests = listOf(quest("slay", objectives = listOf(QuestObjective.KillCreature("warden"))), quest("deliver", "slay", listOf(delivery(1024))))))
        val inventory = WorldDesignCoverage.frozen(pack)
        assertTrue(inventory.naturalCreatures.isEmpty())
        assertEquals(setOf("warden"), inventory.structureCreatures)
        assertTrue(DesignLink(ContentKey("structure", "supply"), ContentKey("creature", "warden"), DesignRelation.CONTAINS_ENCOUNTER) in inventory.links)
        assertTrue(WorldQuestReachability.validate(pack, inventory).isEmpty())
        val disabled = pack.copy(structures = pack.structures.copy(structures = listOf(spawner.copy(placement = spawner.placement.copy(region = StructureRegion("supply", chance = 0.0))))))
        assertEquals("DESIGN_KILL_TARGET_UNPLACED", WorldQuestReachability.validate(disabled, WorldDesignCoverage.frozen(disabled)).first().code)
    }

    @Test fun `receipt is bounded durable nonmutating stale aware and cleared by success`() {
        val directory = root.resolve("sessions")
        val sessions = WorkflowSessions(directory = directory)
        val initial = sessions.begin("Receipt fixture", WorkflowMode.STANDALONE)
        val diagnostics = List(40) { Diagnostic("features.features[$it].density", "DENSITY_OUT_OF_RANGE", DiagnosticSeverity.ERROR, "x".repeat(5000), hint = "y".repeat(2000)) }
        val receipt = PackValidationReceipt.bounded(initial.revision, diagnostics, "Receipt", "", listOf("features"))
        val saved = requireNotNull(sessions.recordWriteFailureAtRevision(initial.id, receipt))
        assertEquals(initial.revision, saved.revision)
        assertEquals(32, saved.lastWriteFailure!!.diagnostics.size)
        assertEquals(40, saved.lastWriteFailure!!.diagnosticCount)
        assertEquals(1536, saved.lastWriteFailure!!.diagnostics.first().message.length)
        assertEquals(saved, WorkflowSessions(directory = directory).find(initial.id))
        val progress = WorldGenerationProgress.inspect(saved)
        assertEquals("FROZEN_REPAIR", progress.stage)
        assertEquals("worldsmith_put_content_modules", progress.nextTool)
        assertEquals(listOf("modules.features"), progress.requiredAuthoring)
        assertTrue(progress.writeFailureCurrent)
        assertTrue(progress.nextInstruction.contains("unsaved inline inputs"))
        val edited = requireNotNull(sessions.invalidate(initial.id))
        assertFalse(WorldGenerationProgress.inspect(edited).writeFailureCurrent)
        assertNull(sessions.recordWriteFailureAtRevision(initial.id, receipt))
        assertNull(sessions.recordPack(initial.id, "1".repeat(64))!!.lastWriteFailure)
    }

    @Test fun `write failure returns the same repair path as later progress`() {
        val sessions = WorkflowSessions(directory = root.resolve("sessions"))
        val session = sessions.begin("Hydrology receipt", WorkflowMode.STANDALONE)
        val tools = WorldsmithMcpTools(root.resolve("packs"), sessions = sessions)
        val written = tools.all().single { it.name == WorldsmithWorkflow.WRITE_TOOL }.handler(buildJsonObject {
            put("sessionId", session.id); put("expectedRevision", session.revision); put("displayName", "Hydrology receipt")
            put("terrain", buildJsonObject { put("shape", buildJsonObject { put("kind", "procedural") }) })
        })
        assertTrue(written.isError)
        assertTrue(written.structuredContent.getValue("writeFailureRecorded").jsonPrimitive.boolean)
        assertEquals("worldsmith_put_content_modules", written.structuredContent.getValue("nextTool").jsonPrimitive.content)
        val restored = WorldGenerationProgress.inspect(sessions.find(session.id)!!)
        assertEquals(listOf("modules.terrain"), restored.requiredAuthoring)
        assertEquals("MISSING_HYDROLOGY", restored.issues.first().code)
        assertEquals(session.revision, restored.revision)
    }
}
