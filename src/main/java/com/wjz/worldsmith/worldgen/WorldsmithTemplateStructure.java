package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/** Thin single-template structure: deterministic site probe, rigid placement, bounded foundations. */
public final class WorldsmithTemplateStructure extends Structure {
    public record Settings(
        Identifier template, BlockPos size, BlockPos origin, List<Rotation> rotations,
        String surface, int maxHeightDifference, String foundation, BlockState foundationState,
        int maxDepth, List<BlockPos> supports, List<BoundingBox> reserved,
        List<BlockPos> footprint, WorldsmithStructureLayout.Member layout
    ) {
        public static final Codec<Settings> CODEC = RecordCodecBuilder.<Settings>create(i -> i.group(
            Identifier.CODEC.fieldOf("template").forGetter(Settings::template),
            BlockPos.CODEC.fieldOf("size").forGetter(Settings::size),
            BlockPos.CODEC.fieldOf("origin").forGetter(Settings::origin),
            Rotation.CODEC.listOf().fieldOf("rotations").forGetter(Settings::rotations),
            Codec.STRING.fieldOf("surface").forGetter(Settings::surface),
            Codec.intRange(0, 12).fieldOf("max_height_difference").forGetter(Settings::maxHeightDifference),
            Codec.STRING.fieldOf("foundation").forGetter(Settings::foundation),
            BlockState.CODEC.fieldOf("foundation_state").forGetter(Settings::foundationState),
            Codec.intRange(0, 16).fieldOf("max_depth").forGetter(Settings::maxDepth),
            BlockPos.CODEC.listOf().fieldOf("supports").forGetter(Settings::supports),
            BoundingBox.CODEC.listOf().fieldOf("reserved").forGetter(Settings::reserved),
            BlockPos.CODEC.listOf().fieldOf("footprint").forGetter(Settings::footprint),
            WorldsmithStructureLayout.Member.CODEC.fieldOf("layout").forGetter(Settings::layout)
        ).apply(i, Settings::new)).validate(s -> {
            if (s.rotations.isEmpty() || s.rotations.size() > 4 || s.rotations.stream().distinct().count() != s.rotations.size()) return DataResult.error(() -> "Invalid structure rotations");
            if (!List.of("LAND_SURFACE", "OCEAN_FLOOR").contains(s.surface)) return DataResult.error(() -> "Unknown structure surface");
            if (!List.of("NONE", "FILL", "PILLARS").contains(s.foundation)) return DataResult.error(() -> "Unknown foundation mode");
            if (s.size.getX()<1 || s.size.getX()>64 || s.size.getY()<1 || s.size.getY()>64 || s.size.getZ()<1 || s.size.getZ()>64) return DataResult.error(() -> "Invalid structure size");
            if(s.supports.isEmpty() || s.supports.size()>4096) return DataResult.error(() -> "Structure requires bounded floor support points");
            if(s.footprint.isEmpty() || s.footprint.size()>4096 || s.footprint.stream().anyMatch(p->p.getY()!=0 || p.getX()<0 || p.getX()>=s.size.getX() || p.getZ()<0 || p.getZ()>=s.size.getZ()))return DataResult.error(()->"Invalid complete terrain footprint");
            if(s.origin.getY()!=0 || s.origin.getX()<0 || s.origin.getX()>=s.size.getX() || s.origin.getZ()<0 || s.origin.getZ()>=s.size.getZ()) return DataResult.error(()->"Invalid template origin");
            if(s.supports.stream().anyMatch(p->p.getY()!=0 || p.getX()<0 || p.getX()>=s.size.getX() || p.getZ()<0 || p.getZ()>=s.size.getZ()))return DataResult.error(()->"Support point outside blueprint");
            var footprint = new HashSet<>(s.footprint);
            if (footprint.size() != s.footprint.size() || new HashSet<>(s.supports).size() != s.supports.size() || !footprint.containsAll(s.supports)) {
                return DataResult.error(() -> "Footprint and supports must be distinct; every support must participate in terrain fitting");
            }
            var required = WorldsmithStructureLayout.envelope(s.size, s.origin, s.rotations, 0);
            var envelope = s.layout.envelope();
            if (envelope.minX() > required.minX() || envelope.minZ() > required.minZ() || envelope.maxX() < required.maxX() || envelope.maxZ() < required.maxZ()) {
                return DataResult.error(() -> "Layout reservation must contain every allowed rotated footprint");
            }
            if(s.reserved.isEmpty() || s.reserved.size()>33 || s.reserved.stream().anyMatch(b->b.minX()<0 || b.minY()<0 || b.minZ()<0 || b.maxX()>=s.size.getX() || b.maxY()>=s.size.getY() || b.maxZ()>=s.size.getZ())) return DataResult.error(()->"Invalid decoration reservation boxes");
            return DataResult.success(s);
        });
    }

