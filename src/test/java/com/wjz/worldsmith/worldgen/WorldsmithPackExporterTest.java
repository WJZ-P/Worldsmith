package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.RiverFill;
import com.wjz.worldsmith.core.model.BandEffect;
import com.wjz.worldsmith.core.model.BandRegion;
import com.wjz.worldsmith.core.model.TerrainBand;
import com.wjz.worldsmith.core.model.TerrainPlan;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure-JVM coverage for the runtime path; no client or render loop is involved. */
final class WorldsmithPackExporterTest {
	private static final Set<ResourceKey<? extends Registry<?>>> OWNED = Set.of(
		Registries.CONFIGURED_FEATURE,
		Registries.PLACED_FEATURE,
		Registries.BIOME,
		Registries.NOISE_SETTINGS,
		Registries.WORLD_PRESET
	);

	private static HolderLookup.Provider vanilla;

	@TempDir
	Path tempDirectory;

	@BeforeAll
	static void bootstrapMinecraft() {
		WorldsmithTestBootstrap.bootStrap();
		vanilla = VanillaRegistries.createLookup();
	}

	@Test
	void publicExportMatchesAllGeneratedDataSemantically() throws IOException {
		Path output = this.tempDirectory.resolve("exported");

		int written = WorldsmithPackExporter.export(WorldsmithPacks.builtinCompiled(), vanilla, output);

		assertEquals(52, written, "51 data files plus pack.mcmeta");
		Path expectedData = Path.of("src/main/generated/data").toAbsolutePath().normalize();
		Path actualData = output.resolve("data");
		Set<String> expectedFiles = jsonFiles(expectedData);
		Set<String> actualFiles = jsonFiles(actualData);
		assertEquals(expectedFiles, actualFiles, "runtime export and datagen must contain the same files");
		assertEquals(51, actualFiles.size());

		for (String relative : expectedFiles) {
			assertEquals(
				readJson(expectedData.resolve(relative)),
				readJson(actualData.resolve(relative)),
				"semantic JSON mismatch in " + relative
			);
		}
	}

