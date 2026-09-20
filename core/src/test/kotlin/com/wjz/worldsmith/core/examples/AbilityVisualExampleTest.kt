package com.wjz.worldsmith.core.examples

import com.wjz.worldsmith.core.ability.AbilityCompiler
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AbilityVisualExampleTest {
    @Test fun `source-authored visual and steering examples compile against real standard signatures`() {
        val pack = AbilityVisualExample.create()
        val errors = WorldsmithPackValidator.validate(pack).filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { "${it.path}: ${it.message}" })
        val compiled = pack.abilities.programs.associate { it.id to AbilityCompiler.compile(it) }
        assertTrue(compiled.getValue(AbilityVisualExample.SHOWCASE).usedCapabilities.keys.containsAll(listOf("fx.particles", "fx.path", "fx.item", "animation.play", "animation.stop", "fx.transform")))
        assertTrue(compiled.getValue(AbilityVisualExample.STEERING).usedCapabilities.keys.containsAll(listOf("projectile.velocity", "projectile.steer", "map.get")))
        assertEquals(AbilityVisualExample.SHOWCASE, pack.creatures.creatures.single().ability?.program)
        assertEquals(pack.computedId, AbilityVisualExample.create().computedId)
    }
}
