package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** One entry of an MCP client's server list, in the shape those clients expect. */
@Serializable
data class McpClientEntry(
    val type: String = "http",
    val url: String,
)

@Serializable
data class McpClientConfig(
    val mcpServers: Map<String, McpClientEntry>,
)

@Serializable
data class McpEndpointInfo(
    val status: String,
    val url: String,
    val transport: String = "streamable-http",
    val protocolVersion: String = McpHttpServer.PROTOCOL_VERSION,
    val runtime: Map<String, String> = emptyMap(),
    val clientConfig: McpClientConfig,
)

/**
 * How an outside tool finds the bridge.
 *
 * The port is configurable and the bridge only runs while the game does, so
 * hard-coding an address in an agent's config is wrong twice over. Instead the
 * running bridge drops a small file at a fixed path and removes it on the way
 * out: the file existing means the bridge is up, and its contents say where.
 *
 * [McpEndpointInfo.clientConfig] is a ready-made `mcpServers` block, so wiring
 * an agent up is a copy rather than a translation.
 */
object McpDiscovery {
    const val FILE_NAME: String = "mcp.json"
    const val SERVER_NAME: String = "worldsmith"

    @JvmStatic
    fun describe(url: URI, runtime: Map<String, String>): McpEndpointInfo = McpEndpointInfo(
        status = "running",
        url = url.toString(),
        runtime = runtime.toSortedMap(),
        clientConfig = McpClientConfig(mapOf(SERVER_NAME to McpClientEntry(url = url.toString()))),
    )

    @JvmStatic
    @Throws(IOException::class)
    fun write(path: Path, url: URI, runtime: Map<String, String>) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, WorldsmithJson.encode(describe(url, runtime)), StandardCharsets.UTF_8)
    }

    /** Removing the file is what tells a watcher the bridge is gone. */
    @JvmStatic
    @Throws(IOException::class)
    fun clear(path: Path) {
        Files.deleteIfExists(path)
    }
}
