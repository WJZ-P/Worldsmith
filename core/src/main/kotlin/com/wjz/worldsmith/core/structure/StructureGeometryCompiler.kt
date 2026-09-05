package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.validation.Diagnostic
import com.wjz.worldsmith.core.validation.DiagnosticSeverity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StructureBuildException(val diagnostic: Diagnostic) : IllegalArgumentException(diagnostic.message)

/** Stateless and bounded; shared by MCP validation, previews and Minecraft template export. */
object StructureGeometryCompiler {
    const val MAX_DIMENSION = 64
    const val MAX_OPERATIONS = 2048
    const val MAX_WORK = 524288
    const val MAX_VOXELS = 131072
    private val ID = Regex("^[a-z0-9_./-]{1,64}$")
    private val BLOCK = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val AIR = BuildMaterial("minecraft:air")

    @JvmStatic
    fun compile(blueprint: StructureBlueprint): CompiledStructure = Builder(blueprint).compile()

    private class Builder(val blueprint: StructureBlueprint) {
        val cells = LinkedHashMap<BuildPos, StructureVoxel>()
        val writers = HashMap<BuildPos, String>()
        val writtenOperations = linkedSetOf<String>()
        val lastClears = HashMap<BuildPos, String>()
        var work = 0
        var operations = 0

        fun compile(): CompiledStructure {
            check(blueprint.schemaVersion == 1, "schemaVersion", "UNSUPPORTED_SCHEMA", "Structure schema must be 1")
            check(blueprint.id.matches(Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")), "id", "INVALID_STRUCTURE_ID", "Use a short lowercase identifier without path separators")
            val size = blueprint.size
            check(listOf(size.x, size.y, size.z).all { it in 1..MAX_DIMENSION }, "size", "STRUCTURE_SIZE_OUT_OF_RANGE", "Each dimension must be 1..$MAX_DIMENSION")
            point(blueprint.origin, "origin")
            check(blueprint.origin.y == 0, "origin.y", "STRUCTURE_ORIGIN_DATUM", "Origin Y is the foundation datum and must be zero")
            check(blueprint.palette.isNotEmpty() && blueprint.palette.size <= 128, "palette", "STRUCTURE_PALETTE_SIZE", "Declare 1..128 palette entries")
            blueprint.palette.forEach { (key, value) ->
                check(ID.matches(key), "palette.$key", "INVALID_MATERIAL_NAME", "Palette keys must be short lowercase identifiers")
                check(value.block.length <= 160 && BLOCK.matches(value.block), "palette.$key.block", "INVALID_BLOCK_ID", "Use a namespaced block id, at most 160 characters")
                check(value.properties.size <= 16 && value.properties.all { (k, v) -> k.length <= 64 && v.length <= 64 && k.matches(Regex("[a-z0-9_]+")) && v.matches(Regex("[a-z0-9_]+")) }, "palette.$key.properties", "INVALID_BLOCK_PROPERTIES", "Properties must be a small string map with short keys and values")
            }
            check(blueprint.modules.size <= 64, "modules", "TOO_MANY_MODULES", "At most 64 local modules")
            check(blueprint.build.isNotEmpty(), "build", "EMPTY_STRUCTURE_BUILD", "A structure needs build operations")
            validateModuleGraph()
            execute(blueprint.build, Transform(), "build", 0)
            check(cells.values.any { !it.material.isAir() }, "build", "EMPTY_STRUCTURE", "The final template must contain solid authored content")
            check(blueprint.keepClear.size <= 32, "keepClear", "TOO_MANY_CLEAR_VOLUMES", "At most 32 clearance boxes")
            blueprint.keepClear.forEachIndexed { i, box -> box(box.from, box.to, "keepClear[$i]"); point(box.from,"keepClear[$i].from"); point(box.to,"keepClear[$i].to") }
            return CompiledStructure(blueprint.id, blueprint.size, blueprint.origin,
                cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x })).map { it.value }, blueprint.keepClear, work, qualityDiagnostics())
        }

