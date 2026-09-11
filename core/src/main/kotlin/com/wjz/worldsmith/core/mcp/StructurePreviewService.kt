package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.draw.*
import com.wjz.worldsmith.core.structure.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Debug views operate on frozen data. Semantic errors never erase the image needed to repair them. */
class StructurePreviewService {
    var paletteForSession:(String)->PreviewMaterialPalette.Palette = {PreviewMaterialPalette.Palette()}
    private val frames=object:LinkedHashMap<String,Box>(16,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Box>?)=size>256}
    fun render(key:String,drawing:DrawStructure,args:JsonObject,components:Map<String,BuildBox> = emptyMap(),inspection:StructureInspection?=null):McpToolResult {
        val start=System.nanoTime();val region=args["region"]?.let {box(McpJson.decode(it))}
        val views=McpJson.strings(args,"views").ifEmpty {listOf(args["view"]?.jsonPrimitive?.content ?: "isometric")}
        require(views.size in 1..4 && views.all {it in DrawPreview.VIEWS}) {"Choose one to four supported preview views"}
        val renderMode=args["renderMode"]?.jsonPrimitive?.content ?: "material"
        require(renderMode in DrawPreview.RENDER_MODES) {"renderMode must be material or clay"}
        val slice=args["sliceY"]?.jsonPrimitive?.int
        val cutaway=args["cutaway"]?.jsonPrimitive?.boolean ?: false
        require((!cutaway && "slice" !in views) || (slice!=null && slice in drawing.bounds().min().y()..drawing.bounds().max().y())) {"sliceY must be inside the drawing for cutaway or slice views"}
        val include=McpJson.strings(args,"components");val exclude=McpJson.strings(args,"hideComponents")
        require(include.size+exclude.size<=32&& (include+exclude).all {it in components}) {"Use existing component ids (at most 32 filters)"}
        val overlays=McpJson.strings(args,"overlays");require(overlays.all {it in listOf("ports","access","clearance","lighting","errors")}) {"Unknown preview overlay"}
        val frame=synchronized(frames){args["frame"]?.let {box(McpJson.decode(it))} ?: region ?: frames.getOrPut(key){drawing.bounds()}}
        val source=drawing.voxels().filter {v->val p=BuildPos(v.position().x(),v.position().y(),v.position().z())
            (!cutaway||v.position().y()<=slice!!)&&(region==null||region.contains(v.position()))&&(include.isEmpty()||include.any {StructureCheckService.contains(components.getValue(it),p)})&&exclude.none {StructureCheckService.contains(components.getValue(it),p)}
        }
        val selected=DrawStructure(drawing.bounds(),source,drawing.anchors())
        val markers=mutableListOf<DrawPreview.Marker>()
        fun mark(p:BuildPos,colour:Int,label:String=""){val v=Vec3i(p.x,p.y,p.z);if((!cutaway||p.y<=slice!!)&&(region==null||region.contains(v)))markers+=DrawPreview.Marker(v,colour,label)}
        if(inspection!=null) {
            for((id,variants)in inspection.geometries)variants.firstOrNull()?.let {g->
                fun original(p:BuildPos)=BuildPos(p.x+g.sourceMin.x,p.y+g.sourceMin.y,p.z+g.sourceMin.z)
                if("ports" in overlays)g.ports.forEach {mark(original(it.at),0x55ddff,it.id)}
                if("clearance" in overlays)g.keepClear.forEach {mark(original(it.from),0xffbd55);mark(original(it.to),0xffbd55)}
                if("access" in overlays){g.reachableFeet.filterIndexed {i,_->i%maxOf(1,(g.reachableFeet.size+2047)/2048)==0}.forEach {mark(original(it),0x8dd4ff)};g.ports.forEach {mark(original(it.at),0x8dd4ff,"entrance")}}
                if("lighting" in overlays)inspection.lighting["$id:0"]?.samples?.forEach {mark(original(it.position),if(it.level<8)0xff4355 else 0x70dc85)}
            }
            if("errors" in overlays)inspection.report.stages.values.flatMap {it.diagnostics}.forEach {d->d.position?.let {mark(it,0xff4e8a,d.code)}}
        }
        val palette=if(renderMode=="material")paletteForSession(args["sessionId"]?.jsonPrimitive?.content ?: key.substringBefore(':'))else PreviewMaterialPalette.Palette()
        val images=views.map {view->McpImage(Base64.getEncoder().encodeToString(DrawPreview.png(selected,view,slice,frame,markers,renderMode,palette.colors)))}
        return McpToolResult.success(buildJsonObject {
            put("previewType","voxel-model-not-game-screenshot");put("frame",McpJson.encode(buildBox(frame)));put("views",McpJson.encode(views))
            put("renderMode",renderMode);put("cutaway",cutaway);slice?.let {put("sliceY",it)}
            put("customMaterialColours",McpJson.encode(palette.colors));put("materialWarnings",McpJson.encode(palette.warnings))
            put("materialColourSource",if(palette.colors.isEmpty())"generic approximate palette" else "alpha-weighted averages of the session's verified PNG assets; not texture sampling")
            put("imageLabels",McpJson.encode(views.map {"$it / $renderMode"+(if(cutaway)" / cutaway y <= $slice" else "")}))
            put("viewDirections","front: north (-Z); back: south (+Z); left: west (-X); right: east (+X); top/slice: above (+Y); isometric: NE; isometric_back: SW")
            put("visualAssessment","Not scored. Inspect form, proportions, depth, usable spaces and theme; material colours are approximate, not a texture or lighting proof. Clay top view shades height relative to the fixed frame.")
            put("overlays",McpJson.encode(overlays));put("overlaySemantics","xray debug samples; simplified block shapes, not actual game lighting")
            put("components",McpJson.encode(components));put("visibleAuthoredCells",source.size);put("previewMillis",(System.nanoTime()-start)/1_000_000)
            inspection?.let {put("checks",McpJson.encode(it.report))}
        },images=images)
    }
    companion object {
        /** One discoverable vocabulary for frozen drawings, blueprints and assembled layouts. */
        fun optionSchema(componentFilters:Boolean=true):Map<String,JsonObject> = buildMap {
            fun choice(values:List<String>)=buildJsonObject {put("type","string");put("enum",McpJson.encode(values))}
            put("view",choice(DrawPreview.VIEWS))
            put("views",buildJsonObject {put("type","array");put("items",choice(DrawPreview.VIEWS));put("minItems",1);put("maxItems",4);put("description","Images returned in this order; overrides view.")})
            put("renderMode",choice(DrawPreview.RENDER_MODES))
            listOf("region","frame").forEach {put(it,buildJsonObject {put("type","object");put("description","Inclusive BuildBox in original drawing coordinates. Region filters cells; frame fixes camera scale across edits.")})}
            put("sliceY",buildJsonObject {put("type","integer");put("description","Original Y for drawings/assembly; normalized local Y for blueprint previews.")})
            put("cutaway",buildJsonObject {put("type","boolean");put("description","Hide all cells above sliceY while preserving the chosen views, not just one layer.")})
            if(componentFilters) {
                listOf("components","hideComponents","overlays").forEach {put(it,McpJson.array())}
            }
        }
        fun box(b:BuildBox)=Box.of(b.from.x,b.from.y,b.from.z,b.to.x,b.to.y,b.to.z)
        fun buildBox(b:Box)=BuildBox(BuildPos(b.min().x(),b.min().y(),b.min().z()),BuildPos(b.max().x(),b.max().y(),b.max().z()))
        fun drawing(g:CompiledStructure):DrawStructure=DrawStructure(Box.of(g.sourceMin.x,g.sourceMin.y,g.sourceMin.z,g.sourceMin.x+g.size.x-1,g.sourceMin.y+g.size.y-1,g.sourceMin.z+g.size.z-1),
            g.voxels.map {v->DrawVoxel(Vec3i(v.position.x+g.sourceMin.x,v.position.y+g.sourceMin.y,v.position.z+g.sourceMin.z),DrawBlock(BlockStateRef(v.material.block,v.material.properties),GridTransform(v.quarterTurns,v.mirrorX,Vec3i.ZERO)))},
            g.anchors.mapValues {(_,p)->Vec3i(p.x+g.sourceMin.x,p.y+g.sourceMin.y,p.z+g.sourceMin.z)})
    }
}
