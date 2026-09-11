package com.wjz.worldsmith.core.mcp

import com.wjz.worldsmith.core.drawhost.*
import com.wjz.worldsmith.core.prompt.ClasspathPromptTemplateRepository
import com.wjz.worldsmith.core.prompt.ClasspathStyleCatalog
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Supplier

/** Developer/local-AI authoring host. Same MCP catalog and worker, explicitly no Minecraft activation receipt. */
object StandaloneAuthoringHost {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 5..6) {"Usage: StandaloneAuthoringHost <workspace> <worker-runtime-dir> <port> <target-data-version> <lifetime-seconds> [--approve-sources]"}
        val root=Path.of(args[0]).toAbsolutePath().normalize()
        val runtime=Path.of(args[1]).toAbsolutePath().normalize()
        val port=args[2].toInt();val version=args[3].toInt();val lifetime=args[4].toLong()
        require(port in 0..65535 && version>0 && lifetime in 30..14400)
        val approve=args.getOrNull(5)=="--approve-sources"
        require(args.size==5||approve) {"Unknown source execution policy"}
        Files.createDirectories(root);require(!Files.isSymbolicLink(root))
        val channel=FileChannel.open(root.resolve(".authoring-host.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
        val workspaceLock=channel.tryLock() ?: error("Another authoring host owns this workspace; use its discovery record or a different workspace")
        for(file in listOf("draw-sdk.jar","authoring-sdk.jar","draw-worker.jar","ecj.jar"))require(Files.isRegularFile(runtime.resolve(file))) {"Missing worker runtime component $file"}
        val stopped=CountDownLatch(1);val closed=AtomicBoolean(false)
        val worker=DrawingHost(root.resolve("drawing-work"),DrawingRuntime(runtime,Path.of(System.getProperty("java.home"))),version,
            Consumer {id->System.err.println("Source job $id requires host approval; restart this isolated host with --approve-sources only for trusted authoring sources")},DrawingExecutionLimits(),approve)
        val sessions=WorkflowSessions(directory=root.resolve("drafts"))
        val tools=WorldsmithMcpTools(root.resolve("packs"),Supplier {mapOf("host" to "standalone-authoring","targetDataVersion" to version.toString(),"nativeActivation" to "not_installed")},Consumer {},ClasspathPromptTemplateRepository(),ClasspathStyleCatalog(),sessions,worker)
        val shutdown=McpTool("worldsmith_stop_authoring_host","Stop this standalone authoring process",
            "Close only this explicit standalone authoring server and its worker. Not present in the Minecraft-hosted catalog.",McpJson.schema(emptyMap(),emptyList()),false,true) {
                Thread {Thread.sleep(250);stopped.countDown()}.apply {isDaemon=true}.start()
                McpToolResult.success(buildJsonObject {put("stopping",true);put("minecraftTouched",false)})
            }
        val server=McpHttpServer(tools.all()+shutdown,"worldsmith-standalone-authoring")
        fun close(){if(closed.compareAndSet(false,true)){try{server.close()}finally{try{worker.close()}finally{workspaceLock.release();channel.close()}}}}
        Runtime.getRuntime().addShutdownHook(Thread(::close,"worldsmith-authoring-cleanup"))
        try {
            val uri=server.start(port)
            val info=buildJsonObject {put("url",uri.toString());put("pid",ProcessHandle.current().pid());put("running",true);put("workspace",root.toString());put("autoApproveSourceExecution",approve);put("targetDataVersion",version);put("nativeActivation",false);put("lifetimeSeconds",lifetime)}
            DurableFiles.write(root.resolve("mcp.json"),info.toString().toByteArray())
            println(info)
            stopped.await(lifetime,TimeUnit.SECONDS)
        } finally {
            close()
            val marker="{\"running\":false,\"stopped\":true,\"pid\":${ProcessHandle.current().pid()}}".toByteArray()
            DurableFiles.write(root.resolve("stopped.json"),marker)
            // This process held the exclusive workspace lock while authoring; its closed discovery is explicit.
            val discovery=root.resolve("mcp.json")
            if(Files.isRegularFile(discovery)) {
                val previous=runCatching {Json.parseToJsonElement(Files.readString(discovery)).jsonObject}.getOrNull()
                if(previous?.get("pid")?.jsonPrimitive?.longOrNull==ProcessHandle.current().pid())DurableFiles.write(discovery,marker)
            }
        }
    }
}
