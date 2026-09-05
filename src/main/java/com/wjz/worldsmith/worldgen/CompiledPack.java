package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.FeatureLibrary;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.structure.CompiledStructureCatalog;
import com.wjz.worldsmith.core.structure.StructureCatalogCompiler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

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
	private final String resourcePrefix;
	private final List<CompiledBiome> biomes;
	private final Map<String, CompiledBiome> byId;
	private final CompiledStructureCatalog structures;

	private CompiledPack(WorldsmithPack pack, String resourcePrefix) {
		this.pack = pack;
		this.resourcePrefix = resourcePrefix;
		this.structures = StructureCatalogCompiler.compile(pack.getStructures());
		this.biomes = CompiledBiomes.compile(pack.getBiomes(), this::biomeKey);
		Map<String, CompiledBiome> index = new LinkedHashMap<>();
		this.biomes.forEach(biome -> index.put(biome.id(), biome));
		this.byId = Map.copyOf(index);
	}

	/** Built-in compilation uses the stable unscoped resource ids written by datagen. */
	public static CompiledPack of(WorldsmithPack pack) {
		return new CompiledPack(pack, "");
	}

	/**
	 * Runtime packs receive a hash-qualified resource scope.
	 *
	 * <p>The built-in data pack remains enabled while the temporary generated
	 * pack is loaded. Reusing {@code worldsmith:abyss} would therefore leave old
	 * tag membership behind when a generated pack also named a biome "abyss".
	 * The immutable pack id makes every generated biome, feature, noise setting
	 * and preset distinct without asking the model to invent globally unique ids.
	 */
	public static CompiledPack scoped(WorldsmithPack pack) {
		String id = pack.getManifest().getId();
		if (!id.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("A scoped pack needs a lowercase SHA-256 id");
		}
		return new CompiledPack(pack, "generated/" + id + "/");
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

	public CompiledStructureCatalog structures() { return this.structures; }

	public boolean scoped() {
		return !this.resourcePrefix.isEmpty();
	}

	public ResourceKey<Biome> biomeKey(String biomeId) {
		return ResourceKey.create(Registries.BIOME, resourceId(biomeId));
	}

	public ResourceKey<ConfiguredFeature<?, ?>> configuredFeatureKey(String featureId) {
		return ResourceKey.create(Registries.CONFIGURED_FEATURE, resourceId("vegetation/" + featureId));
	}

	public ResourceKey<PlacedFeature> placedFeatureKey(String featureId) {
		return ResourceKey.create(Registries.PLACED_FEATURE, resourceId("vegetation/" + featureId));
	}

	public ResourceKey<PlacedFeature> placedFeatureKey(String featureId, String biomeId) {
		return ResourceKey.create(Registries.PLACED_FEATURE, resourceId("vegetation/" + featureId + "/" + biomeId));
	}

	public ResourceKey<NoiseGeneratorSettings> noiseSettingsKey() {
		return ResourceKey.create(Registries.NOISE_SETTINGS, resourceId("wasteland"));
	}

	public ResourceKey<WorldPreset> worldPresetKey() {
		return ResourceKey.create(Registries.WORLD_PRESET, resourceId("wasteland"));
	}

	public Identifier structureTemplateId(String id) { return structureTemplateId(id,0); }
	public Identifier structureTemplateId(String id,int variant) { return resourceId("buildings/" + id + "/" + variant); }
	public Identifier structureLootId(String id,int interaction) { return resourceId("buildings/" + id + "/loot_" + interaction); }
	public ResourceKey<Structure> structureKey(String id) { return ResourceKey.create(Registries.STRUCTURE, resourceId("buildings/" + id)); }
	public ResourceKey<StructureSet> structureSetKey(String id) { return ResourceKey.create(Registries.STRUCTURE_SET, resourceId("buildings/" + id)); }

	private Identifier resourceId(String resourcePath) {
		return Worldsmith.id(this.resourcePrefix + resourcePath);
	}
}
