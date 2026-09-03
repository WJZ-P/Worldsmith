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
			Codec.intRange(0, 8).fieldOf("branch_count").forGetter(placer -> placer.branchCount),
			Codec.intRange(1, 8).fieldOf("branch_length").forGetter(placer -> placer.branchLength),
			Codec.floatRange(0.2F, 0.95F).fieldOf("branch_start").forGetter(placer -> placer.branchStart),
			Codec.floatRange(0.0F, 1.0F).fieldOf("branch_upward_bias").forGetter(placer -> placer.branchUpwardBias)
		)).apply(instance, WorldsmithTrunkPlacer::new)
	);

	private static final Direction[] HORIZONTAL = {
		Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	private final TreeTrunkShape shape;
	private final int thickness;
	private final float bend;
	private final int branchCount;
	private final int branchLength;
	private final float branchStart;
	private final float branchUpwardBias;

	public WorldsmithTrunkPlacer(
		int baseHeight,
		int heightRandA,
		int heightRandB,
		TreeTrunkShape shape,
		int thickness,
		float bend,
		int branchCount,
		int branchLength,
		float branchStart,
		float branchUpwardBias
	) {
		super(baseHeight, heightRandA, heightRandB);
		this.shape = shape;
		this.thickness = thickness;
		this.bend = bend;
		this.branchCount = branchCount;
		this.branchLength = branchLength;
		this.branchStart = branchStart;
		this.branchUpwardBias = branchUpwardBias;
	}

	@Override
	protected TrunkPlacerType<?> type() {
		return WorldsmithTreePlacerTypes.trunk();
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
		for (int dx = 0; dx < this.thickness; dx++) {
			for (int dz = 0; dz < this.thickness; dz++) {
				placeBelowTrunkBlock(level, trunkSetter, random, origin.offset(dx, -1, dz), config);
			}
		}

		List<BlockPos> centres = new ArrayList<>();
		BlockPos.MutableBlockPos centre = origin.mutable();
		Direction driftDirection = HORIZONTAL[random.nextInt(HORIZONTAL.length)];
		int driftInterval = Math.max(2, Math.round(7.0F - this.bend * 5.0F));

		for (int y = 0; y < treeHeight; y++) {
			if (y > 0 && this.bend > 0.0F && y % driftInterval == 0) {
				if (this.shape == TreeTrunkShape.TWISTED) {
					driftDirection = driftDirection.getClockWise();
				}
				if (this.shape == TreeTrunkShape.BENT || this.shape == TreeTrunkShape.TWISTED) {
					centre.move(driftDirection);
				}
			}
			placeFootprint(level, trunkSetter, random, centre, config);
			centres.add(centre.immutable());
			centre.move(Direction.UP);
		}

		List<FoliagePlacer.FoliageAttachment> attachments = new ArrayList<>();
		attachments.add(new FoliagePlacer.FoliageAttachment(centres.getLast().above(), 0, this.thickness == 2));
		int count = this.shape == TreeTrunkShape.FORKED ? Math.max(2, this.branchCount) : this.branchCount;
		int firstBranch = Math.min(treeHeight - 1, Math.max(1, Math.round((treeHeight - 1) * this.branchStart)));
		int initialDirection = random.nextInt(HORIZONTAL.length);

		for (int index = 0; index < count; index++) {
			int available = Math.max(1, treeHeight - firstBranch);
			int trunkIndex = Math.min(treeHeight - 1, firstBranch + index * available / Math.max(1, count));
			Direction direction = HORIZONTAL[(initialDirection + Math.round(index * 4.0F / Math.max(1, count))) % 4];
			BlockPos.MutableBlockPos branch = centres.get(trunkIndex).mutable();
			Function<BlockState, BlockState> orient = state -> state.trySetValue(RotatedPillarBlock.AXIS, direction.getAxis());
			for (int step = 0; step < this.branchLength; step++) {
				branch.move(direction);
				if (random.nextFloat() < this.branchUpwardBias) {
					branch.move(Direction.UP);
				}
				placeLog(level, trunkSetter, random, branch, config, orient);
			}
			attachments.add(new FoliagePlacer.FoliageAttachment(branch.above(), 0, false));
		}
		return List.copyOf(attachments);
	}

	private void placeFootprint(
		WorldGenLevel level,
		BiConsumer<BlockPos, BlockState> trunkSetter,
		RandomSource random,
		BlockPos centre,
		TreeConfiguration config
	) {
		for (int dx = 0; dx < this.thickness; dx++) {
			for (int dz = 0; dz < this.thickness; dz++) {
				placeLog(level, trunkSetter, random, centre.offset(dx, 0, dz), config);
			}
		}
	}
}
