package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.model.TreeTrunkShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType;

/**
 * Builds a bounded organic trunk and emits a foliage attachment for its top and
 * every branch tip. It deliberately stops at two blocks thick and eight-block
 * branches; landmark trees belong to the later structure layer.
 */
public final class WorldsmithTrunkPlacer extends TrunkPlacer {
	private static final Codec<TreeTrunkShape> SHAPE_CODEC = Codec.STRING.xmap(
		name -> TreeTrunkShape.valueOf(name.toUpperCase(Locale.ROOT)),
		shape -> shape.name().toLowerCase(Locale.ROOT)
	);
	public static final MapCodec<WorldsmithTrunkPlacer> CODEC = RecordCodecBuilder.mapCodec(instance ->
		trunkPlacerParts(instance).and(instance.group(
			SHAPE_CODEC.fieldOf("shape").forGetter(placer -> placer.shape),
			Codec.intRange(1, 2).fieldOf("thickness").forGetter(placer -> placer.thickness),
			Codec.floatRange(0.0F, 1.0F).fieldOf("bend").forGetter(placer -> placer.bend),
			Codec.floatRange(0.0F, 1.0F).fieldOf("taper").forGetter(placer -> placer.taper),
			Codec.intRange(0, 2).fieldOf("flare").forGetter(placer -> placer.flare),
			Codec.intRange(1, 4).fieldOf("stems").forGetter(placer -> placer.stems),
			Codec.intRange(0, 24).fieldOf("clearance_padding").forGetter(placer -> placer.clearancePadding),
			Codec.intRange(0, 8).fieldOf("branch_count").forGetter(placer -> placer.branchCount),
			Codec.intRange(1, 8).fieldOf("branch_length").forGetter(placer -> placer.branchLength),
			Codec.floatRange(0.2F, 0.95F).fieldOf("branch_start").forGetter(placer -> placer.branchStart),
			Codec.floatRange(0.0F, 1.0F).fieldOf("branch_upward_bias").forGetter(placer -> placer.branchUpwardBias),
			Codec.floatRange(0.0F, 1.0F).fieldOf("branch_spread").forGetter(placer -> placer.branchSpread),
			Codec.floatRange(0.0F, 1.0F).fieldOf("branch_length_variation").forGetter(placer -> placer.branchLengthVariation)
		)).apply(instance, WorldsmithTrunkPlacer::new)
	);

	private static final Direction[] HORIZONTAL = {
		Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};
	/** Eight radial sectors; diagonal sectors alternate axis-aligned log steps. */
	private static final RadialDirection[] RADIAL = {
		new RadialDirection(0, -1), new RadialDirection(1, -1),
		new RadialDirection(1, 0), new RadialDirection(1, 1),
		new RadialDirection(0, 1), new RadialDirection(-1, 1),
		new RadialDirection(-1, 0), new RadialDirection(-1, -1)
	};

	private final TreeTrunkShape shape;
	private final int thickness;
	private final float bend;
	private final float taper;
	private final int flare;
	private final int stems;
	private final int clearancePadding;
	private final int branchCount;
	private final int branchLength;
	private final float branchStart;
	private final float branchUpwardBias;
	private final float branchSpread;
	private final float branchLengthVariation;

	public WorldsmithTrunkPlacer(
		int baseHeight,
		int heightRandA,
		int heightRandB,
		TreeTrunkShape shape,
		int thickness,
		float bend,
		float taper,
		int flare,
		int stems,
		int clearancePadding,
		int branchCount,
		int branchLength,
		float branchStart,
		float branchUpwardBias,
		float branchSpread,
		float branchLengthVariation
	) {
		super(baseHeight, heightRandA, heightRandB);
		this.shape = shape;
		this.thickness = thickness;
		this.bend = bend;
		this.taper = taper;
		this.flare = flare;
		this.stems = stems;
		this.clearancePadding = clearancePadding;
		this.branchCount = branchCount;
		this.branchLength = branchLength;
		this.branchStart = branchStart;
		this.branchUpwardBias = branchUpwardBias;
		this.branchSpread = branchSpread;
		this.branchLengthVariation = branchLengthVariation;
	}

	@Override
	protected TrunkPlacerType<?> type() {
		return WorldsmithTreePlacerTypes.trunk();
	}

	@Override
	public int getTreeHeight(RandomSource random) {
		return super.getTreeHeight(random) + this.clearancePadding;
	}

