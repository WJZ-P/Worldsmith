package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.TerrainShape;
import com.wjz.worldsmith.core.structure.StructureAnchorTarget;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Resolved placement geometry, copied from the named terrain anchor at export time. */
public sealed interface WorldsmithStructureAnchor {
    int WORLD_LIMIT = 29_999_000;
    Comparator<BlockPos> POSITION_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);

    enum Kind implements StringRepresentable {
        FIXED, SCATTERED;
        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    Codec<WorldsmithStructureAnchor> CODEC = Kind.CODEC.dispatch("type", WorldsmithStructureAnchor::kind,
        kind -> switch (kind) { case FIXED -> Fixed.CODEC; case SCATTERED -> Scattered.CODEC; });

    Kind kind();

    /** Unique candidate pivots per chunk, inside the given horizontal bounds. No chunks are read. */
    List<BlockPos> sitesIn(BoundingBox bounds, WorldsmithAnchorFields.NoiseSampler noise);

    default Optional<BlockPos> inChunk(ChunkPos chunk, WorldsmithAnchorFields.NoiseSampler noise) {
        return sitesIn(new BoundingBox(chunk.getMinBlockX(), 0, chunk.getMinBlockZ(),
            chunk.getMaxBlockX(), 0, chunk.getMaxBlockZ()), noise).stream().findFirst();
    }

    static Optional<WorldsmithStructureAnchor> resolve(CompiledPack pack, StructureAnchorTarget target) {
        if (target == null) return Optional.empty();
        if (!(pack.pack().getTerrain().getShape() instanceof TerrainShape.Procedural shape)) {
            throw new IllegalArgumentException("Structure anchors require procedural terrain");
        }
        var anchor = shape.getAnchors().stream().filter(a -> a.getId().equals(target.getId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown structure anchor: " + target.getId()));
        return Optional.of(switch (anchor.getPlacement()) {
            case AnchorPlacement.Fixed p -> new Fixed(Math.toIntExact((long) p.getX() + target.getOffsetX()),
                Math.toIntExact((long) p.getZ() + target.getOffsetZ()));
            case AnchorPlacement.Line p -> new Fixed(
                Math.toIntExact(Math.round(p.getStartX() + (p.getEndX() - (double) p.getStartX()) * target.getAlong()) + target.getOffsetX()),
                Math.toIntExact(Math.round(p.getStartZ() + (p.getEndZ() - (double) p.getStartZ()) * target.getAlong()) + target.getOffsetZ()));
            case AnchorPlacement.Scattered p -> new Scattered(p.getSpacing(), p.getJitter(), target.getOffsetX(), target.getOffsetZ());
        });
    }

    static boolean insideWorld(BlockPos pos) {
        return pos.getX() >= -WORLD_LIMIT && pos.getX() <= WORLD_LIMIT && pos.getZ() >= -WORLD_LIMIT && pos.getZ() <= WORLD_LIMIT;
    }

    record Fixed(int x, int z) implements WorldsmithStructureAnchor {
        public static final MapCodec<Fixed> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(-WORLD_LIMIT, WORLD_LIMIT).fieldOf("x").forGetter(Fixed::x),
            Codec.intRange(-WORLD_LIMIT, WORLD_LIMIT).fieldOf("z").forGetter(Fixed::z)
        ).apply(i, Fixed::new));

        @Override public Kind kind() { return Kind.FIXED; }
        @Override public List<BlockPos> sitesIn(BoundingBox bounds, WorldsmithAnchorFields.NoiseSampler noise) {
            var point = new BlockPos(x, 0, z);
            return insideWorld(point) && bounds.isInside(point) ? List.of(point) : List.of();
        }
    }

    record Scattered(int spacing, double jitter, int offsetX, int offsetZ) implements WorldsmithStructureAnchor {
        public static final MapCodec<Scattered> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(64, 1_000_000).fieldOf("spacing").forGetter(Scattered::spacing),
            Codec.doubleRange(0, 1).fieldOf("jitter").forGetter(Scattered::jitter),
            Codec.intRange(-4096, 4096).fieldOf("offset_x").forGetter(Scattered::offsetX),
            Codec.intRange(-4096, 4096).fieldOf("offset_z").forGetter(Scattered::offsetZ)
        ).apply(i, Scattered::new));

        @Override public Kind kind() { return Kind.SCATTERED; }

        public BlockPos cell(int cellX, int cellZ, WorldsmithAnchorFields.NoiseSampler noise) {
            return new BlockPos(
                Math.toIntExact(Math.round(WorldsmithAnchorFields.latticeCoordinate(cellX, cellZ, spacing, jitter, false, noise)) + offsetX), 0,
                Math.toIntExact(Math.round(WorldsmithAnchorFields.latticeCoordinate(cellX, cellZ, spacing, jitter, true, noise)) + offsetZ));
        }

        @Override public List<BlockPos> sitesIn(BoundingBox bounds, WorldsmithAnchorFields.NoiseSampler noise) {
            // Canonicalise using complete chunks BEFORE clipping to the requested
            // box. At jitter=1, adjacent terrain anchors can land in the same
            // chunk; Minecraft stores only one start per structure per chunk.
            int minX = Math.floorDiv(Math.floorDiv(bounds.minX(), 16) * 16 - offsetX - 1, spacing);
            int maxX = Math.floorDiv(Math.floorDiv(bounds.maxX(), 16) * 16 + 16 - offsetX, spacing);
            int minZ = Math.floorDiv(Math.floorDiv(bounds.minZ(), 16) * 16 - offsetZ - 1, spacing);
            int maxZ = Math.floorDiv(Math.floorDiv(bounds.maxZ(), 16) * 16 + 16 - offsetZ, spacing);
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 4096) {
                throw new IllegalArgumentException("Anchor candidate query exceeds its bounded neighbourhood");
            }
            Map<ChunkPos, BlockPos> unique = new HashMap<>();
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                BlockPos point = cell(x, z, noise);
                if (insideWorld(point)) unique.merge(ChunkPos.containing(point), point,
                    (a, b) -> POSITION_ORDER.compare(a, b) <= 0 ? a : b);
            }
            return unique.values().stream().filter(bounds::isInside).sorted(POSITION_ORDER).toList();
        }
    }
}
