package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Local, deterministic candidate arbitration. Never inspects or creates chunks.
 * Every site loses to a higher-priority overlapping candidate, even if that
 * neighbour later fails its biome/terrain check. Authored anchors beat random
 * sites; equal modes use stable seeded ranks, never exploration order.
 */
public final class WorldsmithStructureLayout {
    public record Member(Identifier id, String scope, int spacing, int separation, int salt,
        BoundingBox envelope, Optional<WorldsmithStructureAnchor> anchor) {
        public static final Codec<Member> CODEC = RecordCodecBuilder.<Member>create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(Member::id),
            Codec.STRING.fieldOf("scope").forGetter(Member::scope),
            Codec.intRange(2, 4096).fieldOf("spacing").forGetter(Member::spacing),
            Codec.intRange(1, 4095).fieldOf("separation").forGetter(Member::separation),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("salt").forGetter(Member::salt),
            BoundingBox.CODEC.fieldOf("envelope").forGetter(Member::envelope),
            WorldsmithStructureAnchor.CODEC.optionalFieldOf("anchor").forGetter(Member::anchor)
        ).apply(i, Member::new)).validate(m -> {
            if (m.separation >= m.spacing) return DataResult.error(() -> "Spacing must exceed separation");
            if (m.scope.isBlank() || m.scope.length() > 64 || m.envelope.minY() != 0 || m.envelope.maxY() != 0 ||
                m.envelope.minX() < -128 || m.envelope.minZ() < -128 || m.envelope.maxX() > 128 || m.envelope.maxZ() > 128) {
                return DataResult.error(() -> "Invalid bounded layout envelope");
            }
            return DataResult.success(m);
        });

        public RandomSpreadStructurePlacement randomPlacement() {
            return new RandomSpreadStructurePlacement(spacing, separation, RandomSpreadType.LINEAR, salt);
        }

        public StructurePlacement placement() {
            return anchor.<StructurePlacement>map(a -> new WorldsmithAnchorStructurePlacement(a, salt)).orElseGet(this::randomPlacement);
        }

        public Optional<BlockPos> siteInChunk(ChunkPos chunk, WorldsmithAnchorFields.NoiseSampler noise) {
            return anchor.map(a -> a.inChunk(chunk, noise)).orElseGet(() -> Optional.of(middle(chunk)));
        }

        public BoundingBox bounds(BlockPos pivot) {
            return new BoundingBox(pivot.getX() + envelope.minX(), 0, pivot.getZ() + envelope.minZ(),
                pivot.getX() + envelope.maxX(), 0, pivot.getZ() + envelope.maxZ());
        }

        /** Enumerates only pivots that could collide, using their actual block coordinates. */
        public List<BlockPos> sitesIn(BoundingBox area, long seed, WorldsmithAnchorFields.NoiseSampler noise) {
            if (anchor.isPresent()) return anchor.get().sitesIn(area, noise);
            int minChunkX = ceilDiv(area.minX() - 8, 16);
            int maxChunkX = Math.floorDiv(area.maxX() - 8, 16);
            int minChunkZ = ceilDiv(area.minZ() - 8, 16);
            int maxChunkZ = Math.floorDiv(area.maxZ() - 8, 16);
            var placement = randomPlacement();
            List<BlockPos> result = new ArrayList<>();
            for (int x = Math.floorDiv(minChunkX, spacing); x <= Math.floorDiv(maxChunkX, spacing); x++) {
                for (int z = Math.floorDiv(minChunkZ, spacing); z <= Math.floorDiv(maxChunkZ, spacing); z++) {
                    var pivot = middle(placement.getPotentialStructureChunk(seed, x * spacing, z * spacing));
                    if (area.isInside(pivot)) result.add(pivot);
                }
            }
            return result;
        }
    }

    private WorldsmithStructureLayout() {}

    public static BlockPos middle(ChunkPos chunk) {
        return new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
    }

    public static BoundingBox envelope(BlockPos size, BlockPos origin, List<Rotation> rotations, int clearance) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Rotation rotation : rotations) {
            BlockPos pivot = origin.rotate(rotation);
            for (int x : new int[]{0, size.getX() - 1}) for (int z : new int[]{0, size.getZ() - 1}) {
                BlockPos p = new BlockPos(x, 0, z).rotate(rotation).subtract(pivot);
                minX = Math.min(minX, p.getX()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxZ = Math.max(maxZ, p.getZ());
            }
        }
        return new BoundingBox(minX - clearance, 0, minZ - clearance, maxX + clearance, 0, maxZ + clearance);
    }

    public static boolean accepts(Member self, BlockPos pivot, long seed, WorldsmithAnchorFields.NoiseSampler noise, Collection<Member> members) {
        BoundingBox bounds = self.bounds(pivot);
        ChunkPos source = ChunkPos.containing(pivot);
        for (Member other : members) {
            if (!self.scope.equals(other.scope)) continue;
            BoundingBox area = new BoundingBox(bounds.minX() - other.envelope.maxX(), 0, bounds.minZ() - other.envelope.maxZ(),
                bounds.maxX() - other.envelope.minX(), 0, bounds.maxZ() - other.envelope.minZ());
            for (BlockPos candidate : other.sitesIn(area, seed, noise)) {
                ChunkPos chunk = ChunkPos.containing(candidate);
                if (self.id.equals(other.id) && source.equals(chunk)) continue;
                if (bounds.intersects(other.bounds(candidate)) && compare(other, chunk, self, source, seed) < 0) return false;
            }
        }
        return true;
    }

    private static int ceilDiv(int value, int divisor) { return -Math.floorDiv(-value, divisor); }

    private static int compare(Member a, ChunkPos pa, Member b, ChunkPos pb, long seed) {
        int order = Boolean.compare(b.anchor.isPresent(), a.anchor.isPresent());
        if (order != 0) return order;
        order = Long.compareUnsigned(rank(a, pa, seed), rank(b, pb, seed));
        if (order != 0) return order;
        order = a.id.toString().compareTo(b.id.toString());
        if (order != 0) return order;
        order = Integer.compare(pa.x(), pb.x());
        return order != 0 ? order : Integer.compare(pa.z(), pb.z());
    }

    private static long rank(Member member, ChunkPos pos, long seed) {
        long value = seed ^ ((long) member.salt << 32) ^ (pos.x() * 0x9E3779B97F4A7C15L) ^ (pos.z() * 0xD1B54A32D192ED03L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
