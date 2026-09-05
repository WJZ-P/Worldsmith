package com.wjz.worldsmith.core.structure

import kotlin.math.roundToInt

/** Bounded schematic views of the exact compiled geometry; no Minecraft renderer. */
object StructurePreview {
    private const val MAX_ISOMETRIC_FACES = 16000

    @JvmStatic @JvmOverloads
    fun floorPlan(geometry: CompiledStructure, sliceY: Int = minOf(2, geometry.size.y - 1)): String {
        require(sliceY in 0 until geometry.size.y) { "sliceY must lie inside the blueprint height" }
        val layer = geometry.voxels.filter { it.position.y == sliceY }
        val symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val materials = layer.filterNot { it.material.isAir() }.map { it.material.block }.distinct().sorted()
        val wide = materials.size > symbols.length
        val legend = materials.mapIndexed { index, block ->
            block to if (wide) "${symbols[index / symbols.length]}${symbols[index % symbols.length]}" else symbols[index].toString()
        }.toMap()
        val cells = layer.associateBy { it.position.x to it.position.z }
        return buildString {
            appendLine("Local Y=$sliceY; columns +X, rows +Z. '.' is explicit air; spaces KEEP existing world.")
            for (z in 0 until geometry.size.z) {
                for (x in 0 until geometry.size.x) {
                    val voxel = cells[x to z]
                    append(when {
                        voxel == null -> if (wide) "  " else " "
                        voxel.material.isAir() -> if (wide) ".." else "."
                        else -> legend.getValue(voxel.material.block)
                    })
                }
                appendLine()
            }
            legend.forEach { (block, symbol) -> appendLine("$symbol = $block") }
        }
    }

