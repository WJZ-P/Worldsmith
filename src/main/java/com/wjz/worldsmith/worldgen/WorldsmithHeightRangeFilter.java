package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Keeps a position only when its final, recipe-selected origin is in an
 * authored absolute-Y interval.
 *
 * <p>A vanilla height-range modifier samples a new Y and therefore cannot be
 * used after a surface heightmap or a cave scan. This filter preserves the
 * surface that was found and merely accepts or rejects it.
 */
final class WorldsmithHeightRangeFilter extends PlacementFilter {
	static final MapCodec<WorldsmithHeightRangeFilter> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			Codec.intRange(-64, 319).fieldOf("min_y").forGetter(filter -> filter.minY),
			Codec.intRange(-64, 319).fieldOf("max_y").forGetter(filter -> filter.maxY)
		).apply(instance, WorldsmithHeightRangeFilter::new)
	);

	private final int minY;
	private final int maxY;

	private WorldsmithHeightRangeFilter(int minY, int maxY) {
		this.minY = minY;
		this.maxY = maxY;
	}

	static WorldsmithHeightRangeFilter of(int minY, int maxY) {
		if (minY > maxY) {
			throw new IllegalArgumentException("minimum feature Y exceeds maximum feature Y");
		}
		return new WorldsmithHeightRangeFilter(minY, maxY);
	}

	@Override
	protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin) {
		return origin.getY() >= this.minY && origin.getY() <= this.maxY;
	}

	@Override
	public PlacementModifierType<?> type() {
		return WorldsmithPlacementModifierTypes.heightRangeFilter();
	}
}
