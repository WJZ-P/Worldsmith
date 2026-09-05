package com.wjz.worldsmith.core.structure

import kotlin.math.*

/** Bounded shape rasterisers. The caller owns transforms, materials, diagnostics and the shared work budget. */
internal object StructurePrimitives {
    fun interface Writer { fun put(at:BuildPos, material:String?, properties:Map<String,String>, passable:Boolean) }
    fun emit(op:BuildOperation, out:Writer, visit:()->Unit) {
        fun put(p:BuildPos,m:String?,props:Map<String,String> = emptyMap(),pass:Boolean=false)=out.put(p,m,props,pass)
        fun local(p:BuildPos) { require(listOf(p.x,p.y,p.z).all { it in -128..128 }) { "Local coordinates must stay within -128..128" } }
        fun box(a:BuildPos,b:BuildPos) {
            local(a);local(b)
            require(b.x-a.x in 0..63 && b.y-a.y in 0..63 && b.z-a.z in 0..63) { "Shape boxes need ordered endpoints and spans of at most 64 blocks" }
        }
        when(op) {
            is BuildOperation.Ellipsoid -> {
                box(op.from,op.to); require(op.thickness in 0..8)
                val c=doubleArrayOf((op.from.x+op.to.x)/2.0,(op.from.y+op.to.y)/2.0,(op.from.z+op.to.z)/2.0)
                val r=doubleArrayOf((op.to.x-op.from.x+1)/2.0,(op.to.y-op.from.y+1)/2.0,(op.to.z-op.from.z+1)/2.0)
                fun inside(x:Int,y:Int,z:Int,shrink:Int):Boolean {
                    if(r.any { it<=shrink }) return false
                    return (x-c[0]).pow(2)/(r[0]-shrink).pow(2)+(y-c[1]).pow(2)/(r[1]-shrink).pow(2)+(z-c[2]).pow(2)/(r[2]-shrink).pow(2)<=1.0
                }
                for(y in op.from.y..op.to.y)for(z in op.from.z..op.to.z)for(x in op.from.x..op.to.x) {
                    visit();if(inside(x,y,z,0))put(BuildPos(x,y,z),if(op.thickness>0 && inside(x,y,z,op.thickness))null else op.material)
                }
            }
            is BuildOperation.Cylinder -> {
                box(op.from,op.to);require(op.topScale in 0.0..1.0 && op.thickness in 0..8)
                val cx=(op.from.x+op.to.x)/2.0;val cz=(op.from.z+op.to.z)/2.0
                for(y in op.from.y..op.to.y) {
                    val t=(y-op.from.y).toDouble()/max(1,op.to.y-op.from.y)
                    val scale=1+(op.topScale-1)*t
                    val rx=max(0.75,(op.to.x-op.from.x+1)*0.5*scale);val rz=max(0.75,(op.to.z-op.from.z+1)*0.5*scale)
                    for(z in op.from.z..op.to.z)for(x in op.from.x..op.to.x) {
                        visit();val outer=(x-cx).pow(2)/rx.pow(2)+(z-cz).pow(2)/rz.pow(2)<=1
                        if(!outer)continue
                        val thick=op.thickness
                        val inner=thick>0 && rx>thick && rz>thick && y-op.from.y>=thick && op.to.y-y>=thick &&
                            (x-cx).pow(2)/(rx-thick).pow(2)+(z-cz).pow(2)/(rz-thick).pow(2)<1
                        put(BuildPos(x,y,z),if(inner)null else op.material)
                    }
                }
            }
            is BuildOperation.Polygon -> {
                require(op.points.size in 3..32 && op.points.distinct().size==op.points.size) { "Use 3..32 distinct polygon vertices" }
                val p=op.points;val minX=p.minOf { it.x };val maxX=p.maxOf { it.x };val minZ=p.minOf { it.z };val maxZ=p.maxOf { it.z }
                box(BuildPos(minX,op.minY,minZ),BuildPos(maxX-1,op.maxY,maxZ-1))
                val area=p.indices.sumOf { i -> val a=p[i];val b=p[(i+1)%p.size];a.x.toLong()*b.z-b.x.toLong()*a.z }
                require(area!=0L) { "Polygon needs nonzero area" }
                fun orient(a:BuildPoint2,b:BuildPoint2,c:BuildPoint2)=(b.x-a.x).toLong()*(c.z-a.z)-(b.z-a.z).toLong()*(c.x-a.x)
                fun on(a:BuildPoint2,b:BuildPoint2,c:BuildPoint2)=orient(a,b,c)==0L && c.x in min(a.x,b.x)..max(a.x,b.x) && c.z in min(a.z,b.z)..max(a.z,b.z)
                for(i in p.indices)for(j in i+1 until p.size) {
                    if(j==i+1 || i==0 && j==p.lastIndex)continue
                    val a=p[i];val b=p[(i+1)%p.size];val c=p[j];val d=p[(j+1)%p.size]
                    require(!(on(a,b,c)||on(a,b,d)||on(c,d,a)||on(c,d,b)||
                        (orient(a,b,c)>0)!=(orient(a,b,d)>0) && (orient(c,d,a)>0)!=(orient(c,d,b)>0))) { "Polygon edges must not intersect" }
                }
                for(z in minZ until maxZ)for(x in minX until maxX) {
                    visit();var inside=false
                    for(i in p.indices) {
                        val a=p[i];val b=p[(i+1)%p.size]
                        if((a.z>z+0.5)!=(b.z>z+0.5) && x+0.5<(b.x-a.x)*(z+0.5-a.z)/(b.z-a.z)+a.x)inside=!inside
                    }
                    if(inside)for(y in op.minY..op.maxY)put(BuildPos(x,y,z),op.material)
                }
            }
            is BuildOperation.Arch -> {
                box(op.from,op.to);require(op.springY in op.from.y until op.to.y && op.thickness in 1..8)
                val lo=if(op.spanAxis==RoofAxis.X)op.from.x else op.from.z
                val hi=if(op.spanAxis==RoofAxis.X)op.to.x else op.to.z
                val rx=(hi-lo+1)/2.0;val ry=(op.to.y-op.springY+1).toDouble();val centre=(lo+hi)/2.0
                require(rx>op.thickness && ry>op.thickness) { "Arch thickness must leave an opening" }
                for(y in op.from.y..op.to.y)for(z in op.from.z..op.to.z)for(x in op.from.x..op.to.x) {
                    visit();val u=(if(op.spanAxis==RoofAxis.X)x else z)-centre
                    val v=max(0,y-op.springY).toDouble()
                    val outer=u*u/(rx*rx)+v*v/(ry*ry)<=1
                    val inner=u*u/(rx-op.thickness).pow(2)+v*v/(ry-op.thickness).pow(2)<1
                    if(outer)put(BuildPos(x,y,z),if(inner)null else op.material)
                }
            }
            is BuildOperation.Curve -> {
                require(op.points.size in 3..4 && op.radius in 0.0..8.0) { "CURVE needs 3 or 4 Bezier controls and radius 0..8" }
                op.points.forEach(::local)
                val length=op.points.zipWithNext().sumOf { (a,b)->sqrt((a.x-b.x).toDouble().pow(2)+(a.y-b.y).toDouble().pow(2)+(a.z-b.z).toDouble().pow(2)) }
                val steps=max(1,ceil(length*4).toInt());require(steps<=2048)
                fun sample(t:Double):BuildPos {
                    val controls=op.points.map { doubleArrayOf(it.x.toDouble(),it.y.toDouble(),it.z.toDouble()) }.toMutableList()
                    for(n in controls.lastIndex downTo 1)for(i in 0 until n)for(axis in 0..2) controls[i][axis]=controls[i][axis]*(1-t)+controls[i+1][axis]*t
                    return BuildPos(controls[0][0].roundToInt(),controls[0][1].roundToInt(),controls[0][2].roundToInt())
                }
                fun stamp(p:BuildPos) {
                    val r=ceil(op.radius).toInt()
                    for(dy in -r..r)for(dz in -r..r)for(dx in -r..r) {
                        visit();if(dx*dx+dy*dy+dz*dz<=op.radius*op.radius)put(BuildPos(p.x+dx,p.y+dy,p.z+dz),op.material)
                    }
                }
                var last=sample(0.0);stamp(last)
                for(i in 1..steps) {
                    val next=sample(i.toDouble()/steps)
                    while(last.x!=next.x){last=last.copy(x=last.x+(next.x-last.x).sign);stamp(last)}
                    while(last.y!=next.y){last=last.copy(y=last.y+(next.y-last.y).sign);stamp(last)}
                    while(last.z!=next.z){last=last.copy(z=last.z+(next.z-last.z).sign);stamp(last)}
                }
            }
            is BuildOperation.Door -> {
                local(op.at);require(op.hinge in listOf("left","right")) { "Door hinge must be left or right" }
                val props=mapOf("facing" to op.facing.name.lowercase(),"hinge" to op.hinge,"open" to op.open.toString())
                put(op.at,op.material,props+ ("half" to "lower"),true)
                put(op.at.copy(y=op.at.y+1),op.material,props+ ("half" to "upper"),true)
            }
            is BuildOperation.Staircase -> {
                local(op.at);require(op.steps in 1..48 && op.width in 1..8 && op.headroom in 2..6)
                val right=op.facing.rotate(1)
                for(i in 0 until op.steps)for(w in 0 until op.width) {
                    val p=BuildPos(op.at.x+op.facing.dx*i+right.dx*w,op.at.y+i,op.at.z+op.facing.dz*i+right.dz*w)
                    put(p,op.material,mapOf("facing" to op.facing.name.lowercase(),"half" to "bottom","shape" to "straight"))
                    for(h in 1..op.headroom)put(p.copy(y=p.y+h),null)
                    if(op.fillMaterial!=null)for(y in op.at.y until p.y)put(p.copy(y=y),op.fillMaterial)
                }
            }
            else -> error("Not a primitive: ${op.id}")
        }
    }
}
