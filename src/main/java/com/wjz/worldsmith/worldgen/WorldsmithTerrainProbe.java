package com.wjz.worldsmith.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Bounded, side-effect-free site fitting, shared by generation and diagnostic tests. */
public final class WorldsmithTerrainProbe {
    public static final int MAX_FOUNDATION_BLOCKS = 4096;
    public enum Rejection { WRONG_FLUID, EXCESSIVE_SLOPE, OUTSIDE_WORLD, MISSING_SUPPORT, FOUNDATION_BUDGET }
    public record Column(int groundY, int surfaceY) {}
    public record Plan(BlockPos position, List<BoundingBox> foundations) {}
    public record Result(Plan plan, Rejection rejection) {
        public boolean accepted() { return this.plan != null; }
    }
    @FunctionalInterface public interface Sampler { Column sample(int x, int z); }

    /** One candidate (including its rotations), never a world-wide growing cache. */
    public static final class CachedSampler implements Sampler {
        private final Sampler source;
        private final Map<Long, Column> columns = new HashMap<>();
        public CachedSampler(Sampler source) { this.source = source; }
        @Override public Column sample(int x, int z) {
            long key = ((long) x << 32) | (z & 0xffffffffL);
            return columns.computeIfAbsent(key, ignored -> source.sample(x, z));
        }
        public int sampledColumns() { return columns.size(); }
    }

    private WorldsmithTerrainProbe() {}

    /** The two vanilla heightmap predicates derived from ONE noise column. */
    public static Column readColumn(NoiseColumn column, int worldMin, int worldMax) {
        int ground = worldMin, surface = worldMin;
        boolean foundGround = false, foundSurface = false;
        for (int y = worldMax; y >= worldMin; y--) {
            var state = column.getBlock(y);
            if (!foundSurface && Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state)) {
                surface = y + 1;
                foundSurface = true;
            }
            if (!foundGround && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                ground = y + 1;
                foundGround = true;
            }
            if (foundGround && foundSurface) break;
        }
        return new Column(ground, surface);
    }

    public static Result probe(WorldsmithTemplateStructure.Settings config, BlockPos anchor, Rotation rotation,
        int worldMin, int worldMax, Sampler sampler) {
        BlockPos translated = anchor.atY(0).subtract(config.origin().rotate(rotation));
        Sampler cached = sampler instanceof CachedSampler ? sampler : new CachedSampler(sampler);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (BlockPos local : config.footprint()) {
            BlockPos pos = local.rotate(rotation).offset(translated);
            Column column = cached.sample(pos.getX(), pos.getZ());
            if (config.surface().equals("LAND_SURFACE") && column.surfaceY() > column.groundY() ||
                config.surface().equals("OCEAN_FLOOR") && column.surfaceY() <= column.groundY()) return rejected(Rejection.WRONG_FLUID);
            min = Math.min(min, column.groundY());
            max = Math.max(max, column.groundY());
            if (max - min > config.maxHeightDifference()) return rejected(Rejection.EXCESSIVE_SLOPE);
        }
        if (max == Integer.MIN_VALUE) return rejected(Rejection.MISSING_SUPPORT);
        if (min < worldMin + 1 || max + config.size().getY() > worldMax + 1) return rejected(Rejection.OUTSIDE_WORLD);

        List<BoundingBox> columns = new ArrayList<>();
        int fillBlocks = 0;
        for (BlockPos local : config.supports()) {
            BlockPos point = local.rotate(rotation).offset(translated);
            int ground = cached.sample(point.getX(), point.getZ()).groundY();
            int gap = max - ground;
            if (gap < 0 || config.foundation().equals("NONE") && gap != 0 ||
                !config.foundation().equals("NONE") && gap > config.maxDepth()) return rejected(Rejection.MISSING_SUPPORT);
            if (!config.foundation().equals("NONE") && gap > 0) {
                fillBlocks += gap;
                if (fillBlocks > MAX_FOUNDATION_BLOCKS) return rejected(Rejection.FOUNDATION_BUDGET);
                columns.add(new BoundingBox(point.getX(), ground, point.getZ(), point.getX(), max - 1, point.getZ()));
            }
        }
        return new Result(new Plan(new BlockPos(translated.getX(), max, translated.getZ()), List.copyOf(columns)), null);
    }

    private static Result rejected(Rejection rejection) { return new Result(null, rejection); }
}
