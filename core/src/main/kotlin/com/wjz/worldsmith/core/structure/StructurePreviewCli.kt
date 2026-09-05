package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.nio.file.Files
import java.nio.file.Path

/** Developer geometry preview; intentionally does not boot Minecraft or require an API key. */
object StructurePreviewCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 2..6) { "Usage: StructurePreviewCli <source.json> <preview.svg> [sliceY] [cutaway] [variant] [assembly]" }
        val source=Files.readString(Path.of(args[0]))
        val variant=args.getOrNull(4)?.toInt() ?: 0
        val assembly=args.getOrNull(5)?.toBooleanStrict() ?: false
        val compiled=if(assembly) {
            val d=WorldsmithJson.decode<WorldStructureDefinition>(source)
            val plans=StructureCatalogCompiler.compile(StructureLibrary(structures=listOf(d))).plans.getValue(d.id)
            require(variant in plans.indices) { "Unknown assembly variant" }
            StructureCatalogCompiler.preview(d.id,plans[variant])
        } else StructureGeometryCompiler.compile(WorldsmithJson.decode<StructureBlueprint>(source),variant)
        val output=Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val slice = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.toInt() ?: minOf(2, compiled.size.y - 1)
        val cutaway = args.getOrNull(3)?.toBooleanStrict() ?: false
        Files.writeString(output,StructurePreview.svg(compiled, slice, cutaway))
        println("${compiled.id}: ${compiled.voxels.size} cells (${compiled.expandedWork} visits); $output")
    }
}