        fun qualityDiagnostics(): List<Diagnostic> {
            val diagnostics = mutableListOf<Diagnostic>()
            val visible = writers.values.toHashSet()
            writtenOperations.filter { it !in visible }.forEach { path ->
                diagnostics += Diagnostic(path, "OPERATION_FULLY_OVERWRITTEN", DiagnosticSeverity.WARNING,
                    "Every cell written by this operation was overwritten later; keep it only when this is intentional")
            }
            val refilled = lastClears.entries.filter { !cells.getValue(it.key).material.isAir() }
                .groupingBy { it.value }.eachCount()
            refilled.forEach { (path, count) ->
                diagnostics += Diagnostic(path, "CLEAR_REGION_REFILLED", DiagnosticSeverity.WARNING,
                    "$count cleared cells were filled again later; inspect the opening, room or passage")
            }
            return if (diagnostics.size <= 64) diagnostics else diagnostics.take(64) + Diagnostic(
                "build", "MORE_STRUCTURE_WARNINGS", DiagnosticSeverity.WARNING,
                "${diagnostics.size - 64} additional overwrite warnings omitted; repair the first group and inspect again",
            )
        }

        fun validateModuleGraph() {
            val visited = mutableSetOf<String>()
            val visiting = mutableSetOf<String>()
            var declaredOperations = 0
            fun visitOps(ops: List<BuildOperation>, depth: Int, visit: (String, Int) -> Unit) {
                check(depth <= 8, "modules", "STRUCTURE_RECURSION_LIMIT", "Module and repeat depth must stay within 8")
                declaredOperations += ops.size
                check(declaredOperations <= MAX_OPERATIONS, "build", "STRUCTURE_DECLARATION_BUDGET", "Declared operations, including unused modules, must stay within $MAX_OPERATIONS")
                check(ops.map { it.id }.toSet().size == ops.size, "build", "DUPLICATE_BUILD_OPERATION", "Operation ids must be unique within every declared list")
                ops.forEach { op -> when(op) {
                    is BuildOperation.Instance -> visit(op.module, depth + 1)
                    is BuildOperation.Repeat -> visitOps(op.build, depth + 1, visit)
                    else -> Unit
                } }
            }
            fun visit(name: String, depth: Int) {
                check(depth <= 8, "modules.$name", "STRUCTURE_RECURSION_LIMIT", "Module depth exceeds 8")
                check(name !in visiting, "modules.$name", "STRUCTURE_MODULE_CYCLE", "Module '$name' references itself through an instance chain")
                val ops = blueprint.modules[name] ?: fail("modules.$name", "UNKNOWN_STRUCTURE_MODULE", "Unknown module '$name'")
                check(ID.matches(name) && ops.isNotEmpty(), "modules.$name", "INVALID_STRUCTURE_MODULE", "Modules need a short lowercase name and non-empty build list")
                if (name in visited) return
                visiting += name
                visitOps(ops, depth, ::visit)
                visiting -= name
                visited += name
            }
            blueprint.modules.keys.forEach { visit(it, 0) }
            visitOps(blueprint.build, 0, ::visit)
        }

