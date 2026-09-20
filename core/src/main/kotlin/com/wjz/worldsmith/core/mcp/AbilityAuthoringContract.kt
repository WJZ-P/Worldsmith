package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.AbilityCapabilities
import com.wjz.worldsmith.core.ability.AbilityCapabilityRegistry
import kotlinx.serialization.json.*

/** Generated from the compiler's real registry: authoring guidance cannot drift into invented APIs. */
object AbilityAuthoringContract {
    @JvmStatic @JvmOverloads
    fun capabilities(registry: AbilityCapabilityRegistry = AbilityCapabilities.standard()): JsonArray = buildJsonArray {
        registry.specs.sortedBy { it.name }.forEach { spec -> add(buildJsonObject {
            put("name", spec.name); put("version", spec.version)
            put("arguments", JsonArray(spec.arguments.map { JsonPrimitive(it.name) }))
            put("result", spec.result.name); put("effect", spec.effect); put("description", spec.description)
        }) }
    }
}
