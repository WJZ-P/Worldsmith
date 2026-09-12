package com.wjz.worldsmith.core.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Contract assertions only; they make no claim of visual acceptance or an image-model call. */
class CreatureAndLightingDirectionTest {
    private fun contract(name:String)=requireNotNull(javaClass.getResourceAsStream("/prompts/contract/$name.system.md")).bufferedReader().use {it.readText()}

    @Test fun `creature authoring routes share vanilla inspired low noise direction`() {
        for(name in listOf("creatures","creature_authoring","textures")) {
            val text=contract(name)
            assertTrue("Minecraft" in text,name)
            assertTrue("vanilla" in text.lowercase(),name)
            assertTrue("speckle" in text||"random spots" in text,name)
            assertTrue("UV" in text,name)
        }
    }

    @Test fun `drawing guidance exposes real anchors and advisory opt in light diagnostics`() {
        val draw=contract("draw")
        listOf("hangingLightFixture(id,at,anchor)","intentionallyDark(reason)","estimateLighting:true","not fail publication").forEach {assertTrue(it in draw,it)}
        val architecture=contract("architecture")
        listOf("INTENTIONALLY_DARK","roof voids","advisory warnings").forEach {assertTrue(it in architecture,it)}
        assertFalse("either\n  declaration requires READABLE, never EXTERIOR_ONLY" in architecture)
    }
}
