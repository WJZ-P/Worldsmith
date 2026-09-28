package com.wjz.worldsmith.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Vanilla locate only scans its two native placement classes. Enumerates anchor
 * sites nearest first for {@link WorldsmithStructureLocator}, without loading
 * chunks; the presence checks themselves run only for explicit locate/map queries.
 */
public final class WorldsmithAnchorStructureLocator {
    public static final int MAX_SITE_CHECKS = 256;
    public static final int MAX_CELL_RADIUS = 8;

    public record Candidate(Holder<Structure> structure, WorldsmithAnchorStructurePlacement placement, BlockPos pivot) {}

    private WorldsmithAnchorStructureLocator() {}

    /** Pure candidate enumeration; shared by the locate hook and JVM tests. */
    public static List<Candidate> candidates(ChunkGeneratorStructureState state, HolderSet<Structure> wanted,
        BlockPos origin, int maxRadius) {
        List<Candidate> candidates = new ArrayList<>();
        WorldsmithAnchorFields.NoiseSampler noise = null;
        for (Holder<Structure> structure : wanted) {
            for (var placement : state.getPlacementsForStructure(structure)) {
                if (!(placement instanceof WorldsmithAnchorStructurePlacement anchored)) continue;
                var target = anchored.anchor();
                if (target instanceof WorldsmithStructureAnchor.Fixed fixed) {
                    BlockPos pivot = new BlockPos(fixed.x(), 0, fixed.z());
                    if (WorldsmithStructureAnchor.insideWorld(pivot)) candidates.add(new Candidate(structure, anchored, pivot));
                } else if (target instanceof WorldsmithStructureAnchor.Scattered grid) {
                    if (noise == null) noise = WorldsmithAnchorStructurePlacement.noise(state.randomState());
                    int radius = Math.clamp(maxRadius, 0, MAX_CELL_RADIUS);
                    int x = Math.floorDiv(origin.getX() - grid.offsetX(), grid.spacing());
                    int z = Math.floorDiv(origin.getZ() - grid.offsetZ(), grid.spacing());
                    var bounds = new BoundingBox(limit((long) (x-radius)*grid.spacing()+grid.offsetX()-1), 0,
                        limit((long) (z-radius)*grid.spacing()+grid.offsetZ()-1),
                        limit((long) (x+radius+1)*grid.spacing()+grid.offsetX()+1), 0,
                        limit((long) (z+radius+1)*grid.spacing()+grid.offsetZ()+1));
                    for (BlockPos pivot : grid.sitesIn(bounds, noise)) candidates.add(new Candidate(structure, anchored, pivot));
                }
            }
        }
        // Pick the nearest actual successful start, not simply the first lattice
        // cell visited. Bound expensive presence checks even if every biome rejects.
        return candidates.stream().sorted(Comparator.comparingDouble((Candidate c) -> origin.distSqr(c.pivot))
            .thenComparing(c -> c.structure.unwrapKey().map(k -> k.identifier().toString()).orElse(""))
            .thenComparing(Candidate::pivot, WorldsmithStructureAnchor.POSITION_ORDER)).limit(MAX_SITE_CHECKS).toList();
    }

    private static int limit(long coordinate) {
        return (int) Math.clamp(coordinate, -WorldsmithStructureAnchor.WORLD_LIMIT, WorldsmithStructureAnchor.WORLD_LIMIT);
    }
}
