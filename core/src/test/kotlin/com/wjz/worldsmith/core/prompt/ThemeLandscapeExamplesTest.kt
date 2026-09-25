package com.wjz.worldsmith.core.prompt

import com.wjz.worldsmith.core.model.*
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import com.wjz.worldsmith.core.structure.*
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import com.wjz.worldsmith.core.validation.TerrainPlanValidator
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The design cases are human review exercises, but their concrete API examples must remain executable grammar. */
class ThemeLandscapeExamplesTest {
    @Test fun `theme review examples use real typed fields and valid local configurations`() {
        val root = Path.of(System.getProperty("worldsmith.projectRoot"))
        val markdown = Files.readString(root.resolve("docs/theme-landscape-review-cases.md"))
        val examples = Regex("```json\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(markdown)
            .map { Json.parseToJsonElement(it.groupValues[1]).jsonObject }.toList()
        assertEquals(4, examples.size, "Assign any new fragment a typed validator rather than checking syntax alone")
        val pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands")
        val shape = pack.terrain.shape as TerrainShape.Procedural
        fun validTerrain(value: TerrainShape.Procedural) {
            val errors = TerrainPlanValidator.validate(pack.terrain.copy(shape = value)).filter { it.severity == DiagnosticSeverity.ERROR }
            assertTrue(errors.isEmpty(), errors.toString())
        }
        for (example in examples) when {
            "flats" in example -> {
                val relief = WorldsmithJson.format.decodeFromJsonElement<ReliefDistribution>(example)
                validTerrain(shape.copy(relief = relief))
                assertEquals(0.0, relief.peaks, "The gentle example must honor its explicit no-peaks request")
            }
            "surface" in example -> {
                val fit = WorldsmithJson.format.decodeFromJsonElement<StructureTerrainFit>(example)
                val definition = WorldStructureDefinition("coastal_piles", StructureBlueprint(
                    id = "coastal_piles", size = BuildPos(12, 8, 12),
                    palette = mapOf("piles" to BuildMaterial("minecraft:stone")),
                    build = listOf(BuildOperation.Fill("floor", BuildPos(0, 0, 0), BuildPos(11, 0, 11), "piles")),
                ), StructurePlacement(listOf(pack.biomes.biomes.first().id), terrainFit = fit))
                val errors = StructureValidator.validate(StructureLibrary(structures = listOf(definition)), pack.biomes)
                    .filter { it.severity == DiagnosticSeverity.ERROR }
                assertTrue(errors.isEmpty(), errors.toString())
            }
            example["kind"]?.jsonPrimitive?.content == "caldera" -> {
                val relief = WorldsmithJson.format.decodeFromJsonElement<AnchorRelief>(example)
                validTerrain(shape.copy(anchors = listOf(Anchor("basin", AnchorPlacement.Fixed(0, 0), 360, relief))))
            }
            "effect" in example -> {
                val band = WorldsmithJson.format.decodeFromJsonElement<TerrainBand>(example)
                assertEquals(BandEffect.ADD, band.effect)
                validTerrain(shape.copy(bands = listOf(band)))
            }
            else -> fail("Unclassified documented API fragment: $example")
        }
    }
}
