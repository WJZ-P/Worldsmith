package com.wjz.worldsmith.mixin;

import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** 26.2's success return follows native item consumption; use the updated context, not the original hit. */
@Mixin(BlockItem.class)
public abstract class WorldMechanicPlacementMixin {
    @Inject(method = "place", at = @At(value = "RETURN", ordinal = 5), locals = LocalCapture.CAPTURE_FAILHARD)
    private void worldsmith$placed(BlockPlaceContext original, CallbackInfoReturnable<InteractionResult> result,
                                  BlockPlaceContext updated, BlockState placementState, BlockPos pos, Level level,
                                  Player player, ItemStack stack, BlockState placedState, SoundType sound) {
        if (player instanceof ServerPlayer serverPlayer && result.getReturnValue().consumesAction())
            WorldMechanicRuntime.afterPlayerPlacement(serverPlayer, pos);
    }
}
