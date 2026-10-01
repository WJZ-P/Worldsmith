package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.draw.examples.Cottage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The kit's worked example must be a building that passes the structure checks, and the contract must show it as it is. */
class CottageExampleTest {
    @Test fun `the cottage is walkable from its porch to both furnished floors in every wood`() {
        for (wood in listOf("spruce", "oak", "dark_oak", "cherry")) for (seed in 0L..2L) {
            val drawing = Cottage().generate(DrawContext(seed, mapOf("wood" to wood), DrawLimits.DEFAULT))
            val blueprint = StructureBlueprint(id = "cottage", origin = BuildPos(0, 0, 0),
                access = StructureAccess(listOf(BuildPos(0, 1, 9)), listOf(BuildPos(0, 1, 0), BuildPos(0, 6, 0), BuildPos(-5, 6, -3), BuildPos(5, 6, 3))))
            val geometry = StructureDrawCompiler.compile(blueprint, drawing, strict = false)
            assertTrue(geometry.diagnostics.isEmpty(), "$wood/$seed: ${geometry.diagnostics}")
            val ids = drawing.voxels().map { it.block().state().id() }.toSet()
            assertTrue("minecraft:${wood}_stairs" in ids && "minecraft:red_bed" in ids && "minecraft:furnace" in ids, "$wood/$seed is built and furnished")
        }
    }

    @Test fun `the draw contract shows the example's source as it is`() {
        val root = Path.of(System.getProperty("worldsmith.projectRoot"))
        val source = Files.readString(root.resolve("core/src/main/java/com/wjz/worldsmith/core/draw/examples/Cottage.java"))
            .replace("\r\n", "\n").lineSequence().dropWhile { !it.startsWith("public final class") }.joinToString("\n").trimEnd().replace("\t", "    ")
        val contract = Files.readString(root.resolve("core/src/main/resources/prompts/contract/draw.system.md")).replace("\r\n", "\n")
        assertTrue(source.length > 500 && source in contract, "Copy Cottage.java's class into the draw contract's worked example")
    }
}
