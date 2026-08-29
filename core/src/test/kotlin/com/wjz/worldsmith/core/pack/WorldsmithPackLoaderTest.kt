package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.validation.WorldsmithPackValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorldsmithPackLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `built in ashlands pack loads and validates`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")

        assertEquals("ashlands", pack.manifest.id)
        assertEquals(8, pack.biomeLayout.skeletons.size)
        assertEquals(pack.biomeLayout.skeletons.map { it.id }, pack.biomeSkins.skins.map { it.skeletonId })
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }

    @Test
    fun `pack validator rejects content with mismatched world ids`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(terrain = it.terrain.copy(worldId = "another_world")) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "terrain.worldId" && it.code == "WORLD_ID_MISMATCH" })
    }

    @Test
    fun `pack validator rejects terrain outside dimension bounds`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(terrain = it.terrain.copy(seaLevel = 900)) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "terrain.seaLevel" && it.code == "SEA_LEVEL_OUT_OF_RANGE" })
    }

    @Test
    fun `portable pack loads from a regular directory`() {
        val root = "worldsmith/packs/ashlands"
        listOf("worldsmith.json", "terrain.json", "biomes/layout.json", "biomes/skins.json").forEach { relative ->
            val target = tempDir.resolve(relative)
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream("$root/$relative").use { source ->
                requireNotNull(source) { "Missing test resource $relative" }
                Files.copy(source, target)
            }
        }

        val pack = WorldsmithPackLoader.loadDirectory(tempDir)

        assertEquals("ashlands", pack.manifest.id)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }
}
