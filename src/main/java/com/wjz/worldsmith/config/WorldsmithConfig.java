package com.wjz.worldsmith.config;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.settings.WorldsmithSettings;
import com.wjz.worldsmith.core.settings.WorldsmithSettingsStore;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The mod's settings file, held in memory.
 *
 * The reading and writing live in core, which knows nothing about Minecraft;
 * all this adds is where the file goes and what to do when it cannot be read.
 * A broken file is logged and replaced by defaults in memory rather than
 * thrown, because failing to open the settings screen is a worse outcome than
 * losing a hand-edited value.
 */
public final class WorldsmithConfig {
	private static final String FILE_NAME = "worldsmith.json";

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	private static WorldsmithSettings current = read();

	private WorldsmithConfig() {
	}

	public static WorldsmithSettings get() {
		return current;
	}

	public static Path path() {
		return PATH;
	}

	/** Stores the settings and writes them out. Values out of range are clamped first. */
	public static void set(WorldsmithSettings settings) {
		current = WorldsmithSettingsStore.sanitize(settings);
		try {
			WorldsmithSettingsStore.save(PATH, current);
		} catch (RuntimeException | java.io.IOException e) {
			Worldsmith.LOGGER.error("Could not write Worldsmith settings to {}", PATH, e);
		}
	}

	private static WorldsmithSettings read() {
		try {
			return WorldsmithSettingsStore.load(PATH);
		} catch (RuntimeException | java.io.IOException e) {
			Worldsmith.LOGGER.error("Could not read Worldsmith settings from {}, using defaults", PATH, e);
			return new WorldsmithSettings();
		}
	}
}
