package com.wjz.worldsmith.mixin;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.worldgen.WorldsmithAnchorStructureLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve vanilla locate results and consider only the optional custom placement type. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {
    @Inject(method = "findNearestMapStructure", at = @At("RETURN"), cancellable = true)
    private void worldsmith$locateAnchors(ServerLevel level, HolderSet<Structure> wanted, BlockPos origin,
        int maxRadius, boolean createReference, CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> result) {
        var nearest = WorldsmithAnchorStructureLocator.findNearest(level, wanted, origin, maxRadius, createReference, result.getReturnValue());
        if (nearest != result.getReturnValue()) result.setReturnValue(nearest);
    }
}
