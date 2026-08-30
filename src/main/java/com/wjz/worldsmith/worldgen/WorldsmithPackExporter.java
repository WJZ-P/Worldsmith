package com.wjz.worldsmith.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Cloner;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagFile;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Writes a compiled Worldsmith pack out as a Minecraft data pack.
 *
 * <p>This is the same work {@code runDatagen} does, moved to runtime and given a
 * pack to compile rather than always the built-in one. It exists because a pack
 * a model wrote while the game was running cannot reach a world any other way:
 * biome tags are read from data pack JSON while the worldgen registries are
 * being built, so registering biomes directly into the registry would leave them
 * in no tags at all, and a biome in no tags gets no structures and therefore no
 * stronghold and no reachable end portal.
 *
 * <p>Emitting the tag files alongside the biomes is the whole point. Everything
 * else here follows from wanting Minecraft to read the result through its own
 * loader instead of trusting ours.
 */
public final class WorldsmithPackExporter {
	/**
	 * The registries a pack contributes, in dependency order.
	 *
	 * <p>Order matters on the way in: placed features reference configured ones,
	 * biomes reference placed features, the noise settings reach biomes through
	 * the surface rules, and the world preset references everything.
	 */
	private static final List<ResourceKey<? extends Registry<?>>> OWNED = List.of(
		Registries.CONFIGURED_FEATURE,
		Registries.PLACED_FEATURE,
		Registries.BIOME,
		Registries.NOISE_SETTINGS,
		Registries.WORLD_PRESET
	);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private WorldsmithPackExporter() {
	}

	/**
	 * Registers the pack's bootstraps on a builder.
	 *
	 * <p>Shared with datagen so the build-time and runtime paths cannot drift
	 * into producing different worlds from the same pack.
	 */
	public static void addTo(RegistrySetBuilder builder, CompiledPack pack) {
		builder.add(Registries.CONFIGURED_FEATURE, context -> WorldsmithVegetation.bootstrapConfigured(pack, context));
		builder.add(Registries.PLACED_FEATURE, context -> WorldsmithVegetation.bootstrapPlaced(pack, context));
		builder.add(Registries.BIOME, context -> BiomeCompiler.bootstrap(pack, context));
		builder.add(Registries.NOISE_SETTINGS, context -> WorldsmithNoiseSettings.bootstrap(pack, context));
		builder.add(Registries.WORLD_PRESET, context -> WorldsmithWorldPresets.bootstrap(pack, context));
	}

	/**
	 * Resolves the pack against the registries a world is being built from.
	 *
	 * @param registries the worldgen registries already loaded from data packs,
	 *                   which the pack's biomes need for vanilla ores, carvers
	 *                   and noise parameters
	 */
	public static HolderLookup.Provider compile(CompiledPack pack, HolderLookup.Provider registries) {
		return compilePatch(pack, registries).patches();
	}

	/**
	 * Builds the pack as a patch over the active worldgen registries.
	 *
	 * <p>{@link RegistrySetBuilder#build} is deliberately not used here. Its
	 * context may only contain registries outside the ones being built; a real
	 * world-creation context already contains biome, feature, noise-settings and
	 * preset registries, so {@code build} sees duplicate registry keys. With a
	 * static-only context it has the opposite problem: every vanilla holder our
	 * biomes reference remains unclaimed. {@code buildPatch} is the vanilla
	 * mechanism for exactly this operation: build only our entries while using
	 * the active provider as the fallback for cross-registry references.
	 */
	public static RegistrySetBuilder.PatchedRegistries compilePatch(CompiledPack pack, HolderLookup.Provider registries) {
		RegistrySetBuilder builder = new RegistrySetBuilder();
		addTo(builder, pack);

		RegistryAccess.Frozen staticRegistries =
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		Cloner.Factory cloner = new Cloner.Factory();
		RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(data -> data.runWithArguments(cloner::addCodec));
		return builder.buildPatch(staticRegistries, registries, cloner);
	}

	/**
	 * Writes the pack to {@code root} as a loadable data pack.
	 *
	 * @return the number of files written
	 */
	public static int export(CompiledPack pack, HolderLookup.Provider registries, Path root) throws IOException {
		return write(pack, compile(pack, registries), root);
	}

	/** Split out so a caller that already resolved the pack does not do it twice. */
	public static int write(CompiledPack pack, HolderLookup.Provider provider, Path root) throws IOException {
		// Must come from the provider rather than RegistryOps.create: a provider
		// built by RegistrySetBuilder overrides this to hand out its own holder
		// owner, and holders made during a bootstrap belong to that owner. Going
		// through RegistryOps.create instead derives an owner from each registry
		// lookup, and every cross-registry reference then fails to encode.
		RegistryOps<JsonElement> ops = provider.createSerializationContext(JsonOps.INSTANCE);
		int written = 0;

		writeJson(root.resolve("pack.mcmeta"), packMetadata(pack));
		written++;

		for (ResourceKey<? extends Registry<?>> registry : OWNED) {
			written += writeRegistry(root, ops, provider, capture(registry));
		}
		written += writeTags(root, pack);
		return written;
	}

