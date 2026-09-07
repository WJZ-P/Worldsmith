package com.wjz.worldsmith.core.settings

import kotlinx.serialization.Serializable

/** Settings for the loopback-only MCP bridge hosted by the client mod. */
@Serializable
data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    /**
     * Runs AI-authored Java for a session without asking in game first.
     *
     * The bridge is loopback-only and the mod is a local authoring tool, so the
     * per-session dialog mostly added a manual step to every run. Turning this
     * off restores the confirmation. Note what it does not do: the drawing
     * worker is a resource/fault boundary, not a filesystem or network sandbox,
     * so anything that can reach the bridge port can compile and run code under
     * this account.
     */
    val autoApproveSourceExecution: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT: Int = 40821
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
    }
}