	@Override
	public List<FoliagePlacer.FoliageAttachment> placeTrunk(
		WorldGenLevel level,
		BiConsumer<BlockPos, BlockState> trunkSetter,
		RandomSource random,
		int treeHeight,
		BlockPos origin,
		TreeConfiguration config
	) {
		// TreeFeature receives the full vertical clearance height. The authored
		// trunk remains shorter by the internal padding reserved for rising
		// branches and the crown above it.
		treeHeight = Math.max(1, treeHeight - this.clearancePadding);
		for (int dx = 0; dx < this.thickness; dx++) {
			for (int dz = 0; dz < this.thickness; dz++) {
				placeBelowTrunkBlock(level, trunkSetter, random, origin.offset(dx, -1, dz), config);
			}
		}
		if (this.shape == TreeTrunkShape.MULTI_STEM) {
			List<FoliagePlacer.FoliageAttachment> attachments =
				placeMultipleStems(level, trunkSetter, random, treeHeight, origin, config);
			placeRootFlare(level, trunkSetter, random, origin, config);
			return attachments;
		}

		List<BlockPos> centres = new ArrayList<>();
		BlockPos.MutableBlockPos centre = origin.mutable();
		Direction driftDirection = HORIZONTAL[random.nextInt(HORIZONTAL.length)];
		int driftInterval = Math.max(2, Math.round(7.0F - this.bend * 5.0F));
		int firstBranch = Math.min(treeHeight - 1, Math.max(1, Math.round((treeHeight - 1) * this.branchStart)));
		int mainHeight = this.shape == TreeTrunkShape.FORKED ? firstBranch + 1 : treeHeight;
		int narrowStart = Math.max(0, Math.min(
			treeHeight - 1,
			Math.max(1, Math.round(treeHeight * (1.0F - this.taper)))
		));

		for (int y = 0; y < mainHeight; y++) {
			if (y > 0 && this.bend > 0.0F && y % driftInterval == 0) {
				if (this.shape == TreeTrunkShape.TWISTED) {
					driftDirection = driftDirection.getClockWise();
				}
				if (this.shape == TreeTrunkShape.CROOKED) {
					driftDirection = random.nextBoolean()
						? driftDirection.getClockWise()
						: driftDirection.getCounterClockWise();
				}
				if (this.shape == TreeTrunkShape.BENT || this.shape == TreeTrunkShape.TWISTED ||
					this.shape == TreeTrunkShape.CROOKED) {
					centre.move(driftDirection);
				}
			}
			int footprint = this.shape == TreeTrunkShape.TAPERED && y >= narrowStart ? 1 : this.thickness;
			placeFootprint(level, trunkSetter, random, centre, footprint, config);
			centres.add(centre.immutable());
			centre.move(Direction.UP);
		}
		placeRootFlare(level, trunkSetter, random, origin, config);

		List<FoliagePlacer.FoliageAttachment> attachments = new ArrayList<>();
		if (this.shape != TreeTrunkShape.FORKED) {
			boolean doubleTrunkTop = this.thickness == 2 && this.shape != TreeTrunkShape.TAPERED;
			attachments.add(new FoliagePlacer.FoliageAttachment(centres.getLast().above(), 0, doubleTrunkTop));
		}
		int count = this.shape == TreeTrunkShape.FORKED ? Math.max(2, this.branchCount) : this.branchCount;
		int initialDirection = random.nextInt(RADIAL.length);
		boolean[] occupiedForkDirections = new boolean[RADIAL.length];

		for (int index = 0; index < count; index++) {
			int available = Math.max(1, centres.size() - firstBranch);
			int trunkIndex = this.shape == TreeTrunkShape.FORKED
				? centres.size() - 1
				: Math.min(centres.size() - 1, firstBranch + index * available / Math.max(1, count));
			// Fork leaders must remain distinct even when the author clusters
			// them; ordinary branches may intentionally share one windward side.
			int clusteredOffset = this.shape == TreeTrunkShape.FORKED ? index : 0;
			int radialOffset = Math.round(index * (float)RADIAL.length / Math.max(1, count));
			int directionOffset = Math.round(clusteredOffset + (radialOffset - clusteredOffset) * this.branchSpread);
			int directionIndex = Math.floorMod(initialDirection + directionOffset, RADIAL.length);
			if (this.shape == TreeTrunkShape.FORKED) {
				while (occupiedForkDirections[directionIndex]) {
					directionIndex = (directionIndex + 1) % RADIAL.length;
				}
				occupiedForkDirections[directionIndex] = true;
			}
			RadialDirection direction = RADIAL[directionIndex];
			BlockPos.MutableBlockPos branch = centres.get(trunkIndex).mutable();
			int length = variedBranchLength(random);
			for (int step = 0; step < length; step++) {
				Direction segment = direction.step(step);
				branch.move(segment);
				if (this.shape == TreeTrunkShape.FORKED
					? step % 2 == 0 || random.nextFloat() < this.branchUpwardBias
					: random.nextFloat() < this.branchUpwardBias) {
					branch.move(Direction.UP);
				}
				Function<BlockState, BlockState> orient = state -> state.trySetValue(RotatedPillarBlock.AXIS, segment.getAxis());
				placeLog(level, trunkSetter, random, branch, config, orient);
			}
			attachments.add(new FoliagePlacer.FoliageAttachment(branch.above(), 0, false));
		}
		return List.copyOf(attachments);
	}