	/**
	 * Writes one registry's entries.
	 *
	 * <p>Only entries in Worldsmith's namespace are emitted: the provider also
	 * carries every vanilla biome and placed feature the pack was resolved
	 * against, and re-emitting those would overwrite vanilla with a copy of
	 * itself.
	 */
	private static <T> int writeRegistry(
		Path root,
		RegistryOps<JsonElement> ops,
		HolderLookup.Provider provider,
		ResourceKey<? extends Registry<T>> registry
	) throws IOException {
		Codec<T> codec = elementCodec(registry);
		String directory = Registries.elementsDirPath(registry);
		int written = 0;

		for (Holder.Reference<T> holder : ours(provider.lookupOrThrow(registry))) {
			Identifier id = holder.key().identifier();
			JsonElement encoded = codec.encodeStart(ops, holder.value())
				.getOrThrow(message -> new IllegalStateException(
					"Could not encode " + id + ": " + message
				));
			writeJson(elementPath(root, id, directory), encoded);
			written++;
		}
		return written;
	}

	/**
	 * Binds the element type of a key taken from the heterogeneous {@link #OWNED}
	 * list. Each key really does describe a registry of one type; the list just
	 * cannot say which, so the type variable is re-introduced here.
	 */
	@SuppressWarnings("unchecked")
	private static <T> ResourceKey<? extends Registry<T>> capture(ResourceKey<? extends Registry<?>> registry) {
		return (ResourceKey<? extends Registry<T>>) registry;
	}

	private static <T> List<Holder.Reference<T>> ours(HolderLookup.RegistryLookup<T> lookup) {
		return lookup.listElements()
			.filter(holder -> holder.key().identifier().getNamespace().equals(com.wjz.worldsmith.Worldsmith.MOD_ID))
			.toList();
	}

	/**
	 * The tag files, which are what keep vanilla content alive.
	 *
	 * <p>A biome that is in no tag is skipped by structure placement, so a world
	 * built entirely from custom biomes would have no villages, no strongholds
	 * and no way to reach the end. The preset tag is separate: it is what makes
	 * the world type appear in Create New World at all.
	 */
	private static int writeTags(Path root, CompiledPack pack) throws IOException {
		Map<TagKey<Biome>, Set<Identifier>> members = new LinkedHashMap<>();
		for (CompiledBiome biome : pack.biomes()) {
			for (TagKey<Biome> tag : BiomeArchetype.tagsFor(biome)) {
				members.computeIfAbsent(tag, key -> new LinkedHashSet<>()).add(biome.key().identifier());
			}
		}

		int written = 0;
		String biomeTagDir = Registries.tagsDirPath(Registries.BIOME);
		for (Map.Entry<TagKey<Biome>, Set<Identifier>> entry : members.entrySet()) {
			writeJson(elementPath(root, entry.getKey().location(), biomeTagDir), tagFile(entry.getValue()));
			written++;
		}

		TagKey<WorldPreset> normal = WorldPresetTags.NORMAL;
		writeJson(
			elementPath(root, normal.location(), Registries.tagsDirPath(Registries.WORLD_PRESET)),
			tagFile(Set.of(pack.worldPresetKey().identifier()))
		);
		return written + 1;
	}

	private static JsonElement tagFile(Set<Identifier> values) {
		List<TagEntry> entries = new ArrayList<>();
		values.forEach(id -> entries.add(TagEntry.element(id)));
		return TagFile.CODEC.encodeStart(JsonOps.INSTANCE, new TagFile(entries, false))
			.getOrThrow(message -> new IllegalStateException("Could not encode tag: " + message));
	}

	private static JsonElement packMetadata(CompiledPack pack) {
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
		MetadataSectionType<PackMetadataSection> type = PackMetadataSection.forPackType(PackType.SERVER_DATA);
		PackMetadataSection section = new PackMetadataSection(
			Component.literal(pack.displayName()),
			new InclusiveRange<>(format)
		);
		JsonObject root = new JsonObject();
		root.add(
			type.name(),
			type.codec().encodeStart(JsonOps.INSTANCE, section)
				.getOrThrow(message -> new IllegalStateException("Could not encode pack.mcmeta: " + message))
		);
		return root;
	}

	/** {@code data/<namespace>/<directory>/<path>.json}, the layout the loader scans. */
	private static Path elementPath(Path root, Identifier id, String directory) {
		Path path = root.resolve("data").resolve(id.getNamespace());
		for (String segment : directory.split("/")) {
			path = path.resolve(segment);
		}
		for (String segment : id.getPath().split("/")) {
			path = path.resolve(segment);
		}
		return path.resolveSibling(path.getFileName() + ".json");
	}

	private static void writeJson(Path path, JsonElement json) throws IOException {
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
	}

	/**
	 * The codec Minecraft itself reads this registry with.
	 *
	 * <p>Taking it from {@link RegistryDataLoader} rather than naming each codec
	 * here is what guarantees the loader can read back what we wrote.
	 */
	@SuppressWarnings("unchecked")
	private static <T> Codec<T> elementCodec(ResourceKey<? extends Registry<T>> registry) {
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
			if (data.key().equals(registry)) {
				return (Codec<T>) data.elementCodec();
			}
		}
		throw new IllegalArgumentException("No worldgen codec for " + registry.identifier());
	}

	/** Convenience for callers that cannot declare {@link IOException}. */
	public static int exportUnchecked(CompiledPack pack, HolderLookup.Provider registries, Path root) {
		try {
			return export(pack, registries, root);
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}
}
