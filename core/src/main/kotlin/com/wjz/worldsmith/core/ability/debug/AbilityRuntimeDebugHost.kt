package com.wjz.worldsmith.core.ability.debug

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native implementation owns server-thread dispatch, actor/scope checks and bounded trace storage. */
fun interface AbilityRuntimeDebugHost {
    fun inspect(arguments: JsonObject): JsonObject

    companion object {
        @JvmField val UNAVAILABLE = AbilityRuntimeDebugHost {
            buildJsonObject {
                put("available", false)
                put("minecraftExecuted", false)
                put("message", "Live ability inspection requires a connected native runtime. Offline simulation is available separately.")
            }
        }
    }
}
