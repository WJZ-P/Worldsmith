package com.wjz.worldsmith.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldCreationUiState.class)
public interface WorldCreationUiStateAccessor {
    @Accessor("worldType") void worldsmith$setWorldType(WorldCreationUiState.WorldTypeEntry entry);
}
