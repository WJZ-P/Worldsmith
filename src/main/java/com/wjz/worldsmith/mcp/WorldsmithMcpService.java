package com.wjz.worldsmith.mcp;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.mcp.McpDiscovery;
import com.wjz.worldsmith.core.mcp.McpHttpServer;
import com.wjz.worldsmith.core.mcp.WorldsmithMcpTools;
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
	private static Consumer<String> packFinished = id -> { };

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
		if (server != null && requestedPort == settings.getPort()) {
			return;
		}
		stop();
		start(settings.getPort());
	}

	public static synchronized void stop() {
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

	private static void start(int port) {
		try {
			WorldsmithMcpTools tools = new WorldsmithMcpTools(packDirectory(), runtimeInfo(), packFinished);
			McpHttpServer started = new McpHttpServer(tools.all(), modVersion());
			URI endpoint = started.start(port);
			server = started;
			requestedPort = port;
			McpDiscovery.write(discoveryFile(), endpoint, runtimeInfo().get());
			Worldsmith.LOGGER.info("Worldsmith MCP bridge listening on {}, announced in {}", endpoint, discoveryFile());
		} catch (Exception e) {
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
