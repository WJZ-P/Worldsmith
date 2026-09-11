package com.wjz.worldsmith.core.pack

import com.wjz.worldsmith.core.mcp.McpJson
import com.wjz.worldsmith.core.mcp.ResourcePackExchange
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Local command-line counterpart to the same import/export service used by MCP and the game UI. */
object ResourcePackCli {
    private const val USAGE = """Worldsmith resource packs (.wspack)
  paths <managed-packs-directory>
  list <managed-packs-directory>
  inspect <archive.wspack>
  import <managed-packs-directory> <inbox-filename.wspack>
  export <managed-packs-directory> <bundle-id> [export-filename.wspack]

Import reads the dedicated resource-packs/inbox directory returned by paths.
Export writes resource-packs/exports. Existing bundles and different files are preserved.
No command activates a world or executes embedded authoring sources.
"""

    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] in setOf("help", "--help", "-h")) {
            println(USAGE)
            return
        }
        try {
            val result = when (args[0]) {
                "paths" -> {
                    require(args.size == 2) { USAGE }
                    val exchange = ResourcePackExchange(Path.of(args[1]))
                    buildJsonObject {
                        put("extension", ".wspack")
                        put("containerVersion", 1)
                        put("inboxDirectory", exchange.inboxDirectory().toString())
                        put("exportsDirectory", exchange.exportsDirectory().toString())
                        put("sourceExecution", false)
                        put("activated", false)
                    }
                }
                "list" -> {
                    require(args.size == 2) { USAGE }
                    val exchange = ResourcePackExchange(Path.of(args[1]))
                    buildJsonObject {
                        put("packs", McpJson.encode(exchange.listPacks()))
                        put("inbox", McpJson.encode(exchange.listInbox()))
                        put("sourceExecution", false)
                        put("activated", false)
                    }
                }
                "inspect" -> {
                    require(args.size == 2) { USAGE }
                    val read = WorldsmithResourceArchive.read(Path.of(args[1]))
                    val pack = read.pack
                    buildJsonObject {
                        put("archive", McpJson.encode(read.info))
                        put("bundleId", pack.computedId)
                        put("displayName", pack.manifest.displayName)
                        put("bundleFormat", pack.manifest.formatVersion)
                        put("modules", McpJson.encode(pack.manifest.modules))
                        putJsonObject("counts") {
                            put("biomes", pack.biomes.biomes.size)
                            put("features", pack.features.features.size)
                            put("structures", pack.structures.structures.size)
                            put("frozenDrawings", pack.structures.artifacts.size)
                            put("sourceRecords", pack.structures.sources.size)
                            put("blocks", pack.blocks.blocks.size)
                            put("items", pack.items.items.size)
                            put("creatures", pack.creatures.creatures.size)
                            put("bosses", pack.creatures.creatures.count { it.boss != null })
                            put("quests", pack.quests.quests.size)
                            put("pngAssets", pack.manifest.assets.size)
                        }
                        put("validated", true)
                        put("sourceExecution", false)
                        put("activated", false)
                    }
                }
                "import" -> {
                    require(args.size == 3) { USAGE }
                    McpJson.encode(ResourcePackExchange(Path.of(args[1])).importPack(args[2]))
                }
                "export" -> {
                    require(args.size in 3..4) { USAGE }
                    McpJson.encode(ResourcePackExchange(Path.of(args[1])).exportPack(args[2], args.getOrNull(3)))
                }
                else -> throw IllegalArgumentException(USAGE)
            }
            println(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), result))
        } catch (failure: Exception) {
            System.err.println("Resource pack operation failed: ${failure.message}")
            kotlin.system.exitProcess(2)
        }
    }
}