	@Test
	void minecraftCodecsReadEveryExportedRegistryElementBack() throws IOException {
		CompiledPack pack = WorldsmithPacks.builtinCompiled();
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(pack, vanilla);
		Path output = this.tempDirectory.resolve("readback");
		WorldsmithPackExporter.write(pack, compiled.patches(), output);
		RegistryOps<JsonElement> ops = compiled.full().createSerializationContext(JsonOps.INSTANCE);
		String surfaceGrammar = readJson(
			output.resolve("data/worldsmith/worldgen/noise_settings/wasteland.json")
		).toString();
		assertTrue(surfaceGrammar.contains("worldsmith:hydrology"));
		assertTrue(surfaceGrammar.contains("dry_riverbed"));
		assertTrue(surfaceGrammar.contains("minecraft:stone_depth"));
		assertTrue(surfaceGrammar.contains("minecraft:noise_threshold"));
		assertTrue(surfaceGrammar.contains("minecraft:y_above"));

		int decoded = 0;
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
			if (OWNED.contains(data.key())) {
				decoded += decodeDirectory(data, ops, output);
			}
		}
		assertEquals(42, decoded);
	}

	@Test
	void runtimePackKeysAreQualifiedByItsContentHash() {
		CompiledPack builtin = WorldsmithPacks.builtinCompiled();
		CompiledPack runtime = CompiledPack.scoped(builtin.pack());
		String prefix = "generated/" + runtime.id() + "/";

		assertEquals(WorldsmithWorldPresets.WASTELAND, builtin.worldPresetKey());
		assertEquals(WorldsmithNoiseSettings.WASTELAND, builtin.noiseSettingsKey());
		assertNotEquals(builtin.worldPresetKey(), runtime.worldPresetKey());
		assertTrue(runtime.worldPresetKey().identifier().getPath().startsWith(prefix));
		assertTrue(runtime.biomes().stream().allMatch(biome -> biome.key().identifier().getPath().startsWith(prefix)));
	}

	@Test
	void anchorSurfaceConditionCodecRoundTrips() {
		WorldsmithAnchorConditionSource source = new WorldsmithAnchorConditionSource(
			0.7, 1.0, 600, 1.2, false, 120, -80, 0, 0.0
		);

		JsonElement encoded = SurfaceRules.ConditionSource.CODEC.encodeStart(JsonOps.INSTANCE, source)
			.getOrThrow(message -> new IllegalStateException("Could not encode anchor condition: " + message));
		SurfaceRules.ConditionSource decoded = SurfaceRules.ConditionSource.CODEC.parse(JsonOps.INSTANCE, encoded)
			.getOrThrow(message -> new IllegalStateException("Could not decode anchor condition: " + message));

		assertTrue(encoded.toString().contains("worldsmith:anchor"));
		assertEquals(source, decoded);
	}

	@Test
	void scopedRuntimePackExportsAndReadsBackWithMinecraftCodecs() throws IOException {
		CompiledPack runtime = CompiledPack.scoped(WorldsmithPacks.builtin());
		// Create World already has the mod's built-in pack in its fallback
		// registries. Layer the runtime pack over that exact shape, not bare
		// vanilla, so duplicate-key regressions are caught here.
		HolderLookup.Provider activeWorldgen =
			WorldsmithPackExporter.compilePatch(WorldsmithPacks.builtinCompiled(), vanilla).full();
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(runtime, activeWorldgen);
		Path output = this.tempDirectory.resolve("scoped");

		assertEquals(52, WorldsmithPackExporter.write(runtime, compiled.patches(), output));
		String prefix = "generated/" + runtime.id();
		assertTrue(Files.isRegularFile(
			output.resolve("data/worldsmith/worldgen/biome").resolve(prefix).resolve("abyss.json")
		));
		JsonElement normalTag = readJson(
			output.resolve("data/minecraft/tags/worldgen/world_preset/normal.json")
		);
		assertTrue(normalTag.toString().contains(runtime.worldPresetKey().identifier().toString()));

		RegistryOps<JsonElement> ops = compiled.full().createSerializationContext(JsonOps.INSTANCE);
		int decoded = 0;
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
			if (OWNED.contains(data.key())) {
				decoded += decodeDirectory(data, ops, output);
			}
		}
		assertEquals(42, decoded);
	}

	@Test
	void proceduralTerrainExportsAndReadsBackWithMinecraftCodecs() throws IOException {
		WorldsmithPack source = WorldsmithPacks.builtin();
		TerrainPlan template = source.getTerrain();
		TerrainPlan terrain = new TerrainPlan(
			template.getSchemaVersion(),
			template.getSeed(),
			template.getMinY(),
			template.getHeight(),
			template.getHorizontalNoiseSize(),
			template.getVerticalNoiseSize(),
			template.getSeaLevel(),
			template.getDefaultBlock(),
			template.getDefaultFluid(),
			new TerrainShape.Procedural(
				0.72,
				2.0,
				0.8,
				new ReliefDistribution(0.0, 0.0, 1.0),
				1.6,
				0.3,
				new HydrologyIntent(0.06, 1.4, 1.1, 0.8, RiverFill.FLUID, 0.07, 1.6, 0.9, 1.5),
				// Non-default so the round trip covers real bands rather than
				// the branch that compiles them away entirely.
				List.of(
					new TerrainBand(0.28, 176, 244, BandEffect.ADD, BandRegion.OVER_LAND, null, 1.3, 0.9),
					new TerrainBand(0.30, -40, 20, BandEffect.CARVE, BandRegion.INLAND, null, 2.0, 1.2)
				),
				List.of()
			),
			template.getAquifersEnabled(),
			template.getOreVeinsEnabled(),
			template.getLegacyRandomSource(),
			template.getSpawnTargets()
		);
		String id = "a".repeat(64);
		WorldsmithPackManifest oldManifest = source.getManifest();
		WorldsmithPackManifest manifest = new WorldsmithPackManifest(
			oldManifest.getFormatVersion(),
			id,
			"Procedural test",
			"Compiler fixture",
			oldManifest.getFiles()
		);
		CompiledPack runtime = CompiledPack.scoped(new WorldsmithPack(
			manifest,
			terrain,
			source.getBiomes(),
			source.getFeatures(),
			id
		));

		HolderLookup.Provider activeWorldgen =
			WorldsmithPackExporter.compilePatch(WorldsmithPacks.builtinCompiled(), vanilla).full();
		RegistrySetBuilder.PatchedRegistries compiled = WorldsmithPackExporter.compilePatch(runtime, activeWorldgen);
		Path output = this.tempDirectory.resolve("procedural");

		assertEquals(52, WorldsmithPackExporter.write(runtime, compiled.patches(), output));
		JsonElement proceduralBiome = readJson(
			output.resolve("data/worldsmith/worldgen/biome/generated")
				.resolve(id)
				.resolve("abyss.json")
		);
		assertEquals(0, proceduralBiome.getAsJsonObject().getAsJsonArray("carvers").size());
		assertEquals(
			2,
			proceduralBiome.getAsJsonObject().getAsJsonArray("features").get(1).getAsJsonArray().size(),
			"lava lakes stay present when procedural terrain owns cave carving"
		);
		RegistryOps<JsonElement> ops = compiled.full().createSerializationContext(JsonOps.INSTANCE);
		int decoded = 0;
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
			if (OWNED.contains(data.key())) {
				decoded += decodeDirectory(data, ops, output);
			}
		}
		assertEquals(42, decoded);
	}

	private static Set<String> jsonFiles(Path root) throws IOException {
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.map(path -> root.relativize(path).toString().replace('\\', '/'))
				.collect(Collectors.toUnmodifiableSet());
		}
	}

	private static JsonElement readJson(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return JsonParser.parseReader(reader);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int decodeDirectory(
		RegistryDataLoader.RegistryData<?> data,
		RegistryOps<JsonElement> ops,
		Path output
	) throws IOException {
		Path directory = output.resolve("data/worldsmith").resolve(Registries.elementsDirPath(data.key()));
		if (!Files.isDirectory(directory)) {
			return 0;
		}
		Codec<Object> codec = (Codec<Object>) data.elementCodec();
		List<Path> files;
		try (var paths = Files.walk(directory)) {
			files = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList();
		}
		for (Path path : files) {
			Object decoded = codec.parse(ops, readJson(path))
				.getOrThrow(message -> new IllegalStateException("Could not read " + path + ": " + message));
			assertTrue(decoded != null);
		}
		return files.size();
	}
}
