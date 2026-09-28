package com.wjz.worldsmith.mixin;

import com.mojang.datafixers.util.Pair;
import com.wjz.worldsmith.worldgen.WorldsmithStructureLocator;
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

/** Worldsmith structures take the bounded locator; every other structure keeps the vanilla search. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void worldsmith$locate(ServerLevel level, HolderSet<Structure> wanted, BlockPos origin,
        int maxRadius, boolean createReference, CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> result) {
        var split = WorldsmithStructureLocator.split(level.getChunkSource().getGeneratorState(), wanted);
        if (split == null) return;
        // Re-entering with only the other structures finds no Worldsmith ones and runs vanilla.
        var vanilla = split.others().size() == 0 ? null
            : ((ChunkGenerator) (Object) this).findNearestMapStructure(level, split.others(), origin, maxRadius, createReference);
        result.setReturnValue(WorldsmithStructureLocator.findNearest(level, split.ours(), origin, maxRadius, createReference, vanilla));
    }
}
