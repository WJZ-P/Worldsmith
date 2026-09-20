package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AbilityAuthoringTest {
    @TempDir lateinit var root: Path
    private val sessions by lazy { WorkflowSessions(directory = root.resolve("sessions")) }
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs"), sessions = sessions) }
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = tools.all().single { it.name == name }.handler(args)
    private fun library(source: String = "on start { state.calls = 1; wait 2; fx.message(\"The stone remembers.\"); }") =
        AbilityLibrary(programs = listOf(AbilityProgramDefinition("memory", "Memory", source)))

    @Test fun `framework and content contract expose real source authoring and registry signatures`() {
        val framework = call("worldsmith_get_content_framework").structuredContent
        assertEquals(10, framework.getValue("packFormat").jsonPrimitive.int)
        assertTrue(framework.getValue("programmableAbilities").jsonPrimitive.boolean)
        assertFalse(framework.getValue("abilityRuntime").jsonPrimitive.boolean)
        val contract = call("worldsmith_get_content_contract", buildJsonObject { put("module", "abilities") })
        assertFalse(contract.isError, contract.text)
        val data = contract.structuredContent
        assertEquals(1, data.getValue("schemaVersion").jsonPrimitive.int)
        val specs = data.getValue("capabilities").jsonArray.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(AbilityCapabilities.standard().specs.map { it.name }.toSet(), specs.keys)
        assertEquals(listOf("ENTITY", "NUMBER"), specs.getValue("combat.damage").jsonObject.getValue("arguments").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("projectile.emit" in specs && "shape.union" in specs && "signal.emit" in specs)
        assertEquals(listOf("NUMBER", "NUMBER"), specs.getValue("control.claim").jsonObject.getValue("arguments").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("NUMBER", specs.getValue("control.claim").jsonObject.getValue("result").jsonPrimitive.content)
        val text = data.getValue("contract").jsonPrimitive.content
        for (phrase in listOf("on start", "fn strike_at", "while", "projectile_hit", "invokes_ability", "not a list of built-in skill types"))
            assertTrue(phrase in text, phrase)
        val write = tools.all().single { it.name == WorldsmithWorkflow.WRITE_TOOL }
        assertTrue("abilities" in write.inputSchema.getValue("properties").jsonObject)
    }

    @Test fun `every standard function publishes its real ordered argument description through both MCP entry points`() {
        val registry = AbilityCapabilities.standard()
        val contract = call("worldsmith_get_content_contract", buildJsonObject { put("module", "abilities") }).structuredContent
            .getValue("capabilities").jsonArray
        val framework = call("worldsmith_get_content_framework").structuredContent.getValue("abilityCapabilities").jsonArray
        assertEquals(contract, framework)
        val byName = contract.associate { value -> value.jsonObject.let { it.getValue("name").jsonPrimitive.content to it } }
        assertEquals(registry.specs.map { it.name }.toSet(), byName.keys)
        for (spec in registry.specs) {
            assertTrue(spec.description.isNotBlank(), "Missing standard capability usage: ${spec.name}")
            assertTrue(spec.description.startsWith("${spec.name}("), "Description must name its argument order: ${spec.name}")
            val parameterText = spec.description.substringAfter('(').substringBefore(')').trim()
            val parameters = if (parameterText.isEmpty()) emptyList() else parameterText.split(',').map { it.trim() }
            assertEquals(spec.arguments.size, parameters.size, "Description argument order/count: ${spec.name}")
            assertTrue(parameters.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")) }, "Use concrete parameter names for ${spec.name}")
            val actual = byName.getValue(spec.name)
            assertEquals(spec.description, actual.getValue("description").jsonPrimitive.content, "MCP must not drop or synthesize usage for ${spec.name}")
            assertEquals(spec.arguments.map { it.name }, actual.getValue("arguments").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(spec.result.name, actual.getValue("result").jsonPrimitive.content)
            assertEquals(spec.version, actual.getValue("version").jsonPrimitive.int)
        }
    }

    @Test fun `SDK descriptions explain dynamic results bounds protected effects and owned lifetimes`() {
        val specs = call("worldsmith_get_content_contract", buildJsonObject { put("module", "abilities") }).structuredContent
            .getValue("capabilities").jsonArray.associate { value -> value.jsonObject.let {
                it.getValue("name").jsonPrimitive.content to it.getValue("description").jsonPrimitive.content
            } }
        val required = mapOf(
            "entity.position" to listOf("VECTOR", "null", "32"),
            "entity.alive" to listOf("ANY", "null", "LivingEntity", "projectiles"),
            "entity.health_ratio" to listOf("0..1", "return 0"),
            "entity.kind" to listOf("Unresolved", "other"),
            "list.get" to listOf("zero-based", "null", "negative/fractional"),
            "list.append" to listOf("original is unchanged", "Assign the result", "64"),
            "math.sin" to listOf("radians", "not degrees"),
            "math.cos" to listOf("radians", "not degrees"),
            "math.clamp" to listOf("minimum > maximum is an error"),
            "shape.sphere" to listOf("0..32 in Core", "16", "24"),
            "shape.box" to listOf("halfExtents", "not full dimensions", "16"),
            "shape.union" to listOf("1..16", "Combined Core bounds"),
            "world.entities" to listOf("32", "excluding self", "creative/spectator", "No implicit LOS", "half extent <=16", "center <=24"),
            "world.aim" to listOf("0.1..16", "endpoint", "never an entity handle or null"),
            "world.block" to listOf("worldsmith:content/<id>", "<=24", "error"),
            "combat.damage" to listOf("0..100", "native hit result", "PvP", "No implicit LOS"),
            "combat.heal" to listOf("True only if health increased", "no extra PvP/team"),
            "status.apply" to listOf("1..1200", "0..4", "Self works with PvP disabled", "unknown IDs are errors"),
            "control.claim" to listOf("MOVE+LOOK", "0..100", "1..1200", "owned handle", "first owner wins", "higher priority preempts", "8/instance", "128/level", "conversation"),
            "control.held" to listOf("this invocation", "expired", "preempted", "does not terminate"),
            "control.release" to listOf("this invocation", "still-owned native Path", "does not cancel"),
            "motion.stop" to listOf("Self-only", "preserving Y", "live control.claim token", "self players need no NPC token", "newer owner's Path"),
            "motion.navigate" to listOf("Self custom-creature only", "live control.claim token", "0.1..2", "not arrival", "Null/finished paths", "before moveTo", "owned by the token", "no prior route is replayed"),
            "motion.blink" to listOf("Self player-only", "0.1..8", "mounted/sleeping", "swept path"),
            "motion.push" to listOf("Adds", "magnitude <=2", "does not set or cap final velocity", "Self works with PvP disabled"),
            "fx.telegraph" to listOf("1..200", "amber/red/blue/white/green", "Lease", "8 per instance", "128 per level"),
            "fx.clear" to listOf("telegraph", "numeric owned lease", "does not clear entity/projectile handles, pose or caption", "Deferred block restoration"),
            "fx.sound" to listOf("volume 0..2", "pitch 0.5..2", "not heard", "No lifetime lease"),
            "fx.message" to listOf("<=256", "within 16", "no recipients", "no lifetime lease"),
            "fx.pose" to listOf("1..200", "idle/walk/chase/windup/strike/recovery", "Lease", "other hosts ignore", "latest unexpired write wins", "older live lease", "only the last pose lease ending resets idle"),
            "fx.caption" to listOf("<=128", "1..1200", "idle", "Boss-bar suffix", "not a player overlay", "latest unexpired write wins", "older live lease"),
            "projectile.emit" to listOf("0.01..2", "0..0.2", "1..200", "<=64", "No automatic damage", "event_entity (possibly null)", "Lease"),
            "signal.emit" to listOf("event_tag", "event_data", "including null", "absent handler", "full bounded queue"),
        )
        required.forEach { (name, phrases) -> phrases.forEach { phrase ->
            assertTrue(phrase in specs.getValue(name), "$name must explain '$phrase'")
        } }
        assertFalse("expiry/cancel resets idle" in specs.getValue("fx.pose"), "One lease ending must not clear another invocation's live pose")
        assertFalse("cleanup stops owned navigation" in specs.getValue("motion.navigate"), "Navigation cleanup must name current ownership, not every historical sender")
    }

    @Test fun `ability module retains actual source through CAS recovery and inline publication`() {
        val session = sessions.begin("A stone that remembers")
        val document = McpJson.encode(library()).jsonObject
        val args = buildJsonObject {
            put("sessionId", session.id); put("expectedRevision", session.revision)
            putJsonObject("modules") { put("abilities", document) }
        }
        val saved = call("worldsmith_put_content_modules", args)
        assertFalse(saved.isError, saved.text)
        val current = sessions.find(session.id)!!
        assertEquals(document, current.contentModules["abilities"])
        assertEquals(current, WorkflowSessions(directory = root.resolve("sessions")).find(session.id))
        assertThrows(IllegalArgumentException::class.java) { call("worldsmith_put_content_modules", args) }
        assertEquals(1, WorldGenerationProgress.inspect(current).counts["abilityDefinitions"])
        val template = call(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        assertTrue("abilities" in template)
        val written = call(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("displayName", "Memory source")
            listOf("terrain", "biomes", "features", "structures", "theme", "blocks", "creatures", "items", "quests", "mechanics").forEach { put(it, template.getValue(it)) }
            put("abilities", document)
        })
        assertFalse(written.isError, written.text)
        val pack = WorldsmithPackLoader.loadDirectory(Path.of(written.structuredContent.getValue("path").jsonPrimitive.content))
        assertEquals(library(), pack.abilities)
        assertTrue(written.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
    }

    @Test fun `actual bindings join design targets rather than narrative skill names`() {
        val base = WorldAuthoringPolicyFixtures.pack()
        val creatures = base.creatures.copy(schemaVersion = 4, creatures = base.creatures.creatures.map {
            it.copy(category = CreatureCategory.HOSTILE, ability = CreatureAbilityBinding("memory"))
        })
        val items = base.items.copy(schemaVersion = 3, items = base.items.items.map {
            it.copy(actions = listOf(ItemAction(effects = listOf(ItemEffect.RunProgram("memory")))))
        })
        val mechanics = WorldMechanicLibrary(mechanics = listOf(WorldMechanicDefinition("stone", "Stone", rules = listOf(WorldMechanicRule(
            "recall", WorldMechanicEvent.USE_BLOCK, listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:stone"))),
            listOf(MechanicAction.RunProgram("memory")))))))
        val candidate = base.copy(abilities = library(), creatures = creatures, items = items, mechanics = mechanics)
        val inventory = WorldDesignCoverage.draft(WorldAuthoringPolicyFixtures.session().copy(contentModules = ExistingWorldContentModules.input(candidate).modules))
        val ability = ContentKey("ability", "memory")
        val links = listOf(ContentKey("creature", "keeper"), ContentKey("item", "token"), ContentKey("mechanic", "stone"))
            .map { DesignLink(it, ability, DesignRelation.INVOKES_ABILITY) }
        assertTrue(ability in inventory.symbols)
        assertTrue(inventory.links.containsAll(links))
        val targets = (links.map { it.from } + ability).map { DesignTarget(it, it.id, "A source-backed action") }
        val plan = WorldDesignPlan(goal = "Shared source", targets = targets, links = links)
        assertTrue(WorldDesignPlans.validate(plan, completeWorld = false, requireBoss = false).isEmpty())
        assertFalse(WorldDesignCoverage.missing(plan, inventory, false).any { it.code in setOf("DESIGN_TARGET_MISSING", "DESIGN_LINK_MISSING", "DESIGN_ABILITY_UNBOUND") })
        assertTrue(WorldDesignCoverage.missing(plan, inventory.copy(links = emptySet()), false).any { it.code == "DESIGN_ABILITY_UNBOUND" })
    }

    @Test fun `one injected capability snapshot covers contracts progress save and archive readback`() {
        val capabilities = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("fixture.observe", arguments = listOf(AbilityType.ENTITY), result = AbilityType.BOOL, effect = true))
        val extended = WorldsmithMcpTools(root.resolve("extended/packs"), abilityCapabilities = capabilities)
        fun invoke(name: String, arguments: JsonObject = JsonObject(emptyMap())) = extended.all().single { it.name == name }.handler(arguments)
        val contract = invoke("worldsmith_get_content_contract", buildJsonObject { put("module", "abilities") }).structuredContent
        assertTrue(contract.getValue("capabilities").jsonArray.any { it.jsonObject["name"]?.jsonPrimitive?.content == "fixture.observe" })
        val source = library("on start { fixture.observe(self); }")
        val session = WorkflowSession("e".repeat(32), "Observe a remembered stone", contentModules = mapOf("abilities" to McpJson.encode(source).jsonObject))
        assertFalse(WorldGenerationProgress.inspect(session, abilityCapabilities = capabilities).issues.any { it.code == "ability.compile" })
        assertTrue(WorldGenerationProgress.inspect(session).issues.any { it.code == "ability.compile" })
        val template = invoke(WorldsmithWorkflow.TEMPLATE_TOOL).structuredContent
        val written = invoke(WorldsmithWorkflow.WRITE_TOOL, buildJsonObject {
            put("displayName", "Extended memory")
            listOf("terrain", "biomes", "features", "structures", "theme", "blocks", "creatures", "items", "quests", "mechanics").forEach { put(it, template.getValue(it)) }
            put("abilities", McpJson.encode(source))
        })
        assertFalse(written.isError, written.text)
        assertTrue(written.structuredContent.getValue("resourcePackReady").jsonPrimitive.boolean)
        val path = Path.of(written.structuredContent.getValue("resourcePack").jsonObject.getValue("path").jsonPrimitive.content)
        val restored = com.wjz.worldsmith.core.pack.WorldsmithResourceArchive.read(path, capabilities)
        assertEquals(source, restored.pack.abilities)
        assertThrows(IllegalArgumentException::class.java) { com.wjz.worldsmith.core.pack.WorldsmithResourceArchive.read(path) }
    }

    @Test fun `ability review exposes exact source root and source edits invalidate evidence`() {
        val base = WorldAuthoringPolicyFixtures.session()
        val target = ContentKey("ability", "memory")
        val brief = ModuleBrief("abilities", listOf(target), "The stone remembers events through executable source", listOf("node/shore"),
            criteria = listOf(BriefCriterion("source", "The source waits then speaks and stores invocation state", target)))
        val state = requireNotNull(base.authoring)
        val owned = state.copy(briefs = state.briefs + BriefRecord(brief, state.bibleRevision, WorldAuthoringPolicy.basisDigest(state, brief)))
        val plan = requireNotNull(base.designPlan)
        val current = base.copy(authoring = owned, contentModules = base.contentModules + ("abilities" to McpJson.encode(library()).jsonObject),
            designPlan = plan.copy(targets = plan.targets + DesignTarget(target, "Memory", "Source-backed remembering"),
                links = plan.links + DesignLink(ContentKey("theme", WorldAuthoringPolicyFixtures.pack().theme.id), target, DesignRelation.THEME_ANCHOR)))
        assertTrue(WorldAuthoringModel.validateBriefs(owned.briefs.map { it.brief }, owned.bible!!).isEmpty())
        val ready = WorldAuthoringPolicyFixtures.approveAll(current)
        assertTrue(WorldAuthoringPolicy.alignmentProblems(ready).isEmpty(), WorldAuthoringPolicy.alignmentProblems(ready).toString())
        val context = WorldAuthoringPolicy.reviewContext(ready, "abilities")
        assertEquals("/modules/abilities/programs/0", context.getValue("evidenceRoots").jsonObject.getValue("source").jsonArray.single().jsonPrimitive.content)
        val modified = ready.copy(contentModules = ready.contentModules + ("abilities" to McpJson.encode(library("on start { wait 12; }")).jsonObject))
        assertTrue(WorldAuthoringPolicy.alignmentProblems(modified).any { it.code == "AUTHORING_ALIGNMENT_REVIEW_STALE" })
        val noOwner = ready.copy(authoring = ready.authoring!!.copy(briefs = ready.authoring.briefs.filter { it.brief.id != "abilities" }))
        assertTrue(WorldAuthoringPolicy.productionProblems(noOwner, setOf(target)).any { it.code == "AUTHORING_BRIEF_TARGET_COVERAGE" })
    }
}