	/** Places two to four individually crowned stems from one shared root crown. */
	private List<FoliagePlacer.FoliageAttachment> placeMultipleStems(
		WorldGenLevel level,
		BiConsumer<BlockPos, BlockState> trunkSetter,
		RandomSource random,
		int treeHeight,
		BlockPos origin,
		TreeConfiguration config
	) {
		List<FoliagePlacer.FoliageAttachment> attachments = new ArrayList<>();
		int initialDirection = random.nextInt(HORIZONTAL.length);
		int separationSteps = Math.min(2, Math.max(1, treeHeight / 8));
		for (int stem = 0; stem < this.stems; stem++) {
			Direction direction = HORIZONTAL[(initialDirection + Math.round(stem * 4.0F / this.stems)) % HORIZONTAL.length];
			BlockPos.MutableBlockPos centre = origin.mutable();
			int stemHeight = Math.max(2, treeHeight - random.nextInt(3));
			for (int y = 0; y < stemHeight; y++) {
				if (y > 0 && y <= separationSteps) {
					centre.move(direction);
				}
				placeFootprint(level, trunkSetter, random, centre, this.thickness, config);
				centre.move(Direction.UP);
			}
			attachments.add(new FoliagePlacer.FoliageAttachment(centre.immutable(), 0, this.thickness == 2));
		}
		return List.copyOf(attachments);
	}

	private int variedBranchLength(RandomSource random) {
		int maximumReduction = Math.round(this.branchLength * this.branchLengthVariation);
		return Math.max(1, this.branchLength - (maximumReduction == 0 ? 0 : random.nextInt(maximumReduction + 1)));
	}

	/** A small cross-shaped buttress, kept deliberately below structure scale. */
	private void placeRootFlare(
		WorldGenLevel level,
		BiConsumer<BlockPos, BlockState> trunkSetter,
		RandomSource random,
		BlockPos origin,
		TreeConfiguration config
	) {
		for (Direction direction : HORIZONTAL) {
			Function<BlockState, BlockState> orient = state -> state.trySetValue(RotatedPillarBlock.AXIS, direction.getAxis());
			BlockPos edge = switch (direction) {
				case EAST -> origin.offset(this.thickness - 1, 0, 0);
				case SOUTH -> origin.offset(0, 0, this.thickness - 1);
				default -> origin;
			};
			for (int distance = 1; distance <= this.flare; distance++) {
				placeLog(level, trunkSetter, random, edge.relative(direction, distance), config, orient);
			}
		}
	}

	private void placeFootprint(
		WorldGenLevel level,
		BiConsumer<BlockPos, BlockState> trunkSetter,
		RandomSource random,
		BlockPos centre,
		int footprint,
		TreeConfiguration config
	) {
		for (int dx = 0; dx < footprint; dx++) {
			for (int dz = 0; dz < footprint; dz++) {
				placeLog(level, trunkSetter, random, centre.offset(dx, 0, dz), config);
			}
		}
	}

	private record RadialDirection(int x, int z) {
		Direction step(int index) {
			if (this.x != 0 && (this.z == 0 || index % 2 == 0)) {
				return this.x > 0 ? Direction.EAST : Direction.WEST;
			}
			return this.z > 0 ? Direction.SOUTH : Direction.NORTH;
		}
	}
}
