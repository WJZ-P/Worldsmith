package com.wjz.worldsmith.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/** Fits a precompiled rigid or terrain-following plan and persists the entire placement. */
public final class WorldsmithTemplateStructure extends Structure {
    public record Settings(List<WorldsmithStructurePlan> plans,List<Rotation> rotations,WorldsmithStructureSite site,WorldsmithStructureLayout.Member layout,Optional<WorldsmithRoadSettings> roads) {
        public static final Codec<Settings> CODEC=RecordCodecBuilder.<Settings>create(i->i.group(
            WorldsmithStructurePlan.CODEC.listOf().fieldOf("plans").forGetter(Settings::plans),
            Rotation.CODEC.listOf().fieldOf("rotations").forGetter(Settings::rotations),
            WorldsmithStructureSite.CODEC.fieldOf("site").forGetter(Settings::site),
            WorldsmithStructureLayout.Member.CODEC.fieldOf("layout").forGetter(Settings::layout),
            WorldsmithRoadSettings.CODEC.optionalFieldOf("roads").forGetter(Settings::roads)
        ).apply(i,Settings::new)).validate(s->{
            if(s.plans.isEmpty()||s.plans.size()>8||s.rotations.isEmpty()||s.rotations.size()>4||s.rotations.stream().distinct().count()!=s.rotations.size())return DataResult.error(()->"Invalid plan or rotation count");
            var envelope=s.layout.envelope();int padding=s.site.searchRadius();
            if(s.roads.isPresent()) {
                var r=s.roads.get();int extent=r.radius()+padding;
                if(!List.of("LAND_SURFACE","SKY_SURFACE").contains(s.site.surface())||envelope.minX()>-extent||envelope.minZ()>-extent||envelope.maxX()<extent||envelope.maxZ()<extent)return DataResult.error(()->"Road region must fit the reservation on a supported surface");
                if(r.terrainFollowing()&&(s.site.foundation().equals("PILLARS")||s.plans.stream().anyMatch(p->p.parts().stream().anyMatch(part->part.offset().getY()!=0||part.supports().isEmpty())||p.links().stream().anyMatch(l->!l.passage()))))return DataResult.error(()->"Terrain-following plans require independently supported, unstacked buildings");
            }
            for(var plan:s.plans)for(var rotation:s.rotations)for(int x:new int[]{plan.bounds().minX(),plan.bounds().maxX()})for(int z:new int[]{plan.bounds().minZ(),plan.bounds().maxZ()}) {
                BlockPos p=new BlockPos(x,0,z).rotate(rotation);
                if(p.getX()-padding<envelope.minX()||p.getX()+padding>envelope.maxX()||p.getZ()-padding<envelope.minZ()||p.getZ()+padding>envelope.maxZ())return DataResult.error(()->"Layout reservation must contain all rotated plans and search offsets");
            }
            return DataResult.success(s);
        });
    }
    public static final MapCodec<WorldsmithTemplateStructure> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        settingsCodec(i),Settings.CODEC.fieldOf("template_settings").forGetter(s->s.templateSettings)
    ).apply(i,WorldsmithTemplateStructure::new));
    private final Settings templateSettings;
    public WorldsmithTemplateStructure(StructureSettings settings,Settings templateSettings){super(settings);this.templateSettings=templateSettings;}
    public Settings templateSettings(){return templateSettings;}

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        var config=templateSettings;
        long contentSeed=WorldsmithStructures.mixSeed(context.seed()+context.chunkPos().pack()*0x9E3779B97F4A7C15L+((long)config.layout.salt()<<32));
        var chooser=RandomSource.create(contentSeed);
        var plan=config.plans.get(chooser.nextInt(config.plans.size()));
        if(plan.parts().stream().anyMatch(p->context.structureTemplateManager().get(p.template()).isEmpty()))return Optional.empty();
        var peers=context.registryAccess().lookup(Registries.STRUCTURE)
            .map(registry->registry.stream().filter(s->s instanceof WorldsmithTemplateStructure).map(s->((WorldsmithTemplateStructure)s).templateSettings.layout).toList())
            .orElse(List.of(config.layout));
        var jitter=config.layout.anchor().orElse(null) instanceof WorldsmithStructureAnchor.Scattered || peers.stream().anyMatch(p->p.anchor().orElse(null) instanceof WorldsmithStructureAnchor.Scattered)
            ?WorldsmithAnchorStructurePlacement.noise(context.randomState()):null;
        var nominal=config.layout.siteInChunk(context.chunkPos(),jitter);
        if(nominal.isEmpty()||!config.layout.allowed(context.seed(),nominal.get())||!WorldsmithStructureLayout.accepts(config.layout,nominal.get(),context.seed(),jitter,peers))return Optional.empty();
        var height=context.heightAccessor();
        var sampler=new WorldsmithTerrainProbe.CachedSampler(WorldsmithColumnSampler.create(
            context.chunkGenerator(),context.randomState(),height,config.roads.isPresent()?null:config.site,config.roads.isPresent()?2:plan.height()));
        int first=chooser.nextInt(config.rotations.size());
        try {
            for(var anchor:WorldsmithTerrainProbe.sites(nominal.get(),config.site.searchRadius())) {
                if(config.layout.region().isPresent()&&(!config.layout.region().get().contains(context.seed(),anchor)||!config.layout.region().get().waterMatches(anchor,sampler)))continue;
                for(int attempt=0;attempt<config.rotations.size();attempt++) {
                    Rotation rotation=config.rotations.get((first+attempt)%config.rotations.size());
                    var fit=WorldsmithSettlementPlacement.fit(plan,config.site,config.roads,anchor,rotation,sampler,height.getMinY(),height.getMaxY(),p->context.validBiome().test(context.biomeSource().getNoiseBiome(QuartPos.fromBlock(p.getX()),QuartPos.fromBlock(p.getY()),QuartPos.fromBlock(p.getZ()),context.randomState().sampler())));
                    if(fit.isEmpty())continue;
                    var placed=fit.get();var locate=anchor.atY(placed.parts().getFirst().position().getY());
                    return Optional.of(new GenerationStub(locate,builder->{
                        for(int i=0;i<placed.parts().size();i++) {
                            var p=placed.parts().get(i);
                            builder.addPiece(new WorldsmithTemplatePiece(context.structureTemplateManager(),p.part(),config.site.foundationState(),p.position(),p.rotation(),p.foundations(),p.cuts(),contentSeed ^ (i*0x9E3779B97F4A7C15L)));
                        }
                        if(!placed.roads().isEmpty())builder.addPiece(new WorldsmithRoadPiece(placed.roads()));
                    }));
                }
            }
        } catch(WorldsmithTerrainProbe.ProbeBudgetExceeded limit) {
            // A pathological site is skipped, not allowed to monopolise a generation worker.
            return Optional.empty();
        }
        return Optional.empty();
    }
    @Override public StructureType<?> type(){return WorldsmithStructureTypes.template();}
}
