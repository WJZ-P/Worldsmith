package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Worldsmith pack with its biomes already resolved against Minecraft types.
 *
 * <p>Compiling a pack used to be the same act as running datagen: every
 * bootstrap read one static built-in pack, so there was no way to express
 * "compile this other pack". Passing the pack in is what lets a pack that
 * arrived over the MCP bridge be compiled while the game is running, and it
 * costs the build-time path nothing, where the built-in pack is simply the one
 * handed in.
 */
public final class CompiledPack {
	private final WorldsmithPack pack;
	private final List<CompiledBiome> biomes;
	private final Map<String, CompiledBiome> byId;

	private CompiledPack(WorldsmithPack pack, List<CompiledBiome> biomes) {
		this.pack = pack;
		this.biomes = biomes;
		Map<String, CompiledBiome> index = new LinkedHashMap<>();
		biomes.forEach(biome -> index.put(biome.id(), biome));
		this.byId = Map.copyOf(index);
	}

	public static CompiledPack of(WorldsmithPack pack) {
		return new CompiledPack(pack, CompiledBiomes.compile(pack.getBiomes()));
	}

	public WorldsmithPack pack() {
		return this.pack;
	}

	public List<CompiledBiome> biomes() {
		return this.biomes;
	}

	public CompiledBiome biome(String id) {
		CompiledBiome biome = this.byId.get(id);
		if (biome == null) {
			throw new IllegalArgumentException("Unknown biome '" + id + "'");
		}
		return biome;
	}

	public List<BiomeDefinition> definitions() {
		return this.pack.getBiomes().getBiomes();
	}

	public TerrainPlan terrain() {
		return this.pack.getTerrain();
	}

	public FeatureLibrary features() {
		return this.pack.getFeatures();
	}

	public String id() {
		return this.pack.getManifest().getId();
	}

	public String displayName() {
		return this.pack.getManifest().getDisplayName();
	}
}
