package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

/**
 * Local, deterministic candidate arbitration. Never inspects or creates chunks.
 *
 * Every site loses to any lower-ranked overlapping candidate, even if that
 * neighbour later fails its biome/terrain check. This conservative reservation
 * is deliberate: deciding from already-generated starts makes exploration order
 * change the world, while recursively fitting every neighbour is unbounded.
 */
public final class WorldsmithStructureLayout {
    public record Member(Identifier id, String scope, int spacing, int separation, int salt, BoundingBox envelope) {
        public static final Codec<Member> CODEC = RecordCodecBuilder.<Member>create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(Member::id),
            Codec.STRING.fieldOf("scope").forGetter(Member::scope),
            Codec.intRange(2,4096).fieldOf("spacing").forGetter(Member::spacing),
            Codec.intRange(1,4095).fieldOf("separation").forGetter(Member::separation),
            Codec.intRange(0,Integer.MAX_VALUE).fieldOf("salt").forGetter(Member::salt),
            BoundingBox.CODEC.fieldOf("envelope").forGetter(Member::envelope)
        ).apply(i,Member::new)).validate(m -> {
            if (m.separation >= m.spacing) return DataResult.error(() -> "Spacing must exceed separation");
            if (m.scope.isBlank() || m.scope.length() > 64 || m.envelope.minY() != 0 || m.envelope.maxY() != 0 || m.envelope.minX() < -80 || m.envelope.minZ() < -80 || m.envelope.maxX() > 80 || m.envelope.maxZ() > 80) {
                return DataResult.error(() -> "Invalid bounded layout envelope");
            }
            return DataResult.success(m);
        });

        public RandomSpreadStructurePlacement placement() {
            return new RandomSpreadStructurePlacement(spacing,separation,RandomSpreadType.LINEAR,salt);
        }

        public BoundingBox bounds(ChunkPos chunk) {
            int x = chunk.getMiddleBlockX();
            int z = chunk.getMiddleBlockZ();
            return new BoundingBox(x+envelope.minX(),0,z+envelope.minZ(),x+envelope.maxX(),0,z+envelope.maxZ());
        }
    }

    private WorldsmithStructureLayout() {}

    public static BoundingBox envelope(BlockPos size, BlockPos origin, List<Rotation> rotations, int clearance) {
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for (Rotation rotation : rotations) {
            BlockPos pivot=origin.rotate(rotation);
            for(int x:new int[]{0,size.getX()-1}) for(int z:new int[]{0,size.getZ()-1}) {
                BlockPos p=new BlockPos(x,0,z).rotate(rotation).subtract(pivot);
                minX=Math.min(minX,p.getX()); minZ=Math.min(minZ,p.getZ());
                maxX=Math.max(maxX,p.getX()); maxZ=Math.max(maxZ,p.getZ());
            }
        }
        return new BoundingBox(minX-clearance,0,minZ-clearance,maxX+clearance,0,maxZ+clearance);
    }

    public static boolean accepts(Member self, ChunkPos source, long seed, Collection<Member> members) {
        BoundingBox bounds=self.bounds(source);
        for(Member other:members) {
            if(!self.scope.equals(other.scope)) continue;
            // Candidate anchors whose translated envelope could intersect ours.
            int minChunkX=ceilDiv(bounds.minX()-other.envelope.maxX()-8,16);
            int maxChunkX=Math.floorDiv(bounds.maxX()-other.envelope.minX()-8,16);
            int minChunkZ=ceilDiv(bounds.minZ()-other.envelope.maxZ()-8,16);
            int maxChunkZ=Math.floorDiv(bounds.maxZ()-other.envelope.minZ()-8,16);
            var placement=other.placement();
            for(int cellX=Math.floorDiv(minChunkX,other.spacing);cellX<=Math.floorDiv(maxChunkX,other.spacing);cellX++) {
                for(int cellZ=Math.floorDiv(minChunkZ,other.spacing);cellZ<=Math.floorDiv(maxChunkZ,other.spacing);cellZ++) {
                    ChunkPos candidate=placement.getPotentialStructureChunk(seed,cellX*other.spacing,cellZ*other.spacing);
                    if(self.id.equals(other.id) && source.equals(candidate)) continue;
                    if(bounds.intersects(other.bounds(candidate)) && compare(other,candidate,self,source,seed)<0) return false;
                }
            }
        }
        return true;
    }

    private static int ceilDiv(int value,int divisor) { return -Math.floorDiv(-value,divisor); }

    private static int compare(Member a,ChunkPos pa,Member b,ChunkPos pb,long seed) {
        int order=Long.compareUnsigned(rank(a,pa,seed),rank(b,pb,seed));
        if(order!=0)return order;
        order=a.id.toString().compareTo(b.id.toString());
        if(order!=0)return order;
        order=Integer.compare(pa.x(),pb.x());
        return order!=0?order:Integer.compare(pa.z(),pb.z());
    }

    private static long rank(Member member,ChunkPos pos,long seed) {
        long value=seed ^ ((long)member.salt<<32) ^ (pos.x()*0x9E3779B97F4A7C15L) ^ (pos.z()*0xD1B54A32D192ED03L);
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }
}
