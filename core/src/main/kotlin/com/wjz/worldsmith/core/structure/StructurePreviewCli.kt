package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.Files
import java.nio.file.Path

/** Developer geometry preview; intentionally does not boot Minecraft or require an API key. */
object StructurePreviewCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2) { "Usage: StructurePreviewCli <blueprint.json> <preview.svg>" }
        val blueprint=WorldsmithJson.decode<StructureBlueprint>(Files.readString(Path.of(args[0])))
        val compiled=StructureGeometryCompiler.compile(blueprint)
        val output=Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        Files.writeString(output,StructurePreview.svg(compiled))
        println("${compiled.id}: ${compiled.voxels.size} cells (${compiled.expandedWork} visits); $output")
    }
}
