package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityCapabilities
import com.wjz.worldsmith.core.ability.AbilityCompiler
import com.wjz.worldsmith.core.ability.AbilityPrograms
import com.wjz.worldsmith.core.content.ContentKey
import com.wjz.worldsmith.core.content.ExistingWorldContentModules
import com.wjz.worldsmith.core.mcp.DesignLink
import com.wjz.worldsmith.core.mcp.DesignRelation
import com.wjz.worldsmith.core.mcp.WorldDesignCoverage
import com.wjz.worldsmith.core.pack.WorldContentBundleIO
import com.wjz.worldsmith.core.pack.WorldsmithResourceArchive
import com.wjz.worldsmith.core.structure.StructureInteraction
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Compile/link/portable-file evidence only. Native gameplay remains a separate server fixture. */
class AbilityGameplayExampleTest {
    @TempDir lateinit var temp: Path

    @Test fun `three actual programs compile against registered world resource and coordination primitives`() {
        val library = AbilityGameplayExample.programs()
        assertEquals(setOf(AbilityGameplayExample.CONDUCTOR, AbilityGameplayExample.BUILDER, AbilityGameplayExample.WITNESS), library.programs.map { it.id }.toSet())
        assertTrue(AbilityPrograms.validate(library).isEmpty(), AbilityPrograms.validate(library).toString())
        val compiled = library.programs.associate { it.id to AbilityCompiler.compile(it, AbilityCapabilities.standard()) }
        val parent = compiled.getValue(AbilityGameplayExample.CONDUCTOR).usedCapabilities.keys
        assertTrue(parent.containsAll(listOf("resource.define", "resource.pay", "combat.guard", "program.start", "scene.join", "shared.actor_set")))
        assertTrue(compiled.getValue(AbilityGameplayExample.BUILDER).usedCapabilities.keys.containsAll(listOf("world.set_block", "world.restore", "signal.send")))
        assertTrue(compiled.getValue(AbilityGameplayExample.WITNESS).usedCapabilities.keys.containsAll(listOf("shared.scene_get", "shared.scene_set", "scene.emit", "signal.send")))
        library.programs.forEach { assertEquals(AbilityGameplayExample.source(it.id), it.source) }
        val source = library.programs.single { it.id == AbilityGameplayExample.CONDUCTOR }.source
        assertTrue("program.start(\"${AbilityGameplayExample.BUILDER}\"" in source)
        assertTrue("program.start(\"${AbilityGameplayExample.WITNESS}\"" in source)
        assertTrue("on damage_guarded" in source && "on signal" in source)
    }

    @Test fun `an obtainable item uses the real new event binding and keeps all inherited content`() {
        val original = AbilityRuntimeExample.create()
        val pack = AbilityGameplayExample.create()
        assertEquals(10, pack.manifest.formatVersion)
        assertEquals(4, pack.items.schemaVersion)
        assertEquals(original.abilities.programs.size + 3, pack.abilities.programs.size)
        val item = pack.items.items.single { it.id == AbilityGameplayExample.SIGIL }
        assertTrue(item.actions.isEmpty(), "One physical input must not have two competing activation models")
        assertEquals(listOf("use_start"), item.abilityBindings.single().startOn)
        assertEquals(AbilityGameplayExample.CONDUCTOR, item.abilityBindings.single().program)
        assertTrue(pack.structures.structures.flatMap { it.blueprint.interactions }.filterIsInstance<StructureInteraction.Container>()
            .any { container -> container.items.any { it.item == "worldsmith:item/${AbilityGameplayExample.SIGIL}" } })
        val diagnostics = WorldsmithPackValidator.validate(pack)
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, diagnostics.toString())
        val catalog = ExistingWorldContentModules.registry().plan(ExistingWorldContentModules.input(pack))
        assertTrue(catalog.catalogValid, catalog.diagnostics.toString())
        val invocation = DesignLink(ContentKey("item", AbilityGameplayExample.SIGIL), ContentKey("ability", AbilityGameplayExample.CONDUCTOR), DesignRelation.INVOKES_ABILITY)
        assertTrue(invocation in WorldDesignCoverage.frozen(pack).links)
    }

    @Test fun `gameplay source and bindings round trip through a real immutable archive`() {
        val pack = AbilityGameplayExample.create()
        val path = temp.resolve("gameplay-trial.wspack")
        val info = WorldsmithResourceArchive.write(pack, path)
        val restored = WorldsmithResourceArchive.read(path)
        assertEquals(info.archiveSha256, restored.info.archiveSha256)
        assertEquals(pack.computedId, restored.pack.computedId)
        assertEquals(pack.abilities, restored.pack.abilities)
        assertEquals(pack.items, restored.pack.items)
        assertEquals(pack.computedId, WorldContentBundleIO.encode(restored.pack).manifest.id)
    }
}
