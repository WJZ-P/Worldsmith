package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.authoring.AuthoredStructure
import com.wjz.worldsmith.authoring.AuthoringContext
import com.wjz.worldsmith.authoring.StructureProgram
import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/** Executes the published authoring source with only the SDK, then checks frozen geometry offline. */
class PlateauObservatoryExampleTest {
    @TempDir lateinit var temporary: Path
    private val project get() = Path.of(System.getProperty("worldsmith.projectRoot"))
    private fun examples(): Map<String, AuthoredStructure> {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "This developer example test uses the installed JDK compiler" }
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val source = project.resolve("docs/examples/theme-landscape/PlateauObservatory.java")
        val classpath = listOf(StructureProgram::class.java, DrawCanvas::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, null, null).use { manager ->
            val ok = compiler.getTask(diagnostics, manager, null, listOf("--release", "21", "-classpath", classpath, "-d", classes.toString()), null, manager.getJavaFileObjects(source.toFile())).call()
            assertTrue(ok, diagnostics.toString())
        }
        URLClassLoader(arrayOf(classes.toUri().toURL()), StructureProgram::class.java.classLoader).use { loader ->
            val program = loader.loadClass("PlateauObservatory").getDeclaredConstructor().newInstance() as StructureProgram
            return listOf("massing", "refined").associateWith { stage -> program.generate(AuthoringContext(DrawContext(381L, mapOf("study" to stage), DrawLimits.DEFAULT))) }
        }
    }

    private fun metadata(authored: AuthoredStructure): StructureBlueprint {
        val m = Json.parseToJsonElement(authored.sidecar().decodeToString()).jsonObject.getValue("semantics").jsonObject
        val document = buildJsonObject {
            put("id", "plateau_observatory"); put("origin", m.getValue("origin")); put("palette", m.getValue("palette"))
            put("rooms", m.getValue("rooms")); put("indoorPassages", m.getValue("indoorPassages")); put("keepClear", m.getValue("keepClear"))
            put("ports", m.getValue("ports")); put("interactions", m.getValue("interactions"))
            putJsonObject("variation") { put("protectedAreas", m.getValue("protectedAreas")) }
            putJsonObject("access") {
                put("entrances", m.getValue("entrances")); put("destinations", m.getValue("destinations")); put("requiredClear", m.getValue("keepClear"))
            }
            putJsonObject("lighting") {
                put("mode", "READABLE"); put("spaces", JsonArray(m.getValue("rooms").jsonArray + m.getValue("indoorPassages").jsonArray)); put("sources", m.getValue("sources"))
            }
        }
        return WorldsmithJson.format.decodeFromJsonElement(document)
    }

    @Test fun `published plateau program has actual storeys connected stair access and declared fixtures`() {
        for ((stage, authored) in examples()) {
            val blueprint = metadata(authored)
            val geometry = StructureDrawCompiler.compile(blueprint, authored.drawing())
            assertTrue(geometry.diagnostics.isEmpty(), "$stage: ${geometry.diagnostics}")
            assertEquals(setOf(3, 11, 19), blueprint.rooms.map { it.from.y }.toSet())
            assertEquals(4, blueprint.rooms.size)
            assertEquals(3, blueprint.keepClear.size, "Arrival corridor plus two air-only court subregions are actual sidecar clearance, separate from variation protection")
            assertTrue(blueprint.keepClear.all { it.from.y == 3 }, "Court clearance excludes the authored floor at Y=2")
            assertTrue(blueprint.lighting!!.sources.size >= 10)
            val normalized = StructureDrawCompiler.metadata(blueprint, geometry)
            assertTrue(StructureLightingChecker.validate(normalized, geometry.voxels).diagnostics.isEmpty())
            for (at in listOf(BuildPos(5, 3, 19), BuildPos(5, 3, 7), BuildPos(-11, 3, 7), BuildPos(-11, 3, -2),
                BuildPos(-11, 3, -7), BuildPos(9, 3, -7), BuildPos(-11, 11, -7), BuildPos(-11, 19, -7))) {
                val p = BuildPos(at.x - geometry.sourceMin.x, at.y - geometry.sourceMin.y, at.z - geometry.sourceMin.z)
                assertTrue(p in geometry.reachableFeet, "$stage route missing at $at")
            }
        }
    }

    @Test fun `theme produces distinct masses a quiet court a restrained material family and recessed windows`() {
        val authored = examples().getValue("refined")
        val components = authored.components()
        assertTrue(components.getValue("main_tower").max().y() - components.getValue("low_service_wing").max().y() >= 16)
        val court = components.getValue("open_court")
        val solids = authored.drawing().voxels().filterNot { it.block().state().isAir }
        assertTrue(solids.count { court.contains(it.position()) } <= 2, "The court is usable negative space, not filler props")
        val families = setOf("minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:deepslate_tiles", "minecraft:deepslate_tile_slab", "minecraft:dark_oak_planks", "minecraft:dark_oak_slab")
        assertTrue(solids.count { it.block().state().id() in families }.toDouble() / solids.size > 0.95)
        assertEquals(setOf("foundation", "roof", "timber"), metadata(authored).palette.keys)
        val cells = authored.drawing().voxels().associateBy { it.position() }
        assertTrue(cells.getValue(Vec3i(-12, 7, -13)).block().state().isAir)
        assertEquals("minecraft:gray_stained_glass", cells.getValue(Vec3i(-12, 7, -12)).block().state().id())
        assertTrue(cells.getValue(Vec3i(-12, 7, -11)).block().state().isAir, "North window has a reveal, glass and then the actual room")
        assertTrue((3..5).all { y -> (-2..3).all { z -> cells.getValue(Vec3i(9, y, z)).block().state().isAir } }, "The low porch preserves three-block headroom on the service-wing route")
        assertEquals("minecraft:deepslate_tile_slab", cells.getValue(Vec3i(9, 7, 1)).block().state().id())
        assertEquals("minecraft:deepslate_tiles", cells.getValue(Vec3i(-16, 2, 7)).block().state().id(), "The stair approach visibly joins the arrival route instead of ending as an isolated painted spur")
        assertTrue(components.getValue("arrival_threshold").max().y() < components.getValue("low_service_wing").max().y())
    }

    @Test fun `same frame visual repair reduces the measured blank north wall and writes reviewable views`() {
        val authored = examples()
        val frame = Box.of(-24, 0, -18, 24, 28, 20)
        val output = Files.createDirectories(project.resolve("build/structure-quality-verification/plateau-observatory"))
        val evidence = authored.mapValues { (_, value) -> StructureVisualEvidence.inspect(value.drawing(), listOf("front", "back", "left", "right"), frame = frame) }
        val before = evidence.getValue("massing").projections.first { it.view == "front" }
        val after = evidence.getValue("refined").projections.first { it.view == "front" }
        assertTrue(after.largestPlanarPanel!!.cells < before.largestPlanarPanel!!.cells,
            "Repair actual north-wall depth, not just materials: ${before.largestPlanarPanel} -> ${after.largestPlanarPanel}")
        assertTrue(after.reliefEdges > before.reliefEdges)
        for ((stage, value) in authored) {
            for (mode in listOf("clay", "material")) for (view in listOf("isometric", "isometric_back", "front", "back"))
                Files.write(output.resolve("$stage-$mode-$view.png"), DrawPreview.png(value.drawing(), view, null, frame, emptyList(), mode))
            Files.writeString(output.resolve("$stage-evidence.json"), WorldsmithJson.format.encodeToString(StructureVisualEvidenceReport.serializer(), evidence.getValue(stage)))
        }
        val final = authored.getValue("refined").drawing()
        for (y in listOf(5, 13, 21)) {
            val cut = DrawStructure(final.bounds(), final.voxels().filter { it.position().y() <= y }, final.anchors())
            Files.write(output.resolve("refined-floor-$y.png"), DrawPreview.png(cut, "top", y, frame, emptyList(), "material"))
        }
        Files.writeString(output.resolve("review.json"), buildJsonObject {
            put("theme", "Quiet wind-exposed plateau observatory; tall observation datum, low service wing, empty sheltered court and a turning arrival route.")
            put("largestNorthPanelBefore", before.largestPlanarPanel!!.cells); put("largestNorthPanelAfter", after.largestPlanarPanel!!.cells)
            put("northReliefEdgesBefore", before.reliefEdges); put("northReliefEdgesAfter", after.reliefEdges)
            put("repair", "First repair: deep two-storey north slit windows and restrained corner piers reduce the blank wall. Second repair after image review: a low service-door porch, a preparation table and a small bench give the path a human-scale threshold while preserving three-block headroom, the tower skyline and the empty court.")
            put("remainingReview", "Retaining walls deliberately remain broad and quiet. Check the exposed external stair against the chosen climate and site; shorten or shelter it if the player's theme requires severe weather protection.")
            put("validationScope", "Pure Java authoring, frozen geometry, authored-room lighting declarations, approximate two-block navigation and offline shape-aware PNGs. Native plateau placement, terrain support, runtime lighting and in-game appearance were not exercised.")
        }.toString())
    }
}
