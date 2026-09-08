package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.*

internal object McpJson {
    inline fun <reified T> encode(value:T):JsonElement=WorldsmithJson.format.encodeToJsonElement(value)
    inline fun <reified T> decode(value:JsonElement):T=WorldsmithJson.format.decodeFromJsonElement(value)
    fun string(a:JsonObject,key:String)=a[key]?.jsonPrimitive?.content ?: error("$key is required")
    fun strings(a:JsonObject,key:String)=a[key]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty()
    fun schema(properties:Map<String,JsonObject>,required:List<String>)=buildJsonObject {
        put("type","object");put("properties",JsonObject(properties));put("required",JsonArray(required.map(::JsonPrimitive)));put("additionalProperties",false)
    }
    fun type(name:String)=buildJsonObject {put("type",name)}
    fun array()=buildJsonObject {put("type","array");put("items",type("string"))}
}
