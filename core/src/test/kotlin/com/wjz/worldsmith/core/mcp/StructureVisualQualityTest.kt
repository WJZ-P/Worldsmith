package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.model.PromptSet
import com.wjz.worldsmith.core.prompt.ClasspathPromptTemplateRepository
import com.wjz.worldsmith.core.structure.BuildBox
import com.wjz.worldsmith.core.structure.BuildPos
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/** Tests the feedback tools and guidance delivery, not an automated beauty score. */
class StructureVisualQualityTest {
    @TempDir lateinit var root: Path
    private val tools by lazy { WorldsmithMcpTools(root.resolve("packs")) }
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())) =
        StructureTestWorld.call(tools, name, args).also { assertFalse(it.isError, "$name: ${it.text}") }

    @Test fun `summary and full entry keep the same actionable creative brief and resolvable sections`() {
        val begin = listOf("summary", "full").map { detail ->
            call(WorldsmithWorkflow.BEGIN_TOOL, buildJsonObject { put("prompt", "Tidal archives carved into living cliffs"); put("detail", detail) }).structuredContent
        }
        assertEquals(begin[0].getValue("designGuide"), begin[1].getValue("designGuide"))
        val guide = begin[0].getValue("designGuide").jsonPrimitive.content
        assertTrue(guide.length in 500..2000)
        listOf("silhouette", "spatial sequence", "material", "representative", "Minimum size/count/light checks").forEach { assertTrue(it in guide, it) }
        assertFalse("howToDesign" in begin[0])
        val reference = begin[0].getValue("designReference").jsonObject
        val loop = call(reference.getValue("tool").jsonPrimitive.content, JsonObject(reference - "tool")).text
        listOf("renderMode", "isometric_back", "cutaway", "SAME view/mode", "not proof").forEach { assertTrue(it in loop, it) }
        val index = call(WorldsmithWorkflow.CONTRACT_TOOL, buildJsonObject { put("id", "architecture"); put("detail", "index") }).structuredContent
        val sections = index.getValue("sections").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        listOf("creative-brief", "form-function-and-family", "visual-quality-loop").forEach { assertTrue(it in sections) }
        val repo = ClasspathPromptTemplateRepository()
        assertFalse("Declare rooms and indoorPassages separately" in repo.load(PromptSet.DEFAULT.structureDetail).systemPrompt)
        val structure = repo.load(PromptSet.DEFAULT.structurePlan).systemPrompt
        assertFalse("drop the small ones" in structure)
        assertFalse("grid of floor lamp stands" in structure)
        assertTrue("do not hide an unlit area" in structure)
    }

    @Test fun `drawing blueprint and assembly schemas expose the same visual options`() {
        for (name in listOf("worldsmith_preview_drawing", "worldsmith_preview_structure", "worldsmith_preview_assembly")) {
            val properties = tools.all().single { it.name == name }.inputSchema.getValue("properties").jsonObject
            assertEquals(DrawPreview.VIEWS, properties.getValue("view").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(4, properties.getValue("views").jsonObject.getValue("maxItems").jsonPrimitive.int)
            assertEquals(DrawPreview.RENDER_MODES, properties.getValue("renderMode").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
            listOf("frame", "region", "cutaway", "sliceY").forEach { assertTrue(it in properties, "$name: $it") }
        }
        val assembly = tools.all().single { it.name == "worldsmith_preview_assembly" }.inputSchema.getValue("properties").jsonObject
        assertFalse("hideComponents" in assembly, "Assembly has no named component mapping; do not advertise an unsupported filter")
    }

    @Test fun `opposite elevations top and clay reveal geometry rather than material changes`() {
        val drawing = study()
        val views = DrawPreview.VIEWS.associateWith { view ->
            DrawPreview.png(drawing, view, 0, drawing.bounds(), emptyList(), "material")
        }
        views.forEach { (view, bytes) ->
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(1280, image.width); assertEquals(1000, image.height)
            assertTrue(pixels(bytes).count { it and 0xffffff != 0x18242D } > 1000, "$view must contain actual geometry")
        }
        assertFalse(pixels(views.getValue("front")).contentEquals(pixels(views.getValue("back"))))
        assertFalse(pixels(views.getValue("left")).contentEquals(pixels(views.getValue("right"))))
        assertFalse(pixels(views.getValue("isometric")).contentEquals(pixels(views.getValue("isometric_back"))))
        assertFalse(pixels(views.getValue("top")).contentEquals(pixels(views.getValue("slice"))))
        val recoloured = DrawStructure(drawing.bounds(), drawing.voxels().map { v ->
            if (v.block().state().isAir) v else DrawVoxel(v.position(), DrawBlock(BlockStateRef.of("gold_block")))
        }, drawing.anchors())
        val clay = DrawPreview.png(drawing, "isometric", null, drawing.bounds(), emptyList(), "clay")
        val sameForm = DrawPreview.png(recoloured, "isometric", null, drawing.bounds(), emptyList(), "clay")
        assertArrayEquals(clay, sameForm, "Changing only materials must not improve or change a clay study")
        assertFalse(clay.contentEquals(views.getValue("isometric")))
    }

    @Test fun `cutaway keeps the volume below original Y and does not collapse to a slice`() {
        val drawing = study()
        val service = StructurePreviewService()
        val args = buildJsonObject { put("views", McpJson.encode(listOf("top", "isometric_back"))); put("renderMode", "clay"); put("cutaway", true); put("sliceY", 0) }
        val result = service.render("study", drawing, args)
        val expected = DrawStructure(drawing.bounds(), drawing.voxels().filter { it.position().y() <= 0 }, drawing.anchors())
        assertEquals(expected.voxels().size, result.structuredContent.getValue("visibleAuthoredCells").jsonPrimitive.int)
        assertEquals(listOf("top", "isometric_back"), result.structuredContent.getValue("views").jsonArray.map { it.jsonPrimitive.content })
        for ((i, view) in listOf("top", "isometric_back").withIndex()) {
            assertArrayEquals(DrawPreview.png(expected, view, 0, drawing.bounds(), emptyList(), "clay"), Base64.getDecoder().decode(result.images[i].data))
        }
        val slice = service.render("study", drawing, buildJsonObject { put("view", "slice"); put("sliceY", 0); put("renderMode", "clay") })
        assertFalse(pixels(Base64.getDecoder().decode(result.images.first().data)).contentEquals(pixels(Base64.getDecoder().decode(slice.images.first().data))))
        assertEquals(service.render("study", drawing, JsonObject(emptyMap())).structuredContent.getValue("frame"), result.structuredContent.getValue("frame"))
        evidence("cutaway", result)
    }

    @Test fun `clay top shows height differences hidden by an otherwise flat neutral palette`() {
        val c=DrawCanvas.sized(8,8,8)
        c.pen("stone").fill(Box.of(2,0,2,2,0,2))
        c.pen("stone").fill(Box.of(5,0,2,5,4,2))
        fun at(x:Int,mode:String):Int {
            val img=ImageIO.read(ByteArrayInputStream(DrawPreview.png(c.snapshot(),"top",null,c.bounds(),emptyList(),mode)))
            return img.getRGB(kotlin.math.round(640+(x-3.5)*106.25).toInt(),644)
        }
        assertEquals(at(2,"material"),at(5,"material"),"The same approximate material has the same overhead colour")
        assertNotEquals(at(2,"clay"),at(5,"clay"),"Clay top must distinguish a low court from a high roof")
    }

    @Test fun `region component hiding and cutaway intersect and reject invalid visual requests`() {
        val drawing = study(); val service = StructurePreviewService()
        val roof = BuildBox(BuildPos(-5, 7, -5), BuildPos(7, 9, 8))
        val region = BuildBox(BuildPos(-4, -2, -4), BuildPos(2, 8, 6))
        val result = service.render("filters", drawing, buildJsonObject {
            put("views", McpJson.encode(listOf("left", "right"))); put("cutaway", true); put("sliceY", 7)
            put("region", McpJson.encode(region)); put("hideComponents", McpJson.encode(listOf("roof")))
        }, mapOf("roof" to roof))
        val expected = drawing.voxels().count { v -> v.position().y() <= 7 && StructurePreviewService.box(region).contains(v.position()) && !StructurePreviewService.box(roof).contains(v.position()) }
        assertEquals(expected, result.structuredContent.getValue("visibleAuthoredCells").jsonPrimitive.int)
        listOf(
            buildJsonObject { put("cutaway", true) },
            buildJsonObject { put("cutaway", true); put("sliceY", -3) },
            buildJsonObject { put("view", "perspective") },
            buildJsonObject { put("renderMode", "realistic") },
            buildJsonObject { put("views", McpJson.encode(List(5) { "front" })) },
        ).forEach { args -> assertThrows(IllegalArgumentException::class.java) { service.render("invalid", drawing, args) } }
    }

    @Test fun `documented visual loop runs through real blueprint and group MCP handlers`() {
        val sample = call("worldsmith_get_structure_example", buildJsonObject { put("id", "arcane_observatory") }).structuredContent
        val massing = call("worldsmith_preview_structure", buildJsonObject {
            put("blueprint", sample.getValue("blueprint")); put("views", McpJson.encode(listOf("isometric", "isometric_back", "front", "top"))); put("renderMode", "clay")
        })
        assertEquals(4, massing.images.size)
        val exterior = call("worldsmith_preview_structure", buildJsonObject {
            put("blueprint", sample.getValue("blueprint")); put("frame", massing.structuredContent.getValue("frame"))
            put("views", McpJson.encode(listOf("front", "back", "left", "right"))); put("renderMode", "material")
        })
        assertEquals(4, exterior.images.size)
        val interior = call("worldsmith_preview_structure", buildJsonObject {
            put("blueprint", sample.getValue("blueprint")); put("views", McpJson.encode(listOf("top", "isometric")))
            put("cutaway", true); put("sliceY", 5); put("frame", massing.structuredContent.getValue("frame"))
        })
        assertEquals(listOf("top", "isometric"), interior.structuredContent.getValue("views").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(interior.structuredContent.getValue("visibleAuthoredCells").jsonPrimitive.int < massing.structuredContent.getValue("visibleAuthoredCells").jsonPrimitive.int)
        val group = call("worldsmith_get_structure_example", buildJsonObject { put("id", "connected_courtyard") }).structuredContent
        val layout = call("worldsmith_preview_assembly", buildJsonObject {
            put("structure", group.getValue("structure")); put("views", McpJson.encode(listOf("top", "isometric", "isometric_back"))); put("renderMode", "clay")
        })
        assertTrue(layout.structuredContent.getValue("layoutPreviewAvailable").jsonPrimitive.boolean)
        assertEquals(3, layout.images.size)
        assertEquals(5, layout.structuredContent.getValue("pieceCount").jsonPrimitive.int)
        assertFalse(layout.structuredContent.getValue("minecraftCompiled").jsonPrimitive.boolean)
        evidence("massing", massing); evidence("exterior", exterior); evidence("interior", interior); evidence("layout", layout)
    }

    private fun evidence(name: String, result: McpToolResult) {
        val dir = Path.of(System.getProperty("worldsmith.projectRoot"), "build/structure-quality-verification")
        Files.createDirectories(dir)
        val views = result.structuredContent.getValue("views").jsonArray.map { it.jsonPrimitive.content }
        result.images.forEachIndexed { i, image -> Files.write(dir.resolve("$name-${views[i]}.png"), Base64.getDecoder().decode(image.data)) }
        Files.writeString(dir.resolve("$name.json"), JsonObject(result.structuredContent - "previewPath").toString())
    }

    private fun pixels(png: ByteArray): IntArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        return image.getRGB(0, 0, image.width, 900, null, 0, image.width) // Ignore captions in comparisons.
    }

    private fun study(): DrawStructure {
        val c = DrawCanvas(Box.of(-5, -2, -5, 7, 10, 8))
        c.pen("stone").fill(Box.of(-4, -2, -4, 6, -2, 6))
        c.pen("stone_bricks").shell(Box.of(-3, -1, -3, 5, 6, 5), 1)
        c.pen("air").fill(Box.of(-2, 0, -2, 4, 5, 4))
        c.pen("spruce_planks").fill(Box.of(-2, -1, -2, 4, -1, 4))
        c.pen("air").fill(Box.of(0, 0, -3, 1, 2, -3))
        c.pen("gold_block").fill(Box.of(-3, 2, -3, -2, 4, -3))
        c.pen("blue_stained_glass").fill(Box.of(5, 2, 0, 5, 4, 2))
        c.pen("deepslate").fill(Box.of(-4, 7, -4, 6, 7, 6))
        c.pen("deepslate").fill(Box.of(-2, 8, -4, 4, 8, 6))
        c.pen("deepslate").fill(Box.of(0, 9, -4, 2, 9, 6))
        c.pen("lantern").fill(Box.of(2, 0, 2, 2, 0, 2))
        return c.snapshot()
    }
}
