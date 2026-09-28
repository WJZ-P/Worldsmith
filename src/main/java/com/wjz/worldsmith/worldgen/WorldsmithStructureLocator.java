package com.wjz.worldsmith.worldgen;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.Worldsmith;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.jspecify.annotations.Nullable;

/**
 * Locate for Worldsmith template structures. Vanilla walks up to maxRadius
 * rings of a random spread and runs each candidate's full terrain fit on the
 * server thread, so a rare structure that must fit terrain stalled /locate for
 * minutes. The same rings, and anchor sites nearest first, are searched here
 * against a wall-clock budget; when it runs out the nearest start found so far
 * is returned, or nothing, and the log says the search was cut short.
 */
public final class WorldsmithStructureLocator {
    public static final long BUDGET_MILLIS = 5000;
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    /** Leaves a presence check by exception, so no result is cached for that chunk. */
    static final class BudgetSpent extends RuntimeException {
        BudgetSpent() { super(null, null, false, false); }
    }

    public record Split(HolderSet<Structure> ours, HolderSet<Structure> others) {}

    private WorldsmithStructureLocator() {}

    /** A no-op outside a locate. Costly probe steps call it so a search can stop inside one candidate. */
    public static void checkBudget() {
        Long deadline = DEADLINE.get();
        if (deadline != null && System.nanoTime() - deadline > 0) throw new BudgetSpent();
    }

    /** Runs one presence check under a deadline that the probe steps inside it observe. */
    static <T> T within(long deadline, Supplier<T> work) {
        DEADLINE.set(deadline);
        try {
            return work.get();
        } finally {
            DEADLINE.remove();
        }
    }

    /** Null when nothing wanted is a Worldsmith structure, which leaves vanilla untouched. */
    public static @Nullable Split split(ChunkGeneratorStructureState state, HolderSet<Structure> wanted) {
        List<Holder<Structure>> ours = new ArrayList<>(), others = new ArrayList<>();
        for (Holder<Structure> structure : wanted) {
            boolean handled = structure.value() instanceof WorldsmithTemplateStructure && state.getPlacementsForStructure(structure).stream()
                .allMatch(p -> p instanceof RandomSpreadStructurePlacement || p instanceof WorldsmithAnchorStructurePlacement);
            (handled ? ours : others).add(structure);
        }
        return ours.isEmpty() ? null : new Split(HolderSet.direct(ours), HolderSet.direct(others));
    }

    public static @Nullable Pair<BlockPos, Holder<Structure>> findNearest(ServerLevel level, HolderSet<Structure> wanted,
        BlockPos origin, int maxRadius, boolean createReference, @Nullable Pair<BlockPos, Holder<Structure>> vanilla) {
        if (SharedConstants.DEBUG_DISABLE_FEATURES || !level.getServer().getWorldGenSettings().options().generateStructures()) return vanilla;
        var state = level.getChunkSource().getGeneratorState();
        Map<RandomSpreadStructurePlacement, List<Holder<Structure>>> spreads = new LinkedHashMap<>();
        for (Holder<Structure> structure : wanted) for (var placement : state.getPlacementsForStructure(structure)) {
            if (placement instanceof RandomSpreadStructurePlacement spread) spreads.computeIfAbsent(spread, p -> new ArrayList<>()).add(structure);
        }
        long started = System.nanoTime();
        var search = new Search(level, origin, createReference, vanilla, started + BUDGET_MILLIS * 1_000_000L);
        try {
            search.rings(spreads, state.getLevelSeed(), maxRadius);
            for (var candidate : WorldsmithAnchorStructureLocator.candidates(state, wanted, origin, maxRadius)) {
                if (origin.distSqr(candidate.pivot()) >= search.bound) break;
                search.check(candidate.structure(), candidate.placement(), ChunkPos.containing(candidate.pivot()), candidate.pivot());
            }
        } catch (BudgetSpent spent) {
            Worldsmith.LOGGER.warn("Locating {} stopped after {} ms and {} candidate checks, at ring {} of {}; {}. Candidates that must fit terrain are too costly to search exhaustively on the server thread.",
                wanted.stream().map(h -> h.unwrapKey().map(k -> k.identifier().toString()).orElse("?")).toList(),
                (System.nanoTime() - started) / 1_000_000L, search.checked, Math.min(search.ring, maxRadius), maxRadius,
                search.best == null ? "nothing was found in the searched area" : "returning the nearest start found so far");
        }
        return search.best;
    }

    private static final class Search {
        private final ServerLevel level;
        private final BlockPos origin;
        private final boolean createReference;
        private final long deadline;
        private @Nullable Pair<BlockPos, Holder<Structure>> best;
        private double bound;
        private int checked, ring;

        Search(ServerLevel level, BlockPos origin, boolean createReference, @Nullable Pair<BlockPos, Holder<Structure>> vanilla, long deadline) {
            this.level = level; this.origin = origin; this.createReference = createReference; this.deadline = deadline;
            this.best = vanilla;
            this.bound = vanilla == null ? Double.POSITIVE_INFINITY : origin.distSqr(vanilla.getFirst());
        }

        /** Vanilla's ring order and semantics: the first ring holding a start ends the search. */
        void rings(Map<RandomSpreadStructurePlacement, List<Holder<Structure>>> spreads, long seed, int maxRadius) {
            int chunkX = SectionPos.blockToSectionCoord(origin.getX()), chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
            for (ring = 0; ring <= maxRadius && !spreads.isEmpty(); ring++) {
                boolean found = false, reachable = false;
                for (var entry : spreads.entrySet()) {
                    var placement = entry.getKey();
                    int spacing = placement.spacing();
                    // Each cell of this ring lies at least (ring - 1) spacings away on one axis.
                    long near = (long) Math.max(0, ring - 1) * spacing * 16;
                    if ((double) near * near >= bound) continue;
                    reachable = true;
                    for (int x = -ring; x <= ring; x++) for (int z = -ring; z <= ring; z++) {
                        if (Math.abs(x) != ring && Math.abs(z) != ring) continue;
                        var chunk = placement.getPotentialStructureChunk(seed, chunkX + spacing * x, chunkZ + spacing * z);
                        var at = placement.getLocatePos(chunk);
                        if (origin.distSqr(at) >= bound) continue;
                        for (var structure : entry.getValue()) if (check(structure, placement, chunk, at)) { found = true; break; }
                    }
                }
                if (found || !reachable) return;
            }
        }

        boolean check(Holder<Structure> structure, StructurePlacement placement, ChunkPos chunk, BlockPos at) {
            if (System.nanoTime() - deadline > 0) throw new BudgetSpent();
            checked++;
            var manager = level.structureManager();
            var presence = within(deadline, () -> manager.checkStructurePresence(chunk, structure.value(), placement, createReference));
            if (presence == StructureCheckResult.START_NOT_PRESENT) return false;
            if (createReference || presence != StructureCheckResult.START_PRESENT) {
                var access = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS);
                var start = manager.getStartForStructure(SectionPos.bottomOf(access), structure.value(), access);
                if (start == null || !start.isValid() || createReference && !start.canBeReferenced()) return false;
                if (createReference) manager.addReference(start);
            }
            best = Pair.of(at, structure);
            bound = origin.distSqr(at);
            return true;
        }
    }
}