        fun execute(ops: List<BuildOperation>, transform: Transform, path: String, depth: Int) {
            check(depth <= 8, path, "STRUCTURE_RECURSION_LIMIT", "Nested construction exceeds depth 8")
            check(ops.map { it.id }.toSet().size == ops.size, path, "DUPLICATE_BUILD_OPERATION", "Operation ids must be unique within a list")
            for ((index, op) in ops.withIndex()) {
                val at = "$path[$index](${op.id})"
                check(ID.matches(op.id), at, "INVALID_OPERATION_ID", "Use a short lowercase operation id")
                check(++operations <= MAX_OPERATIONS, at, "STRUCTURE_OPERATION_BUDGET", "Expanded operation count exceeds $MAX_OPERATIONS")
                when(op) {
                    is BuildOperation.SetBlock -> emit(op.at, material(op.material,at), transform, at)
                    is BuildOperation.Fill -> each(op.from,op.to,at) { emit(it,material(op.material,at),transform,at) }
                    is BuildOperation.Clear -> each(op.from,op.to,at) { emit(it,AIR,transform,at, true) }
                    is BuildOperation.Shell -> {
                        box(op.from,op.to,at)
                        val smallest = minOf(op.to.x-op.from.x+1,op.to.y-op.from.y+1,op.to.z-op.from.z+1)
                        check(op.thickness in 1..8 && op.thickness*2 < smallest, at,"SHELL_HAS_NO_INTERIOR","Shell thickness must leave an interior in all three dimensions")
                        val wall = material(op.material,at)
                        each(op.from,op.to,at) { p ->
                            val edge = minOf(p.x-op.from.x,op.to.x-p.x,p.y-op.from.y,op.to.y-p.y,p.z-op.from.z,op.to.z-p.z)
                            emit(p,if(edge < op.thickness) wall else AIR,transform,at)
                        }
                    }
                    is BuildOperation.Line -> {
                        local(op.from,at); local(op.to,at)
                        val m = material(op.material,at)
                        val steps=maxOf(abs(op.to.x-op.from.x),abs(op.to.y-op.from.y),abs(op.to.z-op.from.z),1)
                        var p = op.from
                        emit(p,m,transform,at)
                        for(i in 1..steps) {
                            val target=BuildPos(op.from.x+((op.to.x-op.from.x)*i.toDouble()/steps).roundToInt(),op.from.y+((op.to.y-op.from.y)*i.toDouble()/steps).roundToInt(),op.from.z+((op.to.z-op.from.z)*i.toDouble()/steps).roundToInt())
                            while(p.x!=target.x) { p=p.copy(x=p.x+if(target.x>p.x)1 else -1); emit(p,m,transform,at) }
                            while(p.y!=target.y) { p=p.copy(y=p.y+if(target.y>p.y)1 else -1); emit(p,m,transform,at) }
                            while(p.z!=target.z) { p=p.copy(z=p.z+if(target.z>p.z)1 else -1); emit(p,m,transform,at) }
                        }
                    }
                    is BuildOperation.Roof -> roof(op,transform,at)
                    is BuildOperation.Repeat -> {
                        check(op.count in 1..64,at,"STRUCTURE_REPEAT_LIMIT","Repeat count must be 1..64")
                        check(op.build.isNotEmpty(),at,"EMPTY_STRUCTURE_REPEAT","A repeat needs a non-empty build list")
                        local(op.step,at)
                        for(i in 0 until op.count) execute(op.build,transform.then(BuildPos(op.step.x*i,op.step.y*i,op.step.z*i),0),"$at.repeat[$i]",depth+1)
                    }
                    is BuildOperation.Instance -> {
                        local(op.at,at)
                        val module = blueprint.modules[op.module] ?: fail(at,"UNKNOWN_STRUCTURE_MODULE","Unknown module '${op.module}'")
                        execute(module,transform.then(op.at,op.rotation.ordinal),"$at.${op.module}",depth+1)
                    }
                }
            }
        }

        fun roof(op: BuildOperation.Roof, transform: Transform, path: String) {
            box(op.from,op.to,path)
            val width=op.to.x-op.from.x+1; val length=op.to.z-op.from.z+1
            val rise=op.to.y-op.from.y
            val half=if(op.style==RoofStyle.HIP) min(width-1,length-1)/2 else if(op.ridgeAxis==RoofAxis.Z)(width-1)/2 else (length-1)/2
            if(op.style==RoofStyle.FLAT) check(rise==0 && op.stairMaterial==null,path,"FLAT_ROOF_HEIGHT","FLAT roof has one Y layer and no stair material")
            else check(half>0 && rise in 1..half,path,"ROOF_SLOPE_OUT_OF_RANGE","Roof rise must be 1..half its cross-span, so steps remain connected")
            check(op.style==RoofStyle.GABLE || op.ridgeAxis==RoofAxis.Z,path,"UNUSED_ROOF_AXIS","Only GABLE consumes ridgeAxis; leave it omitted for FLAT/HIP")
            val ridge=material(op.material,path)
            val stair=op.stairMaterial?.let { material(it,path) }
            for(x in op.from.x..op.to.x) for(z in op.from.z..op.to.z) {
                val dx=min(x-op.from.x,op.to.x-x); val dz=min(z-op.from.z,op.to.z-z)
                val distance=when(op.style){RoofStyle.FLAT->0;RoofStyle.GABLE->if(op.ridgeAxis==RoofAxis.Z)dx else dz;RoofStyle.HIP->min(dx,dz)}
                val y=if(op.style==RoofStyle.FLAT)op.from.y else op.from.y+min(rise, distance*rise/half)
                val facing=if(op.style==RoofStyle.GABLE && op.ridgeAxis==RoofAxis.Z || op.style==RoofStyle.HIP && dx<=dz) {
                    if(x-op.from.x <= op.to.x-x)"east" else "west"
                } else {if(z-op.from.z <= op.to.z-z)"south" else "north"}
                val block=if(stair!=null && y<op.to.y) stair.copy(properties=stair.properties+mapOf("facing" to facing,"half" to "bottom","shape" to "straight")) else ridge
                emit(BuildPos(x,y,z),block,transform,path)
            }
        }

