package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.creatureauthoring.CreatureBuilder
import com.wjz.worldsmith.core.hash.WorldsmithHashUtil
import com.wjz.worldsmith.core.model.WorldsmithPack
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AbilityBundleTest {
    @TempDir lateinit var temp: Path

    private fun program(source: String = "on start { wait 2; fx.message(\"Echo\"); }") =
        AbilityProgramDefinition("echo", "Echo", source)

    private fun pack(library: AbilityLibrary = AbilityLibrary(programs = listOf(program()))): WorldsmithPack {
        val base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val builder = CreatureBuilder.create("keeper", "Keeper", CreatureCategory.HOSTILE).atlas(32, 32)
            .ability("echo", 7.0, 35, false).boss(CreatureBossProfile())
            .sounds(CreatureSoundProfile(CreatureVoice.IRON_GOLEM))
        builder.bone("body", null, 0f, 24f, 0f).cube("body", -2f, -8f, -2f, 4, 8, 4).end()
        val guide = builder.guide()
        val item = CustomItemDefinition("focus", "Focus", guide.asset.id, maxStackSize = 1,
            actions = listOf(ItemAction(effects = listOf(ItemEffect.RunProgram("echo")))))
        val mechanic = WorldMechanicDefinition("resonator", "Resonator", rules = listOf(WorldMechanicRule(
            "start", WorldMechanicEvent.USE_BLOCK,
            listOf(MechanicPatternCell(MechanicOffset(), MechanicBlockPredicate("minecraft:stone"))),
            listOf(MechanicAction.RunProgram("echo")))))
        return WorldContentBundleIO.create("Echo domain", "One source, three hosts", base.terrain, base.biomes,
            base.features, base.structures, base.theme, creatures = CreatureLibrary(4, listOf(guide.definition)),
            assets = mapOf(guide.asset.id to guide.png), items = CustomItemLibrary(3, listOf(item)),
            mechanics = WorldMechanicLibrary(mechanics = listOf(mechanic)), abilities = library)
    }

    @Test fun `required source module and all three invocation bindings survive round trip`() {
        val original = pack()
        val files = WorldContentBundleIO.encode(original)
        assertEquals(10, files.manifest.formatVersion)
        assertEquals("abilities/abilities.json", files.manifest.modulePath("abilities"))
        Files.writeString(temp.resolve("worldsmith.json"), WorldsmithJson.encode(files.manifest))
        files.texts.forEach { (path, text) -> temp.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        files.binaries.forEach { (path, bytes) -> temp.resolve(path).also { Files.createDirectories(it.parent); Files.write(it, bytes) } }
        val restored = WorldsmithPackLoader.loadDirectory(temp)
        assertEquals(original.computedId, restored.computedId)
        assertEquals(original.abilities, restored.abilities)
        assertEquals(original.creatures, restored.creatures)
        assertEquals(original.items, restored.items)
        assertEquals(original.mechanics, restored.mechanics)
        assertTrue(WorldsmithPackValidator.validate(restored).isEmpty(), WorldsmithPackValidator.validate(restored).toString())
        val plan = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(restored))
        assertTrue(plan.catalogValid, plan.diagnostics.toString())
        for (module in listOf("creatures", "items", "mechanics")) {
            assertTrue(plan.compileOrder.indexOf("abilities") < plan.compileOrder.indexOf(module))
            assertTrue(plan.catalog.entries.any { it.module == module && it.references.any { ref -> ref.target == ContentKey("ability", "echo") } })
        }
        assertEquals(4, CreatureSounds.requiredSchema(restored.creatures.creatures.single()))
        assertTrue(restored.creatures.creatures.single().boss!!.phases.isEmpty())
    }

    @Test fun `source changes and capability requirements alter immutable bundle identity`() {
        val original = pack()
        assertNotEquals(original.computedId, pack(AbilityLibrary(programs = listOf(program("on start { wait 3; fx.message(\"Echo\"); }")))).computedId)
        assertNotEquals(original.computedId, pack(AbilityLibrary(programs = listOf(program().copy(requires = mapOf("fx.message" to 1))))).computedId)
        val changed = original.copy(abilities = AbilityLibrary(programs = listOf(program("on start { wait 4; }"))))
        assertTrue(WorldsmithPackValidator.validate(changed).any { it.code == "PACK_CONTENT_MUTATED" })
        val encoded = WorldContentBundleIO.encode(original)
        assertThrows(IllegalArgumentException::class.java) {
            WorldsmithHashUtil.computeGenerationId(encoded.manifest, encoded.texts - encoded.manifest.modulePath("abilities"), encoded.binaries)
        }
    }

    @Test fun `old format and omitted module are rejected without editing input files`() {
        val original = pack()
        assertThrows(IllegalArgumentException::class.java) { WorldContentBundleIO.encode(original.copy(manifest = original.manifest.copy(formatVersion = 7))) }
        assertThrows(IllegalArgumentException::class.java) { WorldContentBundleIO.encode(original.copy(manifest = original.manifest.copy(modules = original.manifest.modules - "abilities"))) }
        val oldManifest = temp.resolve("worldsmith.json")
        val text = WorldsmithJson.encode(original.manifest.copy(formatVersion = 7))
        Files.writeString(oldManifest, text)
        assertThrows(IllegalArgumentException::class.java) { WorldsmithPackLoader.loadDirectory(temp) }
        assertEquals(text, Files.readString(oldManifest))
    }

    @Test fun `all host program references resolve and invalid source fails before publication`() {
        val missing = pack(AbilityLibrary())
        val errors = WorldsmithPackValidator.validate(missing).filter { it.code == "CONTENT_REFERENCE_MISSING" }
        assertEquals(3, errors.size, errors.toString())
        assertTrue(errors.any { it.path.endsWith("ability.program") })
        assertTrue(errors.any { it.path.endsWith("effects[0].program") })
        assertTrue(errors.any { it.path.endsWith("actions[0].program") })
        val broken = pack(AbilityLibrary(programs = listOf(program("on start { invented.attack(self); }"))))
        assertTrue(WorldsmithPackValidator.validate(broken).any { it.code == "ability.compile" })
    }

    @Test fun `extension signatures are passed to validation without changing pack serialization`() {
        val definition = program("on start { fixture.observe(self); }")
        val library = AbilityLibrary(programs = listOf(definition))
        val candidate = pack(library)
        val registry = AbilityCapabilities.standard().extend(AbilityCapabilitySpec("fixture.observe", arguments = listOf(AbilityType.ENTITY), result = AbilityType.BOOL, effect = true))
        assertTrue(WorldsmithPackValidator.validate(candidate).any { it.code == "ability.compile" })
        assertTrue(WorldsmithPackValidator.validate(candidate, registry).isEmpty(), WorldsmithPackValidator.validate(candidate, registry).toString())
        assertEquals(candidate.computedId, WorldContentBundleIO.encode(candidate).manifest.id)
    }

    @Test fun `program definitions and capability maps are deeply frozen at pack boundary`() {
        val requires = mutableMapOf("fx.message" to 1)
        val programs = mutableListOf(program().copy(requires = requires))
        val original = pack(AbilityLibrary(programs = programs))
        requires["fx.message"] = 99
        programs.clear()
        assertEquals(1, original.abilities.programs.size)
        assertEquals(1, original.abilities.programs.single().requires["fx.message"])
        assertThrows(UnsupportedOperationException::class.java) { (original.abilities.programs as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (original.abilities.programs.single().requires as MutableMap)["fx.message"] = 99 }
        assertEquals(original.computedId, WorldContentBundleIO.encode(original).manifest.id)
    }

    @Test fun `host schemas and atomic use restrictions are explicit`() {
        val original = pack()
        assertTrue(CustomCreatureValidator.validate(original.creatures.copy(schemaVersion = 3)).any { it.path.endsWith(".ability") })
        assertTrue(CustomItemValidation.validate(original.items.copy(schemaVersion = 2)).any { it.code == "items.schema" })
        val item = original.items.items.single()
        val action = item.actions.single()
        val invalid = listOf(
            item.copy(consumable = ItemConsumable()),
            item.copy(actions = listOf(action.copy(trigger = ItemActionTrigger.MELEE_HIT))),
            item.copy(actions = listOf(action.copy(effects = action.effects + ItemEffect.Heal(1f)))),
        )
        invalid.forEach { assertTrue(CustomItemValidation.validate(CustomItemLibrary(3, listOf(it))).any { d -> d.code == "items.program_action" }) }
        val creature = original.creatures.creatures.single()
        assertTrue(CreatureBosses.validate(creature.copy(ability = null), "creature").any { it.path.endsWith(".phases") })
        assertTrue(CustomCreatureValidator.validate(original.creatures.copy(creatures = listOf(creature.copy(
            ability = creature.ability!!.copy(range = Double.NaN, cooldownTicks = 0))))).size >= 2)
        val mechanic = original.mechanics.mechanics.single()
        val duplicate = mechanic.copy(rules = mechanic.rules.map { it.copy(actions = it.actions + MechanicAction.RunProgram("echo")) })
        assertTrue(WorldMechanicValidation.validate(WorldMechanicLibrary(mechanics = listOf(duplicate))).any { it.code == "mechanics.program_count" })
    }
}
