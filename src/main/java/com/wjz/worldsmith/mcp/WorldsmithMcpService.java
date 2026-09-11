package com.wjz.worldsmith.mcp;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.mcp.McpDiscovery;
import com.wjz.worldsmith.core.mcp.McpHttpServer;
import com.wjz.worldsmith.core.mcp.WorldsmithMcpTools;
import com.wjz.worldsmith.core.mcp.PublicationHost;
import com.wjz.worldsmith.core.mcp.WorkflowSessions;
import com.wjz.worldsmith.core.mcp.GenerationProgressSnapshot;
import com.wjz.worldsmith.core.drawhost.DrawingExecutionLimits;
import com.wjz.worldsmith.core.drawhost.DrawingHost;
import com.wjz.worldsmith.core.drawhost.DrawingRuntime;
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec;
import com.wjz.worldsmith.core.prompt.ClasspathPromptTemplateRepository;
import com.wjz.worldsmith.core.prompt.ClasspathStyleCatalog;
import java.nio.file.Files;
import net.minecraft.SharedConstants;
import com.wjz.worldsmith.core.settings.McpSettings;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Owns the local MCP bridge.
 *
 * The bridge is off by default and only ever binds loopback, so enabling it
 * exposes Worldsmith to tools running on this machine and to nothing else.
 * Everything about the protocol lives in core; this class only decides when the
 * server should exist and where its files go.
 */
public final class WorldsmithMcpService {
	/** Packs written over MCP land here, one directory per content hash. */
	private static final String PACK_DIRECTORY = "worldsmith/packs";

	private static McpHttpServer server;
	/**
	 * The port that was asked for, which is not always the one that bound.
	 *
	 * The bridge walks forward from the preferred port when it is busy, so
	 * comparing settings against the live port would restart the server on every
	 * save. What decides whether a restart is needed is whether the request
	 * changed.
	 */
	private static int requestedPort;
	private static boolean requestedAutoApprove;
    private static Consumer<String> packFinished = id -> { };
    private static Consumer<String> sourceApproval = id -> { };
    private static PublicationHost publicationHost = PublicationHost.UNAVAILABLE;
    private static DrawingHost drawingHost;
    private record ProgressBridge(long epoch, WorldsmithMcpTools tools) {}
    private static volatile ProgressBridge progressBridge = new ProgressBridge(0, null);
    public record ProgressConnection(long epoch, boolean connected) {}
    public record ProgressRead(long epoch, boolean connected, GenerationProgressSnapshot snapshot) {}

	private WorldsmithMcpService() {
	}

	/**
	 * Brings the bridge in line with the settings.
	 *
	 * Safe to call whenever the settings change: it restarts on a port change,
	 * starts when switched on, stops when switched off, and does nothing at all
	 * when already in the requested state.
	 */
	public static synchronized void apply(McpSettings settings) {
		if (!settings.getEnabled()) {
			stop();
			return;
		}
		// The approval policy is fixed when the drawing host is built, so a change
		// to it has to restart the bridge just as a port change does.
		if (server != null && requestedPort == settings.getPort()
			&& requestedAutoApprove == settings.getAutoApproveSourceExecution()) {
			return;
		}
		stop();
		start(settings.getPort(), settings.getAutoApproveSourceExecution());
	}

    public static synchronized void stop() {
        // Invalidate UI requests before potentially slow worker shutdown/discovery cleanup.
        progressBridge = new ProgressBridge(progressBridge.epoch() + 1, null);
        if(drawingHost!=null){drawingHost.close();drawingHost=null;}
		if (server == null) {
			requestedPort = 0;
			clearDiscovery();
			return;
		}
		try {
			server.close();
			Worldsmith.LOGGER.info("Worldsmith MCP bridge stopped");
		} catch (RuntimeException e) {
			Worldsmith.LOGGER.warn("Worldsmith MCP bridge did not stop cleanly", e);
		} finally {
			server = null;
			requestedPort = 0;
			clearDiscovery();
		}
	}

