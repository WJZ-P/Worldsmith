package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.Files
import java.nio.file.Path

/** Developer geometry preview; intentionally does not boot Minecraft or require an API key. */
object StructurePreviewCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 2..4) { "Usage: StructurePreviewCli <blueprint.json> <preview.svg> [sliceY] [cutaway]" }
        val blueprint=WorldsmithJson.decode<StructureBlueprint>(Files.readString(Path.of(args[0])))
        val compiled=StructureGeometryCompiler.compile(blueprint)
        val output=Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val slice = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.toInt() ?: minOf(2, blueprint.size.y - 1)
        val cutaway = args.getOrNull(3)?.toBooleanStrict() ?: false
        Files.writeString(output,StructurePreview.svg(compiled, slice, cutaway))
        println("${compiled.id}: ${compiled.voxels.size} cells (${compiled.expandedWork} visits); $output")
    }
}
