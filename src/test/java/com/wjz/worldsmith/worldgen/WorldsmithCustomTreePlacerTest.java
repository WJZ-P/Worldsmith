package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.TreeCrownShape;
import com.wjz.worldsmith.core.model.TreeTrunkShape;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Proves both custom placer types survive Minecraft's worldgen codec boundary. */
final class WorldsmithCustomTreePlacerTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void customTrunkAndCrownEncodeAsWorldsmithRegistryTypes() {
		WorldsmithTrunkPlacer trunk = new WorldsmithTrunkPlacer(
			8, 3, 0,
			TreeTrunkShape.BRANCHING,
			1, 0.25F, 0.0F, 0, 1, 0,
			4, 5, 0.45F, 0.55F, 1.0F, 0.2F
		);
		WorldsmithFoliagePlacer foliage = new WorldsmithFoliagePlacer(
			ConstantInt.of(4), ConstantInt.of(0), TreeCrownShape.CLUSTERED, 6, 0.85F, 0.4F, 0.2F
		);
		ConfiguredFeature<?, ?> configured = new ConfiguredFeature<>(
			Feature.TREE,
			new TreeConfiguration.TreeConfigurationBuilder(
				BlockStateProvider.simple(Blocks.OAK_LOG),
				trunk,
				BlockStateProvider.simple(Blocks.OAK_LEAVES),
				foliage,
				new TwoLayersFeatureSize(3, 0, 9),
				BlockStateProvider.simple(Blocks.DIRT)
			).ignoreVines().build()
		);

		var ops = VanillaRegistries.createLookup().createSerializationContext(JsonOps.INSTANCE);
		JsonElement encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, configured)
			.getOrThrow(message -> new IllegalStateException("Could not encode custom tree: " + message));
		String json = encoded.toString();
		ConfiguredFeature<?, ?> decoded = ConfiguredFeature.DIRECT_CODEC.parse(ops, encoded)
			.getOrThrow(message -> new IllegalStateException("Could not decode custom tree: " + message));
		TreeConfiguration decodedTree = (TreeConfiguration)decoded.config();

		assertTrue(json.contains("worldsmith:shaped_trunk"), json);
		assertTrue(json.contains("worldsmith:shaped_foliage"), json);
		assertTrue(json.contains("\"shape\":\"branching\""), json);
		assertTrue(json.contains("\"shape\":\"clustered\""), json);
		assertInstanceOf(WorldsmithTrunkPlacer.class, decodedTree.trunkPlacer);
		assertInstanceOf(WorldsmithFoliagePlacer.class, decodedTree.foliagePlacer);
	}
}
