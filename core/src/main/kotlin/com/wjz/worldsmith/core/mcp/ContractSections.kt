package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import kotlinx.serialization.json.*

/** Stable section ids and revisions let agents retrieve only the contract they are currently applying. */
object ContractSections {
    fun split(text:String):Map<String,String> {
        val parts=linkedMapOf<String,StringBuilder>();var name="overview";parts[name]=StringBuilder()
        for(line in text.lineSequence()) {
            if(line.startsWith("## ")){val base=line.removePrefix("## ").lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-');name=base.ifBlank {"section-${parts.size}"};var duplicate=2;val stem=name;while(name in parts)name="$stem-${duplicate++}";parts[name]=StringBuilder()}
            parts.getValue(name).appendLine(line)
        }
        return parts.mapValues {it.value.toString().trimEnd()}
    }
    fun index(text:String):JsonObject=buildJsonObject {
        put("revision",DrawSnapshotCodec.hash(text.toByteArray(Charsets.UTF_8)))
        putJsonArray("sections"){split(text).forEach {(id,body)->add(buildJsonObject {put("id",id);put("title",body.lineSequence().firstOrNull().orEmpty().trimStart('#',' '));put("characters",body.length)})}}
    }
}
