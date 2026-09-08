package com.wjz.worldsmith.core.structure

import com.wjz.worldsmith.core.drawhost.DrawingHost
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*

/** Authoring-only input is lowered to the existing portable v2 blueprint; worldgen has no new model. */
class AuthoredDraftResolver(private val drawings:DrawingHost) {
    fun resolve(session:String,document:JsonObject,inspection:Boolean=false):WorldStructureDefinition {
        val root=document.toMutableMap();val original=root.getValue("blueprint").jsonObject
        root["blueprint"]=blueprint(session,original,inspection)
        root["assembly"]?.jsonObject?.let {a->
            root["assembly"]=JsonObject(a+("pieces" to JsonObject(a.getValue("pieces").jsonObject.mapValues {blueprint(session,it.value.jsonObject,inspection)})))
        }
        if(original["authored"]!=null) {
            val semantic=semantic(session,original.getValue("authored").jsonObject.getValue("variants").jsonArray.first().jsonPrimitive.content,true)
            val p=root.getValue("placement").jsonObject;val fit=p["terrainFit"]?.jsonObject
            val foundation=fit?.get("foundation")?.jsonObject
            if(foundation?.get("mode")?.jsonPrimitive?.content=="PILLARS") {
                require(foundation["supports"]==null || foundation["supports"]==semantic["supports"]) {"Authored support points have one source of truth"}
                root["placement"]=JsonObject(p+("terrainFit" to JsonObject(fit+("foundation" to JsonObject(foundation+("supports" to (semantic["supports"] ?: JsonArray(emptyList()))))))))
            }
        }
        return WorldsmithJson.format.decodeFromJsonElement(JsonObject(root))
    }
    fun semantic(session:String,id:String,allowPrevious:Boolean):JsonObject {
        val artifact=drawings.artifact(session,id,allowPrevious);val sidecar=requireNotNull(drawings.semantics(artifact)) {"Drawing was not produced by StructureProgram"}
        require(sidecar.getValue("version").jsonPrimitive.int==1) {"Unsupported authored metadata version"}
        return sidecar.getValue("semantics").jsonObject
    }
    fun blueprint(session:String,raw:JsonObject,inspection:Boolean=false):JsonObject {
        val authored=raw["authored"]?.jsonObject ?: return raw
        require(raw.keys.all {it in setOf("id","schemaVersion","authored","portBindings","instancePatches")}) {"Authored references and hand-written geometry/semantic fields are mutually exclusive"}
        val ids=authored.getValue("variants").jsonArray.map {it.jsonPrimitive.content};require(ids.size in 1..8)
        val previous=authored["allowPreviousRevision"]?.jsonPrimitive?.boolean ?: false
        val semantics=ids.map {semantic(session,it,previous||inspection)};require(semantics.distinct().size==1) {"AUTHORED_VARIANT_LAYOUT: variants need identical semantic layouts; use separate definitions for different layouts"}
        val m=semantics.first();val allowed=setOf("origin","rooms","indoorPassages","sources","ports","entrances","destinations","supports","protectedAreas","keepClear","interactions","palette")
        require(m.keys.all {it in allowed}) {"Unknown authored semantic field"}
        fun array(name:String)=m[name]?.jsonArray ?: JsonArray(emptyList())
        val occupied=JsonArray(array("rooms")+array("indoorPassages"));val entries=array("entrances");val destinations=array("destinations")
        val bindings=raw["portBindings"]?.jsonObject.orEmpty();val ports=array("ports").map {p->
            val value=p.jsonObject;val id=value.getValue("id").jsonPrimitive.content;val binding=bindings[id]?.jsonObject.orEmpty()
            require(binding.keys.all {it in setOf("pool","required","chance")}) {"Port bindings configure assembly only, not entrance geometry"}
            JsonObject(value+binding)
        }
        require(bindings.keys.all {id->ports.any {it.getValue("id").jsonPrimitive.content==id}}) {"Unknown authored entrance binding"}
        val result=buildJsonObject {
            put("id",raw.getValue("id"));put("schemaVersion",1);put("origin",m.getValue("origin"));put("drawing",buildJsonObject {put("variants",JsonArray(ids.map(::JsonPrimitive)));put("allowPreviousRevision",previous)})
            put("palette",m["palette"] ?: JsonObject(emptyMap()));put("rooms",array("rooms"));put("indoorPassages",array("indoorPassages"));put("keepClear",array("keepClear"));put("ports",JsonArray(ports));put("interactions",array("interactions"))
            putJsonObject("variation"){put("protectedAreas",array("protectedAreas"));raw["instancePatches"]?.let {put("instancePatches",it)}}
            putJsonObject("lighting"){put("mode",if(occupied.isEmpty())"EXTERIOR_ONLY" else "READABLE");put("spaces",occupied);put("sources",array("sources"));put("minimum",8)}
            if(entries.isNotEmpty())putJsonObject("access"){put("entrances",entries);put("destinations",if(destinations.isEmpty())entries else destinations);put("requiredClear",array("keepClear"))}
        }
        // Decode shape now; semantic correctness is intentionally a separate preflight stage.
        WorldsmithJson.format.decodeFromJsonElement<StructureBlueprint>(result)
        return result
    }
    fun components(session:String,b:StructureBlueprint):Map<String,BuildBox> {
        val id=b.drawing?.variants?.firstOrNull() ?: return emptyMap()
        val artifact=drawings.artifact(session,id,true);val m=drawings.semantics(artifact)?.get("components")?.jsonObject ?: return emptyMap()
        require(m.size<=128);return m.mapValues {WorldsmithJson.format.decodeFromJsonElement(it.value)}
    }
}
