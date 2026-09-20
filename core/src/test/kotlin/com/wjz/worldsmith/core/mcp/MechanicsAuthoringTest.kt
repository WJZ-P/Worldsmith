package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Typed authoring and provenance evidence, not a native interaction playtest. */
class MechanicsAuthoringTest {
    @TempDir lateinit var root: Path
    private val sessions by lazy { WorkflowSessions(directory = root.resolve("sessions")) }
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs"), sessions = sessions) }
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())) =
        tools.all().single { it.name == name }.handler(args)
    private fun example(name: String): JsonObject = Json.parseToJsonElement(Files.readString(
        Path.of(System.getProperty("worldsmith.projectRoot"), "docs/examples/mechanics/$name.json"))).jsonObject
    private fun sample() = WorldMechanicLibrary(mechanics = listOf(WorldMechanicDefinition(
        "exchange", "Memory exchange", rules = listOf(WorldMechanicRule("offer", WorldMechanicEvent.USE_BLOCK,
            listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:stone"))),
            listOf(MechanicAction.GiveItem("minecraft:emerald")), fromState = "idle", toState = "idle",
            heldItem = MechanicItemCost("minecraft:amethyst_shard", 4))))))

    @Test fun `framework contract and write schema expose the real event driven module`() {
        val capabilities = call("worldsmith_get_content_framework").structuredContent
        assertEquals(WorldContentBundleIO.FORMAT_VERSION, capabilities.getValue("packFormat").jsonPrimitive.int)
        val status = call("worldsmith_status").structuredContent
        assertEquals(WorldContentBundleIO.FORMAT_VERSION, status.getValue("packFormatVersion").jsonPrimitive.int)
        assertEquals(listOf(WorldContentBundleIO.FORMAT_VERSION), status.getValue("supportedPackFormats").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(status.getValue("readOnlyPackFormats").jsonArray.isEmpty())
        assertTrue(capabilities.getValue("installedModules").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "mechanics" })
        assertFalse(capabilities.getValue("mechanicsRuntime").jsonPrimitive.boolean)
        assertFalse(capabilities.getValue("arbitraryMechanicsScripts").jsonPrimitive.boolean)
        val budget = capabilities.getValue("authoringBudgets").jsonObject.getValue("mechanics").jsonObject
        assertEquals(WorldMechanicValidation.MAX_TOTAL_CELLS, budget.getValue("maxTotalPatternCells").jsonPrimitive.int)
        val result = call("worldsmith_get_content_contract", buildJsonObject { put("module", "mechanics") })
        assertFalse(result.isError)
        val contract = result.structuredContent.getValue("contract").jsonPrimitive.content
        for (phrase in listOf("BLOCK_PLACED", "USE_BLOCK", "last placed block may be any", "empty main hand", "Sneaking", "tick scripts", "WorldBible"))
            assertTrue(phrase in contract, phrase)
        val write = tools.all().single { it.name == WorldsmithWorkflow.WRITE_TOOL }
        assertTrue("mechanics" in write.inputSchema.getValue("properties").jsonObject)
        assertEquals(1, result.structuredContent.getValue("schemaVersion").jsonPrimitive.int)
    }

    @Test fun `all three documented devices use the same bounded schema and real authoring targets`() {
        val library = McpJson.decode<WorldMechanicLibrary>(example("mechanics"))
        assertTrue(WorldMechanicValidation.validate(library).isEmpty(), WorldMechanicValidation.validate(library).toString())
        assertEquals(3, library.mechanics.size)
        val summon = library.mechanics.first().rules.single()
        assertEquals(WorldMechanicEvent.BLOCK_PLACED, summon.event)
        assertNull(summon.heldItem)
        assertTrue(summon.rotateY)
        assertNotEquals(summon.fromState, summon.toState)
        assertEquals(1, summon.actions.filterIsInstance<MechanicAction.SpawnCreature>().size)
        val exchange = library.mechanics.last().rules.single()
        assertEquals(exchange.fromState, exchange.toState)
        assertEquals(4, exchange.heldItem!!.count)
        val authoring = example("authoring")
        val bible = McpJson.decode<WorldBible>(authoring.getValue("bible"))
        val briefs = McpJson.decode<List<ModuleBrief>>(authoring.getValue("briefs"))
        assertTrue(WorldAuthoringModel.validateBible(bible, authoring.getValue("originalPrompt").jsonPrimitive.content).isEmpty())
        assertTrue(WorldAuthoringModel.validateBriefs(briefs, bible).isEmpty())
        assertEquals(library.mechanics.map { ContentKey("mechanic", it.id) }.toSet(), briefs.flatMap { it.targets }.toSet())
    }

    @Test fun `mechanics draft uses shared CAS and restores the exact document without execution`() {
        val session = sessions.begin("A memory exchange")
        val document = McpJson.encode(sample()).jsonObject
        val arguments = buildJsonObject {
            put("sessionId", session.id); put("expectedRevision", session.revision)
            putJsonObject("modules") { put("mechanics", document) }
        }
        val saved = call("worldsmith_put_content_modules", arguments)
        assertFalse(saved.isError, saved.text)
        val current = sessions.find(session.id)!!
        assertEquals(document, current.contentModules.getValue("mechanics"))
        assertThrows(IllegalArgumentException::class.java) { call("worldsmith_put_content_modules", arguments) }
        assertEquals(current, sessions.find(session.id))
        assertEquals(current, WorkflowSessions(directory = root.resolve("sessions")).find(session.id))
        val progress = WorldGenerationProgress.inspect(current)
        assertEquals(1, progress.counts["mechanicDefinitions"])
    }

    @Test fun `inline publication and template round trip actual mechanics not a text promise`() {
        val template = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        assertTrue("mechanics" in template)
        val library = sample()
        val write = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("displayName", "Memory stones")
            listOf("terrain", "biomes", "features", "structures", "theme", "blocks", "creatures", "items", "quests").forEach { put(it, template.getValue(it)) }
            put("mechanics", McpJson.encode(library))
        })
        assertFalse(write.isError, write.text)
        val pack = WorldsmithPackLoader.loadDirectory(Path.of(write.structuredContent.getValue("path").jsonPrimitive.content))
        assertEquals(10, pack.manifest.formatVersion)
        assertEquals(library, pack.mechanics)
        assertEquals("mechanics.json", pack.manifest.modules.getValue("mechanics").path)
        assertTrue(write.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
    }

    @Test fun `coverage reflects typed mechanic costs material use rewards and summons`() {
        val library = sample().let { it.copy(mechanics = it.mechanics.map { mechanic -> mechanic.copy(rules = mechanic.rules.map { rule -> rule.copy(
            pattern = listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("worldsmith:content/stone"))),
            heldItem = MechanicItemCost("worldsmith:item/token", 2), toState = "active",
            actions = listOf(MechanicAction.GiveItem("worldsmith:item/token"), MechanicAction.SpawnCreature("keeper", MechanicOffset(0, 1, 0)))) }) }) }
        val base = WorldAuthoringPolicyFixtures.session()
        val inventory = WorldDesignCoverage.draft(base.copy(contentModules = base.contentModules + ("mechanics" to McpJson.encode(library).jsonObject)))
        val owner = ContentKey("mechanic", "exchange")
        val expected = setOf(DesignLink(owner, ContentKey("block", "stone"), DesignRelation.USES_BLOCK),
            DesignLink(owner, ContentKey("item", "token"), DesignRelation.CONSUMES_ITEM),
            DesignLink(owner, ContentKey("item", "token"), DesignRelation.GRANTS_ITEM),
            DesignLink(owner, ContentKey("creature", "keeper"), DesignRelation.SPAWNS_CREATURE))
        assertTrue(owner in inventory.symbols)
        assertTrue(inventory.links.containsAll(expected), inventory.links.toString())
        val plan = WorldDesignPlan(goal = "Connect the exchange", targets = (expected.flatMap { listOf(it.from, it.to) }.toSet()).map { DesignTarget(it, it.id, "Connected rule input or output") }, links = expected.toList())
        assertTrue(WorldDesignPlans.validate(plan, completeWorld = false, requireBoss = false).isEmpty())
        val missing = WorldDesignCoverage.draft(base)
        assertTrue(WorldDesignCoverage.missing(plan, missing, false).any { it.code == "DESIGN_TARGET_MISSING" })
        assertTrue(WorldDesignCoverage.missing(plan, missing, false).any { it.code == "DESIGN_LINK_MISSING" })
        assertTrue(WorldDesignCoverage.missing(plan, inventory, true).any { it.code == "DESIGN_MECHANIC_UNREACHABLE" })
        assertFalse(WorldDesignCoverage.missing(plan, inventory.copy(reachableMechanics = setOf("exchange")), true).any { it.code == "DESIGN_MECHANIC_UNREACHABLE" })
    }

    @Test fun `activation promise is covered only by an actual activate mechanic objective`() {
        val base = WorldAuthoringPolicyFixtures.session()
        val owner = ContentKey("mechanic", "exchange")
        val quests = McpJson.decode<QuestLibrary>(base.contentModules.getValue("quests"))
        val changed = quests.copy(quests = quests.quests.map { it.copy(objectives = listOf(QuestObjective.ActivateMechanic("exchange"))) })
        val session = base.copy(contentModules = base.contentModules + mapOf("mechanics" to McpJson.encode(sample()).jsonObject,
            "quests" to McpJson.encode(changed).jsonObject))
        val link = DesignLink(ContentKey("quest", quests.quests.single().id), owner, DesignRelation.ACTIVATION_OBJECTIVE)
        assertTrue(link in WorldDesignCoverage.draft(session).links)
        assertFalse(link in WorldDesignCoverage.draft(base).links)
        val plan = WorldDesignPlan(goal = "Open the machine", targets = listOf(link.from, link.to).map { DesignTarget(it, it.id, "Observe actual activation") }, links = listOf(link))
        assertTrue(WorldDesignPlans.validate(plan, completeWorld = false, requireBoss = false).isEmpty())
    }

    @Test fun `mechanic review binds actual rule roots and normalized defaults and becomes stale on cost change`() {
        val base = WorldAuthoringPolicyFixtures.session()
        val owner = ContentKey("mechanic", "exchange")
        val brief = ModuleBrief("mechanics", listOf(owner), "Trade remembered materials", listOf("node/shore"),
            criteria = listOf(BriefCriterion("cost", "Four held shards pay for one emerald", owner)))
        val state = requireNotNull(base.authoring)
        val withBrief = state.copy(briefs = state.briefs + BriefRecord(brief, state.bibleRevision, WorldAuthoringPolicy.basisDigest(state, brief)))
        val plan = requireNotNull(base.designPlan)
        val current = base.copy(authoring = withBrief, contentModules = base.contentModules + ("mechanics" to McpJson.encode(sample()).jsonObject),
            designPlan = plan.copy(targets = plan.targets + DesignTarget(owner, "Exchange", "Paid material exchange"),
                links = plan.links + DesignLink(ContentKey("theme", WorldAuthoringPolicyFixtures.pack().theme.id), owner, DesignRelation.THEME_ANCHOR)))
        val ready = WorldAuthoringPolicyFixtures.approveAll(current)
        assertTrue(WorldAuthoringPolicy.alignmentProblems(ready).isEmpty(), WorldAuthoringPolicy.alignmentProblems(ready).toString())
        val frozen = WorldAuthoringPolicyFixtures.pack().copy(mechanics = sample())
        assertTrue(WorldAuthoringPolicy.publicationProblems(ready, frozen).isEmpty(), WorldAuthoringPolicy.publicationProblems(ready, frozen).toString())
        val context = WorldAuthoringPolicy.reviewContext(ready, "mechanics")
        assertEquals("/modules/mechanics/mechanics/0", context.getValue("evidenceRoots").jsonObject.getValue("cost").jsonArray.single().jsonPrimitive.content)
        val normalized = McpJson.encode(sample()).jsonObject
        val defaultless = JsonObject(normalized + ("mechanics" to JsonArray(normalized.getValue("mechanics").jsonArray.map { value ->
            JsonObject(value.jsonObject - "initialState" - "states" - "description")
        })))
        val omitted = ready.copy(contentModules = ready.contentModules + ("mechanics" to defaultless))
        assertEquals(WorldAuthoringPolicy.contentDigest(ready, brief), WorldAuthoringPolicy.contentDigest(omitted, brief))
        val changed = sample().let { it.copy(mechanics = it.mechanics.map { m -> m.copy(rules = m.rules.map { r -> r.copy(heldItem = r.heldItem!!.copy(count = 5)) }) }) }
        val edited = ready.copy(contentModules = ready.contentModules + ("mechanics" to McpJson.encode(changed).jsonObject))
        assertTrue(WorldAuthoringPolicy.alignmentProblems(edited).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_STALE" })
        assertTrue(WorldAuthoringPolicy.publicationProblems(ready, frozen.copy(mechanics = changed)).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_STALE" })
        val readyState = requireNotNull(ready.authoring)
        val noOwner = ready.copy(authoring = readyState.copy(briefs = readyState.briefs.filter { it.brief.id != "mechanics" }))
        assertTrue(WorldAuthoringPolicy.productionProblems(noOwner, setOf(owner)).any { it.code == "AUTHORING_BRIEF_TARGET_COVERAGE" })
    }
}