	/** Where an outside tool looks to find out whether the bridge is up, and where. */
	public static Path discoveryFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("worldsmith").resolve(McpDiscovery.FILE_NAME);
	}

	private static void clearDiscovery() {
		try {
			McpDiscovery.clear(discoveryFile());
		} catch (Exception e) {
			Worldsmith.LOGGER.warn("Could not remove {}", discoveryFile(), e);
		}
	}

	public static synchronized boolean isRunning() {
		return server != null;
	}

	/** The address tools should connect to, or null when the bridge is off. */
	public static synchronized URI endpoint() {
		return server == null ? null : server.getEndpoint();
	}

	public static Path packDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(PACK_DIRECTORY);
	}

	/** Installs the client-side action performed after a guided MCP run finishes. */
    public static synchronized void setPackFinishedListener(Consumer<String> listener) {
		packFinished = Objects.requireNonNull(listener, "listener");
    }

    public static synchronized void setSourceApprovalListener(Consumer<String> listener) { sourceApproval=Objects.requireNonNull(listener); }
    public static synchronized void setPublicationHost(PublicationHost host) { publicationHost=Objects.requireNonNull(host); }
    public static synchronized DrawingHost drawingHost() { return drawingHost; }

    /** Render-thread-safe metadata read: no service lock, disk access, HTTP call or draft projection. */
    public static ProgressConnection progressConnection() {
        var bridge = progressBridge;
        return new ProgressConnection(bridge.epoch(), bridge.tools() != null);
    }

    /** Background-only projection directly against this bridge's tool catalog, never loopback HTTP. */
    public static ProgressRead readGenerationProgress(String preferredSessionId) {
        var bridge = progressBridge;
        if (bridge.tools() == null) return new ProgressRead(bridge.epoch(), false, null);
        var snapshot = bridge.tools().progressSnapshot(preferredSessionId);
        var current = progressBridge;
        if (current != bridge) return new ProgressRead(current.epoch(), current.tools() != null, null);
        return new ProgressRead(bridge.epoch(), true, snapshot);
    }

    private static DrawingRuntime drawingRuntime() throws java.io.IOException {
        Path directory=packDirectory().resolveSibling("runtime").resolve("draw-1-ecj-3.46.0");Files.createDirectories(directory);
        for(String name:java.util.List.of("draw-sdk.jar","authoring-sdk.jar","draw-worker.jar","ecj.jar")) {
            byte[] bytes;
            try(var input=WorldsmithMcpService.class.getClassLoader().getResourceAsStream("worldsmith/runtime/"+name)) {
                if(input==null)throw new java.io.IOException("Missing bundled drawing runtime: "+name);bytes=input.readAllBytes();
            }
            Path file=directory.resolve(name);
            if(!Files.isRegularFile(file)||!DrawSnapshotCodec.hash(Files.readAllBytes(file)).equals(DrawSnapshotCodec.hash(bytes)))DrawingHost.atomic(file,bytes);
        }
        return new DrawingRuntime(directory.toAbsolutePath(),Path.of(System.getProperty("java.home")));
    }

	private static void start(int port, boolean autoApprove) {
		try {
            requestedAutoApprove=autoApprove;
            drawingHost=new DrawingHost(packDirectory().resolveSibling("drawing-work"),drawingRuntime(),SharedConstants.getCurrentVersion().dataVersion().version(),sourceApproval,new DrawingExecutionLimits(),autoApprove);
            var sessions=new WorkflowSessions(8,()->java.util.UUID.randomUUID().toString().replace("-",""),packDirectory().resolveSibling("drafts"));
            WorldsmithMcpTools tools = new WorldsmithMcpTools(packDirectory(), runtimeInfo(), packFinished,new ClasspathPromptTemplateRepository(),new ClasspathStyleCatalog(),sessions,drawingHost,publicationHost,new com.wjz.worldsmith.core.mcp.DrawingExportHost(){
                @Override public byte[] export(com.wjz.worldsmith.core.draw.DrawStructure drawing) { return encode(drawing,null); }
                @Override public byte[] exportContent(com.wjz.worldsmith.core.draw.DrawStructure drawing,String scope,com.wjz.worldsmith.core.content.CustomBlockLibrary blocks) {
                    var resolver=com.wjz.worldsmith.content.WorldBlockBindings.resolver(com.wjz.worldsmith.core.content.CustomBlockBindings.plan(scope,blocks));
                    return encode(drawing,resolver);
                }
                private byte[] encode(com.wjz.worldsmith.core.draw.DrawStructure drawing,com.wjz.worldsmith.content.WorldBlockBindings.Resolver resolver) {
                    try {var output=new java.io.ByteArrayOutputStream();net.minecraft.nbt.NbtIo.writeCompressed(com.wjz.worldsmith.worldgen.WorldsmithDrawExporter.encode(drawing,resolver),output);return output.toByteArray();}
                    catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                }
            },new com.wjz.worldsmith.worldgen.WorldsmithAuthoringNativeHost());
			McpHttpServer started = new McpHttpServer(tools.all(), modVersion());
			URI endpoint = started.start(port);
			server = started;
			requestedPort = port;
			McpDiscovery.write(discoveryFile(), endpoint, runtimeInfo().get());
            progressBridge = new ProgressBridge(progressBridge.epoch() + 1, tools);
			Worldsmith.LOGGER.info("Worldsmith MCP bridge listening on {}, announced in {}", endpoint, discoveryFile());
        } catch (Exception e) {
            progressBridge = new ProgressBridge(progressBridge.epoch() + 1, null);
            if(drawingHost!=null){drawingHost.close();drawingHost=null;}
			// Broad on purpose: binding the port throws IOException, which Kotlin
			// does not declare, so a narrower catch would not compile and would
			// still miss it at runtime. A busy port must not stop the game.
			server = null;
			requestedPort = 0;
			clearDiscovery();
			Worldsmith.LOGGER.error("Worldsmith MCP bridge could not start on port {}", port, e);
		}
	}

	/** Told to callers through the status tool so a model knows what it is targeting. */
	private static Supplier<Map<String, String>> runtimeInfo() {
		return () -> {
			Map<String, String> info = new LinkedHashMap<>();
			info.put("minecraft", version("minecraft"));
			info.put("worldsmith", modVersion());
			info.put("fabricLoader", version("fabricloader"));
			return info;
		};
	}

	private static String modVersion() {
		return version(Worldsmith.MOD_ID);
	}

	private static String version(String modId) {
		return FabricLoader.getInstance()
			.getModContainer(modId)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
