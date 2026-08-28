package com.wjz.worldsmith.core.serialization

import kotlinx.serialization.json.Json

object WorldsmithJson {
    val format: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
    }

    inline fun <reified T> encode(value: T): String = format.encodeToString(value)

    inline fun <reified T> decode(value: String): T = format.decodeFromString(value)
}
