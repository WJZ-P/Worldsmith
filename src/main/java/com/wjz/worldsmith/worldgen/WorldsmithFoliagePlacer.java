package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.model.TreeCrownShape;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacerType;

/** Places bounded crowns using Worldsmith's own geometric rules. */
public final class WorldsmithFoliagePlacer extends FoliagePlacer {
	private static final Codec<TreeCrownShape> SHAPE_CODEC = Codec.STRING.xmap(
		name -> TreeCrownShape.valueOf(name.toUpperCase(Locale.ROOT)),
		shape -> shape.name().toLowerCase(Locale.ROOT)
	);
	public static final MapCodec<WorldsmithFoliagePlacer> CODEC = RecordCodecBuilder.mapCodec(instance ->
		foliagePlacerParts(instance).and(instance.group(
			SHAPE_CODEC.fieldOf("shape").forGetter(placer -> placer.shape),
			Codec.intRange(1, 16).fieldOf("height").forGetter(placer -> placer.height),
			Codec.floatRange(0.1F, 1.0F).fieldOf("density").forGetter(placer -> placer.density),
			Codec.floatRange(0.0F, 1.0F).fieldOf("irregularity").forGetter(placer -> placer.irregularity),
			Codec.floatRange(0.0F, 1.0F).fieldOf("hanging_leaves").forGetter(placer -> placer.hangingLeaves)
		)).apply(instance, WorldsmithFoliagePlacer::new)
	);

	private final TreeCrownShape shape;
	private final int height;
	private final float density;
	private final float irregularity;
	private final float hangingLeaves;

	public WorldsmithFoliagePlacer(
		IntProvider radius,
		IntProvider offset,
		TreeCrownShape shape,
		int height,
		float density,
		float irregularity,
		float hangingLeaves
	) {
		super(radius, offset);
		this.shape = shape;
		this.height = height;
		this.density = density;
		this.irregularity = irregularity;
		this.hangingLeaves = hangingLeaves;
	}

	@Override
	protected FoliagePlacerType<?> type() {
		return WorldsmithTreePlacerTypes.foliage();
	}

