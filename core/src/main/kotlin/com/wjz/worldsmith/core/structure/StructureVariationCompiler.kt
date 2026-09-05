package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity

/** Stateless keyed randomness: unrelated choices do not shift an RNG stream. */
internal object StructureVariationCompiler {
    fun unit(seed:Long,key:String):Double {
        var x=seed xor -7046029254386353131L
        for(c in key)x=(x xor c.code.toLong())*1099511628211L
        x=(x xor (x ushr 30))*-4658895280553007687L
        x=(x xor (x ushr 27))*-7723592293110705685L
        return ((x xor (x ushr 31)) ushr 11)/9007199254740992.0
    }
    fun <T> choose(values:List<T>,weight:(T)->Int,seed:Long,key:String):T {
        var remaining=(unit(seed,key)*values.sumOf(weight)).toInt()
        for(v in values){remaining-=weight(v);if(remaining<0)return v}
        return values.last()
    }
    fun validate(b:StructureBlueprint) {
        fun check(ok:Boolean,path:String,message:String) { if(!ok)throw StructureBuildException(Diagnostic(path,"INVALID_STRUCTURE_VARIATION",DiagnosticSeverity.ERROR,message)) }
        val v=b.variation
        check(v.count in 1..8,"variation.count","Use 1..8 precompiled variants")
        check(v.materials.size<=128 && v.decay.size<=16 && v.protectedAreas.size<=32,"variation","Variation has too many rules or protected areas")
        v.materials.forEach { (slot,choices)->check(slot in b.palette && choices.size in 1..16 && choices.all {it.material in b.palette && it.weight in 1..10000},"variation.materials.$slot","Choose existing palette entries with positive bounded weights") }
        for((i,d) in v.decay.withIndex())check(d.materials.isNotEmpty()&&d.materials.size<=128&&d.materials.all {it in b.palette}&&d.probability in 0.0..1.0&&(d.replacement==null||d.replacement in b.palette),"variation.decay[$i]","Decay needs palette references and probability 0..1")
        for(box in v.protectedAreas+v.decay.mapNotNull {it.region})check(box.from.x>=0&&box.from.y>=0&&box.from.z>=0&&box.to.x<b.size.x&&box.to.y<b.size.y&&box.to.z<b.size.z&&box.from.x<=box.to.x&&box.from.y<=box.to.y&&box.from.z<=box.to.z,"variation","Variation boxes must be ordered and inside the blueprint")
    }
    fun palette(b:StructureBlueprint,seed:Long)=b.palette.mapValues { (slot,value)->
        b.variation.materials[slot]?.let { b.palette.getValue(choose(it,WeightedMaterial::weight,seed,"palette:$slot").material) } ?: value
    }
    fun decay(b:StructureBlueprint,source:Map<BuildPos,StructureVoxel>,palette:Map<String,BuildMaterial>,seed:Long,protected:Set<BuildPos>):Map<BuildPos,StructureVoxel> {
        fun contains(box:BuildBox,p:BuildPos)=p.x in box.from.x..box.to.x && p.y in box.from.y..box.to.y && p.z in box.from.z..box.to.z
        val result=source.toMutableMap();val special=b.interactions.map {it.at}.toSet()
        for((p,voxel) in source) {
            if(p.y==0||voxel.material.isAir()||voxel.passable||p in protected||p in special||b.variation.protectedAreas.any {contains(it,p)}||b.ports.any { it.at.x==p.x&&it.at.z==p.z&&p.y in it.at.y-1..it.at.y+1 })continue
            for((i,rule) in b.variation.decay.withIndex()) {
                if(rule.region!=null&&!contains(rule.region,p)||rule.materials.none {palette.getValue(it).block==voxel.material.block})continue
                if(rule.exposedOnly && listOf(BuildPos(1,0,0),BuildPos(-1,0,0),BuildPos(0,1,0),BuildPos(0,-1,0),BuildPos(0,0,1),BuildPos(0,0,-1)).none {d->source[BuildPos(p.x+d.x,p.y+d.y,p.z+d.z)]?.material?.isAir()!=false})continue
                if(unit(seed,"decay:$i:${p.x}:${p.y}:${p.z}")<rule.probability) {
                    result[p]=voxel.copy(material=rule.replacement?.let {palette.getValue(it)} ?: BuildMaterial("minecraft:air"));break
                }
            }
        }
        return result
    }
}