        fun material(key: String,path: String)=blueprint.palette[key] ?: fail(path,"UNKNOWN_STRUCTURE_MATERIAL","Unknown palette entry '$key'")
        fun local(p: BuildPos,path: String) = check(listOf(p.x,p.y,p.z).all { it in -128..128 },path,"BUILD_COORDINATE_OUT_OF_RANGE","Local operation coordinates must stay within -128..128")
        fun point(p: BuildPos,path: String) = check(p.x in 0 until blueprint.size.x && p.y in 0 until blueprint.size.y && p.z in 0 until blueprint.size.z,path,"STRUCTURE_BOUNDS_EXCEEDED","Position $p is outside size ${blueprint.size}")
        fun box(from: BuildPos,to: BuildPos,path: String) { local(from,path);local(to,path);check(from.x<=to.x && from.y<=to.y && from.z<=to.z,path,"REVERSED_BUILD_BOX","Box from must not exceed to on any axis") }
        fun each(from: BuildPos,to: BuildPos,path: String,action:(BuildPos)->Unit) {
            box(from,to,path)
            val count=(to.x-from.x+1).toLong()*(to.y-from.y+1)*(to.z-from.z+1)
            check(count+work<=MAX_WORK,path,"STRUCTURE_WORK_BUDGET","Construction exceeds $MAX_WORK voxel visits")
            for(y in from.y..to.y) for(z in from.z..to.z) for(x in from.x..to.x) action(BuildPos(x,y,z))
        }
        fun emit(p: BuildPos,m: BuildMaterial,t: Transform,path: String, clear: Boolean = false) {
            local(p,path)
            check(++work<=MAX_WORK,path,"STRUCTURE_WORK_BUDGET","Construction exceeds $MAX_WORK voxel visits")
            val actual=t.apply(p)
            point(actual,path)
            cells[actual]=StructureVoxel(actual,m,t.rotation)
            writers[actual] = path
            writtenOperations += path
            if (clear) lastClears[actual] = path
            check(cells.size<=MAX_VOXELS,path,"STRUCTURE_VOXEL_BUDGET","Template exceeds $MAX_VOXELS authored cells")
        }
    }

    private data class Transform(val x:Int=0,val y:Int=0,val z:Int=0,val rotation:Int=0) {
        fun apply(p:BuildPos):BuildPos {val r=rotate(p,rotation);return BuildPos(r.x+x,r.y+y,r.z+z)}
        fun then(offset:BuildPos,r:Int):Transform {val p=apply(offset);return Transform(p.x,p.y,p.z,(rotation+r)%4)}
    }

    @JvmStatic
    fun rotate(p:BuildPos,quarterTurns:Int):BuildPos=when(Math.floorMod(quarterTurns,4)) {
        0->p;1->BuildPos(-p.z,p.y,p.x);2->BuildPos(-p.x,p.y,-p.z);else->BuildPos(p.z,p.y,-p.x)
    }

    private fun check(ok:Boolean,path:String,code:String,message:String) {if(!ok)fail(path,code,message)}
    private fun fail(path:String,code:String,message:String):Nothing=throw StructureBuildException(Diagnostic(path,code,DiagnosticSeverity.ERROR,message))
}
