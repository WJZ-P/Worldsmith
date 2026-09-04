package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wjz.worldsmith.core.model.TreeTrunkShape;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Executes the Worldsmith trunk skeleton independently of any crown rule. */
final class WorldsmithTrunkGeometryTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void taperedTrunkNarrowsAndRootFlareWidensTheBase() {
		PlacedTree tree = place(placer(TreeTrunkShape.TAPERED, 2, 0.0F, 0.01F, 2, 1, 0, 1, 0.6F, 0.5F, 1.0F, 0.0F), 14, 11L);
		long baseLogs = tree.logs().stream().filter(pos -> pos.getY() == 1).count();
		int topY = tree.logs().stream().mapToInt(BlockPos::getY).max().orElseThrow();
		long topLogs = tree.logs().stream().filter(pos -> pos.getY() == topY).count();

		assertTrue(baseLogs >= 10, "two-block flare did not widen the root crown: " + baseLogs);
		assertEquals(1, topLogs, "tapered upper trunk did not narrow to one block");
		assertEquals(1, tree.attachments().size());
	}

	@Test
	void forkedTrunkProducesSeveralRisingLeaders() {
		PlacedTree tree = place(placer(TreeTrunkShape.FORKED, 1, 0.0F, 0.0F, 0, 1, 3, 5, 0.55F, 1.0F, 1.0F, 0.0F), 14, 19L);
		Set<BlockPos> tips = tree.attachments().stream()
			.map(FoliagePlacer.FoliageAttachment::pos)
			.collect(Collectors.toUnmodifiableSet());

		assertEquals(3, tips.size(), "each fork must own one independent crown attachment");
		assertTrue(tips.stream().allMatch(pos -> pos.getY() > 8), "a fork did not continue upward from the split");
		assertTrue(tips.stream().map(pos -> new BlockPos(pos.getX(), 0, pos.getZ())).distinct().count() > 1,
			"fork leaders did not separate horizontally");
		assertTrue(tree.logs().stream().noneMatch(pos -> pos.getX() == 0 && pos.getZ() == 0 && pos.getY() >= 9),
			"the original central pole continued above the authored split");
	}

	@Test
	void crookedAndMultiStemRulesCreateGenuinelyDifferentSkeletons() {
		PlacedTree crooked = place(placer(TreeTrunkShape.CROOKED, 1, 1.0F, 0.0F, 0, 1, 0, 1, 0.6F, 0.5F, 1.0F, 0.0F), 16, 23L);
		assertTrue(horizontalSpan(crooked.logs()) >= 2, "crooked path stayed on one vertical column");

		PlacedTree multi = place(placer(TreeTrunkShape.MULTI_STEM, 1, 0.0F, 0.0F, 1, 4, 0, 1, 0.6F, 0.5F, 1.0F, 0.0F), 13, 29L);
		assertEquals(4, multi.attachments().size(), "stems must each emit their own crown attachment");
		assertEquals(4, multi.attachments().stream()
			.map(FoliagePlacer.FoliageAttachment::pos)
			.map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()))
			.distinct().count());
	}

	@Test
	void branchSpreadAndLengthVariationChangeTheAuthoredBranches() {
		WorldsmithTrunkPlacer concentrated = placer(
			TreeTrunkShape.BRANCHING, 1, 0.0F, 0.0F, 0, 1, 8, 7, 0.55F, 0.6F, 0.0F, 0.0F
		);
		WorldsmithTrunkPlacer radialVariable = placer(
			TreeTrunkShape.BRANCHING, 1, 0.0F, 0.0F, 0, 1, 8, 7, 0.55F, 0.6F, 1.0F, 1.0F
		);
		PlacedTree concentratedTree = place(concentrated, 16, 37L);
		PlacedTree variableTree = place(radialVariable, 16, 37L);

		long concentratedDirections = horizontalTips(concentratedTree).stream().distinct().count();
		long radialDirections = horizontalTips(variableTree).stream().distinct().count();
		assertTrue(radialDirections > concentratedDirections, "branch spread did not fan branches around the trunk");
		assertTrue(horizontalBranchDistances(variableTree).stream().distinct().count() > 1,
			"lengthVariation produced eight identical branch lengths");
	}

	private static WorldsmithTrunkPlacer placer(
		TreeTrunkShape shape,
		int thickness,
		float bend,
		float taper,
		int flare,
		int stems,
		int branchCount,
		int branchLength,
		float branchStart,
		float upwardBias,
		float spread,
		float lengthVariation
	) {
		return new WorldsmithTrunkPlacer(
			12, 0, 0,
			shape, thickness, bend, taper, flare, stems, 0,
			branchCount, branchLength, branchStart, upwardBias, spread, lengthVariation
		);
	}

	private static PlacedTree place(WorldsmithTrunkPlacer placer, int height, long seed) {
		FlatWorld world = new FlatWorld();
		TreeConfiguration config = new TreeConfiguration.TreeConfigurationBuilder(
			BlockStateProvider.simple(Blocks.OAK_LOG),
			placer,
			BlockStateProvider.simple(Blocks.OAK_LEAVES),
			new WorldsmithFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0),
				com.wjz.worldsmith.core.model.TreeCrownShape.ROUND, 3, 1.0F, 0.0F, 0.0F),
			new TwoLayersFeatureSize(1, 2, 10),
			BlockStateProvider.simple(Blocks.DIRT)
		).ignoreVines().build();
		List<FoliagePlacer.FoliageAttachment> attachments = placer.placeTrunk(
			world.level(),
			(pos, state) -> world.placed().put(pos.immutable(), state),
			RandomSource.create(seed),
			height,
			new BlockPos(0, 1, 0),
			config
		);
		Set<BlockPos> logs = world.placed().entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.OAK_LOG))
			.map(Map.Entry::getKey)
			.collect(Collectors.toUnmodifiableSet());
		return new PlacedTree(logs, attachments);
	}

	private static List<BlockPos> horizontalTips(PlacedTree tree) {
		return tree.attachments().stream().skip(1)
			.map(FoliagePlacer.FoliageAttachment::pos)
			.map(pos -> new BlockPos(Integer.signum(pos.getX()), 0, Integer.signum(pos.getZ())))
			.toList();
	}

	private static List<Integer> horizontalBranchDistances(PlacedTree tree) {
		return tree.attachments().stream().skip(1)
			.map(FoliagePlacer.FoliageAttachment::pos)
			.map(pos -> Math.abs(pos.getX()) + Math.abs(pos.getZ()))
			.toList();
	}

	private static int horizontalSpan(Set<BlockPos> positions) {
		int minX = positions.stream().mapToInt(BlockPos::getX).min().orElseThrow();
		int maxX = positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
		int minZ = positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
		int maxZ = positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
		return Math.max(maxX - minX, maxZ - minZ);
	}

	private record PlacedTree(Set<BlockPos> logs, List<FoliagePlacer.FoliageAttachment> attachments) {}

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
			if (name.equals("getBlockState")) return stateAt((BlockPos)arguments[0]);
			if (name.equals("setBlock")) {
				this.placed.put(((BlockPos)arguments[0]).immutable(), (BlockState)arguments[1]);
				return true;
			}
			if (name.equals("isStateAtPosition")) {
				BlockPos pos = (BlockPos)arguments[0];
				return ((Predicate<BlockState>)arguments[1]).test(stateAt(pos));
			}
			if (name.equals("getFluidState")) return stateAt((BlockPos)arguments[0]).getFluidState();
			if (name.equals("getRandom")) return this.random;
			if (name.equals("getGameTime")) return 0L;
			if (name.equals("scheduleTick")) return null;
			if (name.equals("getMinY")) return -64;
			if (name.equals("getMaxY")) return 319;
			if (name.equals("getHeight")) return arguments == null || arguments.length == 0 ? 384 : 1;
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