    public static final MapCodec<WorldsmithTemplateStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        settingsCodec(i), Settings.CODEC.fieldOf("template_settings").forGetter(s -> s.templateSettings)
    ).apply(i, WorldsmithTemplateStructure::new));

    private final Settings templateSettings;

    public WorldsmithTemplateStructure(StructureSettings settings, Settings templateSettings) {
        super(settings);
        this.templateSettings = templateSettings;
    }

    public Settings templateSettings() { return this.templateSettings; }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Settings config = this.templateSettings;
        // A missing template should produce no start, never an empty ghost structure.
        if (context.structureTemplateManager().get(config.template).isEmpty()) return Optional.empty();
        var peers=context.registryAccess().lookup(Registries.STRUCTURE)
            .map(registry->registry.stream().filter(s->s instanceof WorldsmithTemplateStructure)
                .map(s->((WorldsmithTemplateStructure)s).templateSettings.layout).toList())
            .orElse(List.of(config.layout));
        var jitter = config.layout.anchor().orElse(null) instanceof WorldsmithStructureAnchor.Scattered ||
            peers.stream().anyMatch(p -> p.anchor().orElse(null) instanceof WorldsmithStructureAnchor.Scattered)
            ? WorldsmithAnchorStructurePlacement.noise(context.randomState()) : null;
        var site = config.layout.siteInChunk(context.chunkPos(), jitter);
        if (site.isEmpty()) return Optional.empty();
        BlockPos anchor = site.get();
        if(!WorldsmithStructureLayout.accepts(config.layout,anchor,context.seed(),jitter,peers))return Optional.empty();
        var sampler=new WorldsmithTerrainProbe.CachedSampler((x,z)->WorldsmithTerrainProbe.readColumn(
            context.chunkGenerator().getBaseColumn(x,z,context.heightAccessor(),context.randomState()),
            context.heightAccessor().getMinY(),context.heightAccessor().getMaxY()));
        int firstRotation=context.random().nextInt(config.rotations.size());
        // Try each allowed orientation once, sharing sampled columns. A long
        // house can fit along a ridge even when its first orientation did not.
        for(int attempt=0;attempt<config.rotations.size();attempt++) {
            Rotation rotation=config.rotations.get((firstRotation+attempt)%config.rotations.size());
            var result=WorldsmithTerrainProbe.probe(config,anchor,rotation,context.heightAccessor().getMinY(),context.heightAccessor().getMaxY(),sampler);
            if(!result.accepted())continue;
            var plan=result.plan();
            return Optional.of(new GenerationStub(new BlockPos(anchor.getX(),plan.position().getY(),anchor.getZ()),builder ->
                builder.addPiece(new WorldsmithTemplatePiece(context.structureTemplateManager(),config,plan.position(),rotation,plan.foundations()))));
        }
        return Optional.empty();
    }

    @Override public StructureType<?> type() { return WorldsmithStructureTypes.template(); }
}
