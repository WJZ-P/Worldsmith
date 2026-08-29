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

        assertEquals(pack.computedId, pack.manifest.id)
        assertEquals(16, pack.biomes.biomes.size)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }

    @Test
    fun `every referenced feature is declared once in the library`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val declared = pack.features.features.map { it.id }
        val referenced = pack.biomes.biomes.flatMap { biome -> biome.features.map { it.feature } }

        assertEquals(declared.size, declared.toSet().size)
        assertTrue(declared.containsAll(referenced))
        assertTrue(referenced.size > declared.size, "the fixture should reuse at least one feature across biomes")
    }

    @Test
    fun `display metadata does not change generation identity`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val renamed = pack.copy(manifest = pack.manifest.copy(displayName = "Renamed Ashlands", description = "Edited description"))

        val diagnostics = WorldsmithPackValidator.validate(renamed)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `pack validator rejects terrain outside dimension bounds`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(terrain = it.terrain.copy(seaLevel = 900)) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "terrain.seaLevel" && it.code == "SEA_LEVEL_OUT_OF_RANGE" })
    }

    @Test
    fun `pack validator rejects a stale declared hash`() {
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
            .let { it.copy(manifest = it.manifest.copy(id = "0".repeat(64))) }

        val diagnostics = WorldsmithPackValidator.validate(pack)

        assertTrue(diagnostics.any { it.path == "manifest.id" && it.code == "PACK_HASH_MISMATCH" })
    }

    @Test
    fun `portable pack loads from a regular directory`() {
        val root = "worldsmith/packs/ashlands"
        listOf("worldsmith.json", "terrain.json", "biomes.json", "features.json").forEach { relative ->
            val target = tempDir.resolve(relative)
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream("$root/$relative").use { source ->
                requireNotNull(source) { "Missing test resource $relative" }
                Files.copy(source, target)
            }
        }

        val pack = WorldsmithPackLoader.loadDirectory(tempDir)

        assertEquals(pack.computedId, pack.manifest.id)
        assertTrue(WorldsmithPackValidator.validate(pack).isEmpty())
    }
}