    @JvmStatic @JvmOverloads
    fun svg(geometry: CompiledStructure, sliceY: Int = minOf(2, geometry.size.y - 1), cutaway: Boolean = false): String {
        require(sliceY in 0 until geometry.size.y) { "sliceY must lie inside the blueprint height" }
        val scale = 6
        val solids = geometry.voxels.filter { !it.material.isAir() && (!cutaway || it.position.y <= sliceY) }
        val colors = solids.map { it.material.block }.distinct().associateWith(::color)
        val panelWidth = maxOf(144, maxOf(geometry.size.x, geometry.size.z) * scale + 32)
        val panelHeight = maxOf(geometry.size.y, geometry.size.z) * scale + 84
        val isoUnit = 4.8
        val isoWidth = maxOf(224, ((geometry.size.x + geometry.size.z) * isoUnit).roundToInt() + 32)
        val isoHeight = ((geometry.size.x + geometry.size.z) * isoUnit / 2 + geometry.size.y * isoUnit).roundToInt() + 90
        val width = panelWidth * 3 + isoWidth
        val height = maxOf(panelHeight, isoHeight) + 30
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
            append("<rect width=\"100%\" height=\"100%\" fill=\"#172022\"/>")
            label(16,24,"${geometry.id} | ${geometry.size.x} x ${geometry.size.y} x ${geometry.size.z}",14,"#e5e8e0")
            label(16,44,if (cutaway) "CUTAWAY: Y <= $sliceY. Schematic, not in-game render." else "Full geometry. Schematic, not in-game render.",11)
            for (view in 0..2) {
                val title = listOf("TOP", "FRONT (-Z)", "RIGHT (+X)")[view]
                val visible = linkedMapOf<Pair<Int,Int>,StructureVoxel>()
                solids.sortedBy { when(view) { 0 -> it.position.y; 1 -> -it.position.z; else -> it.position.x } }.forEach { voxel ->
                    val p = voxel.position
                    val cell = when(view) { 0 -> p.x to p.z; 1 -> p.x to (geometry.size.y - 1 - p.y); else -> p.z to (geometry.size.y - 1 - p.y) }
                    visible[cell] = voxel
                }
                val offset = 16 + view * panelWidth
                label(offset,64,title,12)
                visible.forEach { (p,v) ->
                    // Otherwise a distant back wall looks exactly like a filled
                    // doorway, and a cutaway floor blends into walls of the same material.
                    val depth = when (view) {
                        0 -> (geometry.size.y - 1 - v.position.y).toDouble() / maxOf(1, geometry.size.y - 1)
                        1 -> v.position.z.toDouble() / maxOf(1, geometry.size.z - 1)
                        else -> (geometry.size.x - 1 - v.position.x).toDouble() / maxOf(1, geometry.size.x - 1)
                    }
                    val fill = shade(colors.getValue(v.material.block), 1 - depth * 0.45)
                    append("<rect x=\"${offset+p.first*scale}\" y=\"${80+p.second*scale}\" width=\"$scale\" height=\"$scale\" fill=\"$fill\" stroke=\"#000000\" stroke-opacity=\".16\" stroke-width=\".4\"><title>${escape(v.material.block)}</title></rect>")
                }
            }
            appendIsometric(solids,colors,panelWidth*3,geometry.size,isoUnit)
            append("</svg>")
        }
    }

    private fun StringBuilder.appendIsometric(geometry: List<StructureVoxel>, colors: Map<String,String>, offset: Int, size: BuildPos, unit: Double) {
        label(offset+16,64,"ISOMETRIC (+X,-Z)",12)
        // Look at the authored front (-Z), not the rear facade. Reflecting the
        // display Z coordinates changes the camera basis, never the blueprint.
        val solids = geometry.map { it.copy(position = it.position.copy(z = size.z - 1 - it.position.z)) }
        val occupied = solids.mapTo(HashSet()) { it.position }
        val directions = listOf(BuildPos(0,1,0),BuildPos(1,0,0),BuildPos(0,0,1))
        var faceCount = 0
        for (voxel in solids) for (d in directions) {
            if (BuildPos(voxel.position.x+d.x,voxel.position.y+d.y,voxel.position.z+d.z) !in occupied) faceCount++
        }
        if (faceCount > MAX_ISOMETRIC_FACES) {
            label(offset+16,100,"Too many exposed faces.",11)
            label(offset+16,118,"Use a lower cutaway slice.",11)
            return
        }
        fun project(x:Int,y:Int,z:Int):String {
            val sx = offset + 16 + size.z * unit + (x-z) * unit
            val sy = 80 + size.y * unit + (x+z) * unit / 2 - y * unit
            return "$sx,$sy"
        }
        for (voxel in solids.sortedWith(compareBy({it.position.x+it.position.y+it.position.z},{it.position.y}))) {
            val (x,y,z) = voxel.position
            val top = listOf(project(x,y+1,z),project(x+1,y+1,z),project(x+1,y+1,z+1),project(x,y+1,z+1))
            val east = listOf(project(x+1,y,z),project(x+1,y,z+1),project(x+1,y+1,z+1),project(x+1,y+1,z))
            val south = listOf(project(x,y,z+1),project(x+1,y,z+1),project(x+1,y+1,z+1),project(x,y+1,z+1))
            val faces = listOf(top,east,south)
            val shades = listOf(1.12,0.73,0.9)
            for (i in directions.indices) {
                val d = directions[i]
                if (BuildPos(x+d.x,y+d.y,z+d.z) in occupied) continue
                val fill = shade(colors.getValue(voxel.material.block),shades[i])
                append("<polygon points=\"${faces[i].joinToString(" ")}\" fill=\"$fill\" stroke=\"#000000\" stroke-opacity=\".16\" stroke-width=\".3\"/>")
            }
        }
    }

    private fun StringBuilder.label(x:Int,y:Int,text:String,size:Int,color:String="#9cacb0") {
        append("<text x=\"$x\" y=\"$y\" font-family=\"sans-serif\" font-size=\"$size\" fill=\"$color\">${escape(text)}</text>")
    }
    private fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
    private fun shade(color:String,amount:Double):String {
        if (!color.startsWith("#")) return color
        val value = color.substring(1).toInt(16)
        val components = listOf((value shr 16) and 255,(value shr 8) and 255,value and 255)
        return "#"+components.joinToString("") { (it*amount).roundToInt().coerceIn(0,255).toString(16).padStart(2,'0') }
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
        else -> {
            // HSL(32%, 62%) as RGB so every material supports depth/face shading.
            val hue = Math.floorMod(block.hashCode(),360) / 30.0
            "#" + listOf(0,8,4).joinToString("") { n ->
                val k = (n + hue) % 12
                val channel = 0.62 - 0.32 * 0.38 * maxOf(-1.0,minOf(k-3,9-k,1.0))
                (channel * 255).roundToInt().toString(16).padStart(2,'0')
            }
        }
    }
}
