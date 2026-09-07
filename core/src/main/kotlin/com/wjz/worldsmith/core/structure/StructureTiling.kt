package com.wjz.worldsmith.core.structure

/** Storage fragments of ONE logical building; they never become assembly members. */
data class StructureTile(val offset: BuildPos, val geometry: CompiledStructure)
object StructureTiling {
    const val TILE_SIZE=32
    const val MAX_TILES=128
    @JvmStatic fun tiles(g:CompiledStructure):List<StructureTile> {
        if(!g.drawingSource)return listOf(StructureTile(BuildPos(0,0,0),g))
        val groups=g.voxels.groupBy { BuildPos(it.position.x/TILE_SIZE,it.position.y/TILE_SIZE,it.position.z/TILE_SIZE) }
        require(groups.size<=MAX_TILES) { "Drawing exceeds 128 storage fragments" }
        return groups.entries.sortedWith(compareBy({it.key.y},{it.key.z},{it.key.x})).map { (key,cells)->
            val offset=BuildPos(key.x*TILE_SIZE,key.y*TILE_SIZE,key.z*TILE_SIZE)
            val size=BuildPos(minOf(TILE_SIZE,g.size.x-offset.x),minOf(TILE_SIZE,g.size.y-offset.y),minOf(TILE_SIZE,g.size.z-offset.z))
            fun local(p:BuildPos)=BuildPos(p.x-offset.x,p.y-offset.y,p.z-offset.z)
            fun inside(p:BuildPos)=p.x in offset.x until offset.x+size.x && p.y in offset.y until offset.y+size.y && p.z in offset.z until offset.z+size.z
            fun clip(b:BuildBox):BuildBox? {
                val a=BuildPos(maxOf(b.from.x,offset.x),maxOf(b.from.y,offset.y),maxOf(b.from.z,offset.z))
                val z=BuildPos(minOf(b.to.x,offset.x+size.x-1),minOf(b.to.y,offset.y+size.y-1),minOf(b.to.z,offset.z+size.z-1))
                return if(a.x<=z.x&&a.y<=z.y&&a.z<=z.z)BuildBox(local(a),local(z))else null
            }
            val interactions=g.interactions.withIndex().filter { inside(it.value.at) }
            StructureTile(offset,g.copy(size=size,origin=BuildPos(0,0,0),voxels=cells.map {it.copy(position=local(it.position))},
                keepClear=g.keepClear.mapNotNull(::clip),protectedAreas=g.protectedAreas.mapNotNull(::clip),
                interactions=interactions.map { (_,v)->when(v) {is StructureInteraction.Container->v.copy(at=local(v.at));is StructureInteraction.Sign->v.copy(at=local(v.at));is StructureInteraction.Banner->v.copy(at=local(v.at))} },
                interactionIds=interactions.map {it.index},anchors=g.anchors.filterValues(::inside).mapValues { local(it.value) },lighting=null,ports=emptyList(),storageFragment=true))
        }
    }
}
