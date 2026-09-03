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
				}
			}
			case UMBRELLA -> {
				int layers = Math.min(4, this.height);
				bottomY = 1 - layers;
				for (int y = 1; y >= bottomY; y--) {
					int radius = y == 1 || y == bottomY ? Math.max(1, leafRadius - 1) : leafRadius;
					placeLeavesRow(level, foliageSetter, random, config, origin, radius, y, attachment.doubleTrunk());
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
				int lobeHeight = Math.max(2, this.height / 2);
				placeBlob(level, foliageSetter, random, config, origin, leafRadius, lobeHeight, attachment.doubleTrunk());
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos lobe = origin.relative(direction, Math.max(1, leafRadius - 1)).offset(0, random.nextInt(3) - 1, 0);
					placeBlob(level, foliageSetter, random, config, lobe, lobeRadius, lobeHeight, false);
				}
				bottomY = -(lobeHeight / 2);
				bottomRadius = lobeRadius;
			}
		}

		if (this.hangingLeaves > 0.0F) {
			placeLeavesRowWithHangingLeavesBelow(
				level,
				foliageSetter,
				random,
				config,
				origin,
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