	@Override
	protected void createFoliage(
		WorldGenLevel level,
		FoliageSetter foliageSetter,
		RandomSource random,
		TreeConfiguration config,
		int treeHeight,
		FoliageAttachment attachment,
		int foliageHeight,
		int leafRadius,
		int offset
	) {
		BlockPos origin = attachment.pos().above(offset);
		BlockPos hangingOrigin = origin;
		int bottomY = 0;
		int bottomRadius = leafRadius;
		switch (this.shape) {
			case ROUND -> {
				int top = this.height / 2;
				bottomY = top - this.height + 1;
				for (int y = top; y >= bottomY; y--) {
					double vertical = this.height <= 1 ? 0.0 : Math.abs(y - (top + bottomY) / 2.0) / (this.height / 2.0);
					int radius = Math.max(0, (int)Math.round(leafRadius * Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical))));
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) bottomRadius = radius;
				}
			}
			case CONICAL -> {
				bottomY = 1 - this.height;
				for (int y = 0; y >= bottomY; y--) {
					int depth = -y + 1;
					int radius = Math.max(0, (int)Math.ceil(leafRadius * depth / (double)this.height));
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
				}
			}
			case LAYERED -> {
				bottomY = 1 - this.height;
				for (int y = 0; y >= bottomY; y--) {
					int radius = Math.max(1, leafRadius - (Math.abs(y) % 2));
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) bottomRadius = radius;
				}
			}
			case UMBRELLA -> {
				bottomY = 2 - this.height;
				for (int y = 1; y >= bottomY; y--) {
					int depth = 1 - y;
					// The upper two rows form the broad parasol. Additional authored
					// height grows a progressively narrower underside instead of being
					// silently capped at four layers.
					int inset = depth == 0 ? 1 : Math.max(0, (depth - 1) / 2);
					int radius = Math.max(1, leafRadius - inset);
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) bottomRadius = radius;
				}
			}
			case WEEPING -> {
				int top = Math.max(1, this.height / 3);
				bottomY = top - this.height + 1;
				for (int y = top; y >= bottomY; y--) {
					int radius = y == top ? Math.max(1, leafRadius - 1) : leafRadius;
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
				}
			}
			case CLUSTERED -> {
				int lobeRadius = Math.max(1, leafRadius / 2 + 1);
				int lobeHeight = Math.max(1, (int)Math.ceil(this.height * 0.65));
				int mainTop = this.height / 2;
				int mainBottom = mainTop - this.height + 1;
				int lobeTop = lobeHeight / 2;
				int lobeBottom = lobeTop - lobeHeight + 1;
				placeBlob(level, foliageSetter, random, config, origin, leafRadius, this.height, attachment.doubleTrunk());
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					int requestedOffset = random.nextInt(3) - 1;
					int verticalOffset = Math.max(
						mainBottom - lobeBottom,
						Math.min(mainTop - lobeTop, requestedOffset)
					);
					BlockPos lobe = origin.relative(direction, Math.max(1, leafRadius - 1)).offset(0, verticalOffset, 0);
					placeBlob(level, foliageSetter, random, config, lobe, lobeRadius, lobeHeight, false);
				}
				bottomY = mainBottom;
				bottomRadius = Math.max(1, leafRadius / 2);
			}
			case COLUMNAR -> {
				int top = Math.max(1, this.height / 4);
				bottomY = top - this.height + 1;
				for (int y = top; y >= bottomY; y--) {
					double position = this.height <= 1 ? 0.5 : (y - bottomY) / (double)(this.height - 1);
					// A long capsule rather than a rescaled ROUND crown: its middle
					// keeps the requested radius while both ends close decisively.
					double endDistance = Math.min(position, 1.0 - position) * 2.0;
					double scale = 0.42 + 0.58 * Math.sqrt(Math.max(0.0, endDistance));
					int radius = Math.max(1, (int)Math.round(leafRadius * scale));
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) bottomRadius = radius;
				}
			}
			case PAGODA -> {
				int top = 1;
				bottomY = top - this.height + 1;
				for (int y = top; y >= bottomY; y--) {
					int depth = top - y;
					double descent = this.height <= 1 ? 1.0 : depth / (double)(this.height - 1);
					int tierRadius = Math.max(1, (int)Math.ceil(leafRadius * (0.35 + 0.65 * descent)));
					// Wide eaves alternate with a sharply inset row. Unlike LAYERED,
					// every successive tier also grows broader toward the ground.
					int radius = depth % 2 == 0 ? tierRadius : Math.max(1, tierRadius - 2);
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) bottomRadius = radius;
				}
			}
			case WINDSWEPT -> {
				// One world has one prevailing wind. Choosing from the world seed,
				// rather than once per foliage attachment, keeps every branch crown
				// of one tree (and every tree in a wind-shaped forest) leaning the
				// same way.
				long worldSeed = level.getSeed();
				long windSeed = worldSeed ^ (worldSeed >>> 32);
				Direction wind = switch (Math.floorMod((int)windSeed, 4)) {
					case 0 -> Direction.NORTH;
					case 1 -> Direction.EAST;
					case 2 -> Direction.SOUTH;
					default -> Direction.WEST;
				};
				int top = Math.max(1, this.height / 3);
				bottomY = top - this.height + 1;
				for (int y = top; y >= bottomY; y--) {
					double position = this.height <= 1 ? 0.0 : (y - bottomY) / (double)(this.height - 1);
					double middle = 1.0 - Math.abs(position * 2.0 - 1.0);
					int radius = Math.max(1, (int)Math.round(leafRadius * (0.55 + 0.45 * middle)));
					int drift = (int)Math.round(leafRadius * 0.9 * position);
					BlockPos sweptOrigin = origin.relative(wind, drift);
					placeLeavesRow(level, foliageSetter, random, config, sweptOrigin, radius, y, attachment.doubleTrunk());
					if (y == bottomY) {
						bottomRadius = radius;
						hangingOrigin = sweptOrigin;
					}
				}
			}
		}

		if (this.hangingLeaves > 0.0F) {
			placeLeavesRowWithHangingLeavesBelow(
				level,
				foliageSetter,
				random,
				config,
				hangingOrigin,
				bottomRadius,
				bottomY,
				attachment.doubleTrunk(),
				this.hangingLeaves,
				this.hangingLeaves * 0.7F
			);
		}
	}

	private void placeBlob(
		WorldGenLevel level,
		FoliageSetter setter,
		RandomSource random,
		TreeConfiguration config,
		BlockPos origin,
		int radius,
		int height,
		boolean doubleTrunk
	) {
		int top = height / 2;
		int bottom = top - height + 1;
		for (int y = top; y >= bottom; y--) {
			double vertical = height <= 1 ? 0.0 : Math.abs(y - (top + bottom) / 2.0) / (height / 2.0);
			int layerRadius = Math.max(0, (int)Math.round(radius * Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical))));
			placeLeavesRow(level, setter, random, config, origin, layerRadius, y, doubleTrunk);
		}
	}

	@Override
	public int foliageHeight(RandomSource random, int treeHeight, TreeConfiguration config) {
		return this.height;
	}

	@Override
	protected boolean shouldSkipLocation(
		RandomSource random,
		int dx,
		int y,
		int dz,
		int currentRadius,
		boolean doubleTrunk
	) {
		if (dx == 0 && dz == 0) {
			return false;
		}
		if (currentRadius <= 0) {
			return true;
		}
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > currentRadius + 0.35) {
			return true;
		}
		double edge = distance / currentRadius;
		double edgeLoss = this.irregularity * Math.max(0.0, edge - 0.45) * 0.65;
		double keepChance = Math.max(0.1, this.density - edgeLoss);
		return random.nextFloat() > keepChance;
	}
}
