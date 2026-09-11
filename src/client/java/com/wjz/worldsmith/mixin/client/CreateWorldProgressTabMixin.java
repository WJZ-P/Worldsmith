package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithGenerationProgressTab;
import com.wjz.worldsmith.client.WorldsmithProgressTabAccess;
import java.util.Arrays;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Appends one native tab; vanilla retains all tabs, controls and the original publication context. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldProgressTabMixin implements WorldsmithProgressTabAccess {
    @Unique private WorldsmithGenerationProgressTab worldsmith$generationProgressTab;

    @ModifyArg(method = "init", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;"), index = 0)
    private Tab[] worldsmith$appendProgressTab(Tab[] original) {
        var previousState = worldsmith$generationProgressTab == null ? null : worldsmith$generationProgressTab.uiState();
        if (worldsmith$generationProgressTab != null) worldsmith$generationProgressTab.removed();
        worldsmith$generationProgressTab = new WorldsmithGenerationProgressTab((CreateWorldScreen)(Object)this, previousState);
        Tab[] tabs = Arrays.copyOf(original, original.length + 1);
        tabs[original.length] = worldsmith$generationProgressTab;
        return tabs;
    }

    @Override public WorldsmithGenerationProgressTab worldsmith$getGenerationProgressTab() {
        return worldsmith$generationProgressTab;
    }
}
