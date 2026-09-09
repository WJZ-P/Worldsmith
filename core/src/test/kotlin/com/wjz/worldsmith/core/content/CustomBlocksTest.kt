package com.wjz.worldsmith.core.content

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomBlocksTest {
    private fun block(id: String, profile: CustomBlockProfile = CustomBlockProfile.STONE, light: Int = 0) =
        CustomBlockDefinition(id, "Moon $id", profile, "a".repeat(64), light, "Moonstone culture")

    @Test fun `native allocation is deterministic independent of declaration order`() {
        val first = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(block("zinc"), block("amber"))))
        val second = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(block("amber"), block("zinc"))))
        assertEquals(first, second)
        assertEquals("worldsmith:content/block/stone/00", first.bindings[0].nativeId())
        assertEquals("worldsmith:content/amber", first.bindings[0].logicalId())
    }

    @Test fun `existing saved blocks never remap when lexically earlier blocks are appended`() {
        val initial = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(block("zinc"))))
        val extended = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(block("amber"), block("zinc"))), initial)
        assertEquals(initial.bindings.single(), extended.bindings.single { it.id == "zinc" })
        assertEquals(1, extended.bindings.single { it.id == "amber" }.slot)
    }

    @Test fun `every profile has an independent bounded host pool`() {
        val library = CustomBlockLibrary(blocks = CustomBlockProfile.entries.flatMap { profile ->
            (0 until 32).map { block("${profile.name.lowercase()}_$it", profile) }
        })
        assertTrue(CustomBlockValidation.validate(library).isEmpty())
        assertEquals(128, CustomBlockBindings.plan("realm", library).bindings.size)
        val overflow = library.copy(blocks = library.blocks + block("extra_stone"))
        assertTrue(CustomBlockValidation.validate(overflow).any { it.code == "blocks.capacity" })
        assertThrows(IllegalArgumentException::class.java) { CustomBlockBindings.plan("realm", overflow) }
    }

    @Test fun `profile light removal and world scope edits need explicit migration`() {
        val definition = block("moon", light = 7)
        val initial = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(definition)))
        for (library in listOf(CustomBlockLibrary(), CustomBlockLibrary(blocks = listOf(definition.copy(light = 15))),
            CustomBlockLibrary(blocks = listOf(definition.copy(profile = CustomBlockProfile.METAL))))) {
            assertThrows(IllegalArgumentException::class.java) { CustomBlockBindings.plan("realm", library, initial) }
        }
        assertThrows(IllegalArgumentException::class.java) { CustomBlockBindings.plan("other", CustomBlockLibrary(blocks = listOf(definition)), initial) }
    }

    @Test fun `visual and story edits preserve saved state identity`() {
        val original = block("moon")
        val initial = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(original)))
        assertEquals(initial, CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(original.copy(displayName = "New moon", textureAsset = "b".repeat(64), themeRole = "New theme"))), initial))
    }

    @Test fun `malformed documents have field-specific diagnostics`() {
        val library = CustomBlockLibrary(2, listOf(block("../bad").copy(displayName = "", textureAsset = "../texture", light = 16), block("../bad")))
        val codes = CustomBlockValidation.validate(library).map { it.code }.toSet()
        assertTrue(codes.containsAll(listOf("blocks.schema", "blocks.id", "blocks.name", "blocks.texture", "blocks.light", "blocks.duplicate")))
    }

    @Test fun `snapshot tampering and duplicate host assignments are rejected`() {
        val binding = CustomBlockBinding("moon", CustomBlockProfile.STONE, 0, 0)
        for (bindings in listOf(listOf(binding, binding.copy(id = "sun")), listOf(binding.copy(slot = 32)), listOf(binding.copy(light = -1)))) {
            assertThrows(IllegalArgumentException::class.java) { CustomBlockBindings.validateSnapshot(CustomBlockBindingSnapshot(scope = "realm", bindings = bindings)) }
        }
    }

    @Test fun `binding roundtrip retains immutable snapshot and rejects unknown fields`() {
        val snapshot = CustomBlockBindings.plan("realm", CustomBlockLibrary(blocks = listOf(block("moon"))))
        val restored = CustomBlockBindings.decode(CustomBlockBindings.encode(snapshot))
        assertEquals(snapshot, restored)
        assertThrows(UnsupportedOperationException::class.java) { (restored.bindings as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { CustomBlockBindings.decode(CustomBlockBindings.encode(snapshot).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"mystery\":true")) }
    }
}
