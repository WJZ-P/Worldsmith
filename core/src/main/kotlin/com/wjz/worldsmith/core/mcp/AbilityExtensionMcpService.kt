package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.ability.*
import com.wjz.worldsmith.core.ability.extension.*
import kotlinx.serialization.json.*

/** There is deliberately no approval tool or approval argument in this catalog. */
class AbilityExtensionMcpService(private val service: AbilityExtensionService? = null) {
    fun tools(): List<McpTool> {
        val jobSchema = McpJson.schema(mapOf("jobId" to McpJson.type("string")), listOf("jobId"))
        return listOf(
            McpTool("worldsmith_get_ability_extension_contract", "Read trusted provider extension workflow",
                "Read the Java AbilityExtension SPI, declared metadata, compile/static-probe limits, independent install confirmation and restart requirement. This is trusted JVM code, not sandboxed AbilityScript.",
                McpJson.schema(emptyMap(), emptyList()), true, handler = { McpToolResult.success(contract()) }),
            McpTool("worldsmith_build_ability_extension", "Compile a trusted ability provider candidate",
                "Submit a bounded Java project for bundled ECJ compilation (-proc:none) and an isolated static ABI probe. Constructors, static initializers, spec() and invoke() are not executed. Returns an asynchronous job; never installs or registers providers.",
                McpJson.schema(mapOf("project" to McpJson.type("object")), listOf("project")), false, handler = { arguments ->
                    allowed(arguments, setOf("project"))
                    val host = service?.takeIf { it.available } ?: return@McpTool unavailable("compilation")
                    val raw = arguments.getValue("project").jsonObject
                    require(raw.toString().length <= 8 * 1024 * 1024 && raw.toString().toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Extension project JSON exceeds 8 MiB." }
                    McpToolResult.success(McpJson.encode(host.submit(McpJson.decode<AbilityExtensionProject>(raw))).jsonObject)
                }),
            McpTool("worldsmith_get_ability_extension_job", "Read provider build/install status",
                "Read bounded build diagnostics, source/artifact hashes, static verification scope and install/restart status. No source execution or new approval occurs.", jobSchema, true, handler = { arguments ->
                    allowed(arguments, setOf("jobId"))
                    val host = service ?: return@McpTool unavailable("job history")
                    McpToolResult.success(McpJson.encode(host.get(McpJson.string(arguments, "jobId"))).jsonObject)
                }),
            McpTool("worldsmith_list_ability_extension_jobs", "List provider candidates",
                "List at most 64 retained candidate records without execution or installation.", McpJson.schema(emptyMap(), emptyList()), true, handler = { arguments ->
                    allowed(arguments, emptySet())
                    val host = service ?: return@McpTool unavailable("job history")
                    McpToolResult.success(buildJsonObject { put("available", host.available); put("jobs", McpJson.encode(host.list())) })
                }),
            McpTool("worldsmith_request_ability_extension_install", "Request host-UI provider installation confirmation",
                "Ask the native UI to display one statically verified source/artifact hash for explicit confirmation. Drawing approvals do not apply. This tool cannot approve. Only a confirmed install writes the JAR plus hash receipt; activation requires restarting the game.",
                jobSchema, false, handler = { arguments ->
                    allowed(arguments, setOf("jobId"))
                    val host = service?.takeIf { it.installationAvailable } ?: return@McpTool unavailable("installation with independent native UI confirmation")
                    McpToolResult.success(McpJson.encode(host.requestInstall(McpJson.string(arguments, "jobId"))).jsonObject)
                }),
            McpTool("worldsmith_cancel_ability_extension_build", "Cancel provider build or pending confirmation",
                "Stop this candidate's compiler/probe or dismiss its pending approval. Installed JARs are not removed or unloaded.", jobSchema, false, handler = { arguments ->
                    allowed(arguments, setOf("jobId"))
                    val host = service ?: return@McpTool unavailable("candidate cancellation")
                    McpToolResult.success(McpJson.encode(host.cancel(McpJson.string(arguments, "jobId"))).jsonObject)
                }),
            McpTool("worldsmith_list_ability_extension_backups", "List verified provider rollback artifacts",
                "Read preserved prior JARs with matching approval receipts. Does not replace the active extension.",
                McpJson.schema(mapOf("id" to McpJson.type("string")), listOf("id")), true, handler = { arguments ->
                    allowed(arguments, setOf("id"))
                    val host = service?.takeIf { it.installationAvailable } ?: return@McpTool unavailable("rollback history")
                    McpToolResult.success(buildJsonObject { put("backups", McpJson.encode(host.backups(McpJson.string(arguments, "id")))) })
                }),
            McpTool("worldsmith_prepare_ability_extension_rollback", "Prepare a prior provider artifact for confirmation",
                "Select an exact preserved artifactHash, recheck its static ABI in a child JVM, then request a fresh host-UI installation confirmation. Does not bypass approval or hot-replace running providers.",
                McpJson.schema(mapOf("id" to McpJson.type("string"), "artifactHash" to McpJson.type("string"), "requestId" to McpJson.type("string")), listOf("id", "artifactHash", "requestId")), false, handler = { arguments ->
                    allowed(arguments, setOf("id", "artifactHash", "requestId"))
                    val host = service?.takeIf { it.installationAvailable } ?: return@McpTool unavailable("rollback preparation")
                    McpToolResult.success(McpJson.encode(host.prepareRollback(McpJson.string(arguments, "id"), McpJson.string(arguments, "artifactHash"), McpJson.string(arguments, "requestId"))).jsonObject)
                }),
        )
    }

    fun contract(): JsonObject = buildJsonObject {
        put("available", service?.available == true)
        put("installationAvailable", service?.installationAvailable == true)
        put("trustedJvmCode", true)
        put("sandboxed", false)
        put("providerCodeExecutedByBuild", false)
        put("restartRequiredAfterInstall", true)
        put("approvalToolAvailable", false)
        put("contract", javaClass.classLoader.getResourceAsStream("prompts/contract/ability_extensions.system.md")?.bufferedReader()?.use { it.readText() }
            ?: error("Missing ability extension contract"))
        put("example", McpJson.encode(example()))
    }

    private fun allowed(arguments: JsonObject, fields: Set<String>) {
        require(arguments.keys.all { it in fields }) { "Unknown extension workflow argument; approvals and runtime paths are not MCP parameters." }
    }
    private fun unavailable(action: String): McpToolResult = McpToolResult.error("Ability extension $action is unavailable on this host.", buildJsonObject {
        put("available", false); put("action", action); put("providerCodeExecuted", false); put("installed", false)
        put("message", "A configured compiler/native host and separate in-app installation confirmation are required. No fallback build, approval or installation occurred.")
    })

    companion object {
        @JvmStatic fun example(): AbilityExtensionProject {
            val description = "example.double(value) doubles one bounded number. Trusted JVM host extension."
            val spec = AbilityCapabilitySpec("example.double", arguments = listOf(AbilityType.NUMBER), result = AbilityType.NUMBER, description = description)
            return AbilityExtensionProject("example_math", "Example numeric provider", "example-v1", listOf("example.DoubleProvider"), mapOf("example.DoubleProvider" to spec),
                mapOf("example/DoubleProvider.java" to """
                    package example;
                    import java.util.List;
                    import com.wjz.worldsmith.core.ability.*;
                    import com.wjz.worldsmith.core.ability.extension.AbilityExtension;
                    public final class DoubleProvider implements AbilityExtension {
                        public DoubleProvider() {}
                        public AbilityCapabilitySpec spec() {
                            return new AbilityCapabilitySpec("example.double", 1, List.of(AbilityType.NUMBER), AbilityType.NUMBER, false, "$description");
                        }
                        public AbilityValue invoke(AbilityHost host, List<AbilityValue> arguments) {
                            return AbilityValues.number(((AbilityValue.NumberValue) arguments.get(0)).getValue() * 2);
                        }
                    }
                """.trimIndent()))
        }
    }
}
