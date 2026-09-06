package com.wjz.worldsmith.worldgen;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Candidate-local, exact noise-cell sampling. Never loads terrain chunks or shares mutable noise state. */
final class WorldsmithColumnSampler implements WorldsmithTerrainProbe.Sampler {
    private final NoiseGeneratorSettings settings;
    private final NoiseSettings noiseSettings;
    private final RandomState random;
    private final LevelHeightAccessor height;
    private final WorldsmithStructureSite site;
    private final int requiredHeight, width;
    private final Map<Long,WorldsmithTerrainProbe.Column[]> cells = new HashMap<>();

    static WorldsmithTerrainProbe.Sampler create(ChunkGenerator generator,RandomState random,LevelHeightAccessor height,
        WorldsmithStructureSite site,int requiredHeight) {
        if(generator instanceof NoiseBasedChunkGenerator noise) {
            var settings=noise.generatorSettings().value();
            var shape=settings.noiseSettings().clampToHeightAccessor(height);
            int width=shape.getCellWidth();
            if(shape.height()>0&&width<=16&&16%width==0)
                return new WorldsmithColumnSampler(settings,shape,random,height,site,requiredHeight);
        }
        // Flat/custom generators keep their own column implementation.
        return (x,z)->WorldsmithTerrainProbe.readColumn(generator.getBaseColumn(x,z,height,random),height.getMinY(),height.getMaxY(),site,requiredHeight);
    }

    private WorldsmithColumnSampler(NoiseGeneratorSettings settings,NoiseSettings noiseSettings,RandomState random,
        LevelHeightAccessor height,WorldsmithStructureSite site,int requiredHeight) {
        this.settings=settings;this.noiseSettings=noiseSettings;this.random=random;this.height=height;
        this.site=site;this.requiredHeight=requiredHeight;this.width=noiseSettings.getCellWidth();
    }

    @Override public WorldsmithTerrainProbe.Column sample(int x,int z) {
        int cellX=Math.floorDiv(x,width),cellZ=Math.floorDiv(z,width);
        long key=((long)cellX<<32)|(cellZ&0xffffffffL);
        var cell=cells.get(key);
        if(cell==null) {
            // Charge prefetched columns too, so sparse road/water probes cannot bypass the budget.
            if((long)(cells.size()+1)*width*width>WorldsmithTerrainProbe.MAX_COLUMNS)
                throw new WorldsmithTerrainProbe.ProbeBudgetExceeded();
            cell=readCell(cellX*width,cellZ*width);cells.put(key,cell);
        }
        return cell[Math.floorMod(x,width)*width+Math.floorMod(z,width)];
    }

    private WorldsmithTerrainProbe.Column[] readCell(int firstX,int firstZ) {
        int minY=noiseSettings.minY(),cellHeight=noiseSettings.getCellHeight();
        int firstY=Math.floorDiv(minY,cellHeight),countY=Math.floorDiv(noiseSettings.height(),cellHeight);
        BlockState[][] blocks=new BlockState[width*width][noiseSettings.height()];
        var noise=new CellNoise(random,firstX,firstZ,noiseSettings,settings);
        noise.initializeForFirstCellX();
        try {
            noise.advanceCellX(0);
            // Same native interpolation traversal as a terrain cell, but no world writes,
            // structures, surface decoration or AI calls. Aquifer setup is done once per cell.
            for(int cy=countY-1;cy>=0;cy--) {
                noise.selectCellYZ(cy,0);
                for(int dy=cellHeight-1;dy>=0;dy--) {
                    int y=(firstY+cy)*cellHeight+dy;
                    noise.updateForY(y,(double)dy/cellHeight);
                    for(int dx=0;dx<width;dx++) {
                        noise.updateForX(firstX+dx,(double)dx/width);
                        for(int dz=0;dz<width;dz++) {
                            noise.updateForZ(firstZ+dz,(double)dz/width);
                            BlockState state=noise.state();
                            blocks[dx*width+dz][cy*cellHeight+dy]=state==null?settings.defaultBlock():state;
                        }
                    }
                }
            }
        } finally {noise.stopInterpolation();}
        var result=new WorldsmithTerrainProbe.Column[width*width];
        for(int i=0;i<result.length;i++)result[i]=WorldsmithTerrainProbe.readColumn(
            new NoiseColumn(minY,blocks[i]),height.getMinY(),height.getMaxY(),site,requiredHeight);
        return result;
    }

    private static final class CellNoise extends NoiseChunk {
        CellNoise(RandomState random,int x,int z,NoiseSettings shape,NoiseGeneratorSettings settings) {
            super(1,random,x,z,shape,NoStructures.INSTANCE,settings,fluidPicker(settings),Blender.empty());
        }
        BlockState state(){return getInterpolatedState();}
    }

    private enum NoStructures implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(DensityFunction.FunctionContext context){return 0;}
        @Override public double minValue(){return 0;}
        @Override public double maxValue(){return 0;}
    }

    /** Mirrors the native column generator's global fluid picker, including its debug switch. */
    private static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        var lava=new Aquifer.FluidStatus(-54,Blocks.LAVA.defaultBlockState());
        var sea=new Aquifer.FluidStatus(settings.seaLevel(),settings.defaultFluid());
        var empty=new Aquifer.FluidStatus(DimensionType.MIN_Y*2,Blocks.AIR.defaultBlockState());
        return (x,y,z)->SharedConstants.DEBUG_DISABLE_FLUID_GENERATION?empty:y<Math.min(-54,settings.seaLevel())?lava:sea;
    }
}
