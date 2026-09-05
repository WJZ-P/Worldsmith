package com.wjz.worldsmith.core.structure

/** Lightweight three-view schematic; no Minecraft registry, renderer or network is involved. */
object StructurePreview {
    /** Text travels over MCP even when the caller has no access to local SVG files. */
    @JvmStatic
    fun floorPlan(geometry: CompiledStructure): String {
        val y = minOf(2, geometry.size.y - 1)
        val symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val materials = geometry.voxels.map { it.material.block }.filter { it != "minecraft:air" }.distinct().sorted()
        val legend = materials.mapIndexed { index, block -> block to symbols[index % symbols.length] }.toMap()
        val cells = geometry.voxels.filter { it.position.y == y }.associate { (it.position.x to it.position.z) to it.material.block }
        return buildString {
            appendLine("Local Y=$y; columns +X, rows +Z. '.' is explicit air; spaces KEEP existing world.")
            for (z in 0 until geometry.size.z) {
                for (x in 0 until geometry.size.x) {
                    val block = cells[x to z]
                    append(if (block == null) ' ' else if (block == "minecraft:air") '.' else legend.getValue(block))
                }
                appendLine()
            }
            legend.forEach { (block, symbol) -> appendLine("$symbol = $block") }
        }
    }

    @JvmStatic
    fun svg(geometry:CompiledStructure):String {
        val scale=6
        val width=geometry.size.x*scale
        val depth=geometry.size.z*scale
        val height=geometry.size.y*scale
        val panelWidth=maxOf(width,depth)+32
        val panelHeight=maxOf(height,depth)+64
        val materialColors=geometry.voxels.map {it.material.block}.distinct().sorted().associateWith(::color)
        fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${panelWidth*3}\" height=\"${panelHeight+45}\" viewBox=\"0 0 ${panelWidth*3} ${panelHeight+45}\">")
            append("<rect width=\"100%\" height=\"100%\" fill=\"#172022\"/>")
            append("<text x=\"16\" y=\"24\" font-family=\"sans-serif\" font-size=\"13\" fill=\"#e5e8e0\">${escape(geometry.id)} · ${geometry.size.x} × ${geometry.size.y} × ${geometry.size.z}</text>")
            append("<text x=\"16\" y=\"42\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#9cacb0\">Schematic only (not in-game render)</text>")
            for(view in 0..2) {
                val title=listOf("TOP","FRONT (-Z)","RIGHT (+X)")[view]
                val visible=linkedMapOf<Pair<Int,Int>,StructureVoxel>()
                geometry.voxels.filter {it.material.block!="minecraft:air"}.sortedBy { when(view){0->it.position.y;1->-it.position.z;else->it.position.x} }.forEach { voxel ->
                    val p=voxel.position
                    val key=when(view){0->p.x to p.z;1->p.x to (geometry.size.y-1-p.y);else->p.z to (geometry.size.y-1-p.y)}
                    visible[key]=voxel
                }
                val offset=16+view*panelWidth
                append("<text x=\"$offset\" y=\"60\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#9cacb0\">$title</text>")
                visible.forEach { (p,v) -> append("<rect x=\"${offset+p.first*scale}\" y=\"${74+p.second*scale}\" width=\"$scale\" height=\"$scale\" fill=\"${materialColors.getValue(v.material.block)}\" stroke=\"#000000\" stroke-opacity=\".12\" stroke-width=\".4\"><title>${escape(v.material.block)}</title></rect>") }
            }
            append("</svg>")
        }
    }

    private fun color(block:String):String = when {
        "dark_oak" in block -> "#604432"
        "spruce" in block -> "#826143"
        "cherry" in block -> "#dba8a0"
        "birch" in block -> "#d3c296"
        "oak" in block -> "#b3915e"
        "red_terracotta" in block -> "#a45446"
        "glass" in block -> "#8dc2c3"
        "lantern" in block || "glowstone" in block -> "#f3bf54"
        "moss" in block || "leaves" in block -> "#628451"
        "stone" in block || "andesite" in block -> "#90999a"
        else -> "hsl(${Math.floorMod(block.hashCode(),360)},32%,62%)"
    }
}
