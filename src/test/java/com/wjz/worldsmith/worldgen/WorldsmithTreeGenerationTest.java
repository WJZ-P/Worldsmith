package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.FeatureDefinition;
import com.wjz.worldsmith.core.model.FeatureRecipe;
import com.wjz.worldsmith.core.model.MaterialRole;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.TreeDistribution;
import com.wjz.worldsmith.core.model.TreeHeight;
import com.wjz.worldsmith.core.model.TreeSilhouette;
import com.wjz.worldsmith.core.model.TreeSpec;
import com.wjz.worldsmith.core.model.TreeSubstrate;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Executes every tree algorithm against a tiny flat in-memory level. */
final class WorldsmithTreeGenerationTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void everySilhouettePlacesBothTrunkAndCrown() {
		for (TreeSilhouette silhouette : TreeSilhouette.values()) {
			FlatWorld world = new FlatWorld();
			ConfiguredFeature<?, ?> configured = WorldsmithVegetation.configure(tree(silhouette), new MaterialResolver());
			boolean placed = place(configured, world.level(), RandomSource.create(0x54524545L + silhouette.ordinal()));

			long trunks = world.placed().values().stream().filter(state -> state.is(Blocks.OAK_LOG)).count();
			long leaves = world.placed().values().stream().filter(state -> state.is(Blocks.OAK_LEAVES)).count();
			assertTrue(placed, silhouette + " reported that placement failed");
			assertTrue(trunks > 0, silhouette + " placed no trunk blocks");
			assertTrue(leaves > 0, silhouette + " placed no foliage blocks");
		}
	}

	@Test
	void customHeightAndCrownChangeTheBlocksActuallyPlaced() {
		TreeSpec spec = new TreeSpec(
			TreeSilhouette.BROADLEAF,
			TreeDistribution.GROVE,
			TreeSubstrate.NATURAL_SOIL,
			new TreeHeight(12, 12),
			5,
			null,
			List.of()
		);
		FlatWorld world = new FlatWorld();
		assertTrue(place(WorldsmithVegetation.configure(tree(spec), new MaterialResolver()), world.level(), RandomSource.create(7L)));

		int highestLog = world.placed().entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.OAK_LOG))
			.mapToInt(entry -> entry.getKey().getY())
			.max()
			.orElseThrow();
		int minLeafX = world.placed().entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.OAK_LEAVES))
			.mapToInt(entry -> entry.getKey().getX())
			.min()
			.orElseThrow();
		int maxLeafX = world.placed().entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.OAK_LEAVES))
			.mapToInt(entry -> entry.getKey().getX())
			.max()
			.orElseThrow();

		assertTrue(highestLog >= 12, "requested height produced a top log at " + highestLog);
		assertTrue(maxLeafX - minLeafX >= 8, "requested radius produced a crown only " + (maxLeafX - minLeafX) + " blocks wide");
	}

	@SuppressWarnings("unchecked")
	private static boolean place(ConfiguredFeature<?, ?> configured, WorldGenLevel level, RandomSource random) {
		Feature<TreeConfiguration> feature = (Feature<TreeConfiguration>)configured.feature();
		TreeConfiguration config = (TreeConfiguration)configured.config();
		return feature.place(config, level, null, random, new BlockPos(0, 1, 0));
	}

	private static FeatureDefinition tree(TreeSilhouette silhouette) {
		return tree(new TreeSpec(
			silhouette,
			TreeDistribution.GROVE,
			TreeSubstrate.NATURAL_SOIL,
			null,
			null,
			null,
			List.of()
		));
	}

	private static FeatureDefinition tree(TreeSpec spec) {
		return new FeatureDefinition(
			"tree_" + spec.getSilhouette().name().toLowerCase(java.util.Locale.ROOT),
			FeatureRecipe.TREE,
			null,
			Map.of(
				MaterialRole.TRUNK, new MaterialSelector("wood", List.of("minecraft:oak_log"), List.of(), List.of()),
				MaterialRole.FOLIAGE, new MaterialSelector("leaves", List.of("minecraft:oak_leaves"), List.of(), List.of())
			),
			0.5,
			spec
		);
	}

	/** Only the LevelAccessor surface touched by TreeFeature is modelled. */
	private static final class FlatWorld implements InvocationHandler {
		private final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
		private final RandomSource random = RandomSource.create(1L);
		private final WorldGenLevel level = (WorldGenLevel)Proxy.newProxyInstance(
			WorldGenLevel.class.getClassLoader(),
			new Class<?>[] {WorldGenLevel.class},
			this
		);

		WorldGenLevel level() {
			return this.level;
		}

		Map<BlockPos, BlockState> placed() {
			return this.placed;
		}

		private BlockState stateAt(BlockPos pos) {
			return this.placed.getOrDefault(
				pos,
				pos.getY() <= 0 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState()
			);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			String name = method.getName();
			if (name.equals("getBlockState")) {
				return stateAt((BlockPos)arguments[0]);
			}
			if (name.equals("setBlock")) {
				this.placed.put(((BlockPos)arguments[0]).immutable(), (BlockState)arguments[1]);
				return true;
			}
			if (name.equals("isStateAtPosition")) {
				BlockPos pos = (BlockPos)arguments[0];
				return ((Predicate<BlockState>)arguments[1]).test(stateAt(pos));
			}
			if (name.equals("getFluidState")) {
				return stateAt((BlockPos)arguments[0]).getFluidState();
			}
			if (name.equals("getRandom")) return this.random;
			if (name.equals("getGameTime")) return 0L;
			if (name.equals("scheduleTick")) return null;
			if (name.equals("getMinY")) return -64;
			if (name.equals("getMaxY")) return 319;
			if (name.equals("getHeight")) {
				return arguments == null || arguments.length == 0 ? 384 : 1;
			}
			if (name.equals("getHeightmapPos")) {
				BlockPos pos = (BlockPos)arguments[1];
				return new BlockPos(pos.getX(), 1, pos.getZ());
			}
			if (name.equals("ensureCanWrite")) return true;
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);

			Class<?> type = method.getReturnType();
			if (type == boolean.class) return false;
			if (type == int.class) return 0;
			if (type == long.class) return 0L;
			if (type == float.class) return 0.0F;
			if (type == double.class) return 0.0;
			if (type == Optional.class) return Optional.empty();
			if (type == List.class) return List.of();
			if (type == Set.class) return Set.of();
			if (type == Stream.class) return Stream.empty();
			return null;
		}
	}
}
