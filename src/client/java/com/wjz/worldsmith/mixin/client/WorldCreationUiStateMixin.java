package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithWorldTypeMenu;
import java.util.List;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {
    @Unique private int worldsmith$presetRefreshDepth;
    @Inject(method={"getNormalPresetList", "getAltPresetList"},at=@At("RETURN"),cancellable=true)
    private void worldsmith$allManagedChoices(CallbackInfoReturnable<List<WorldCreationUiState.WorldTypeEntry>> ci) {
        ci.setReturnValue(WorldsmithWorldTypeMenu.choices((WorldCreationUiState)(Object)this, ci.getReturnValue()));
    }
    @Inject(method="setWorldType",at=@At("HEAD"),cancellable=true)
    private void worldsmith$deferSelectedWorld(WorldCreationUiState.WorldTypeEntry value,CallbackInfo ci) {
        if (WorldsmithWorldTypeMenu.select((WorldCreationUiState)(Object)this,value,worldsmith$presetRefreshDepth>0)) ci.cancel();
    }
    @Redirect(method="updatePresetLists",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;setWorldType(Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState$WorldTypeEntry;)V"))
    private void worldsmith$nativeRefreshSelection(WorldCreationUiState ui,WorldCreationUiState.WorldTypeEntry value) {
        ++worldsmith$presetRefreshDepth;
        try {ui.setWorldType(value);} finally {--worldsmith$presetRefreshDepth;}
    }
    @Inject(method="updatePresetLists",at=@At("RETURN"))
    private void worldsmith$restoreMenuSelection(CallbackInfo ci) {
        WorldsmithWorldTypeMenu.restoreAfterPresetRefresh((WorldCreationUiState)(Object)this);
    }
}
