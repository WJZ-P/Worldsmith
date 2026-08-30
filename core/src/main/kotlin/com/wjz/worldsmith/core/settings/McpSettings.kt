package com.wjz.worldsmith.core.settings

import kotlinx.serialization.Serializable

/** Settings for the loopback-only MCP bridge hosted by the client mod. */
@Serializable
data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val DEFAULT_PORT: Int = 40821
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
    }
}
