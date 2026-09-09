package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.content.WorldsmithCreativeContentClient;
import com.wjz.worldsmith.content.creative.WorldsmithCreativeContent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's cache key has no world-content identity. Invalidate it only when our scoped catalog changes. */
@Mixin(CreativeModeTabs.class)
public abstract class CreativeModeTabsContentMixin {
    @Shadow private static CreativeModeTab.ItemDisplayParameters CACHED_PARAMETERS;
    @Unique private static long worldsmith$catalogRevision = -1;

    @Inject(method = "tryRebuildTabContents", at = @At("HEAD"))
    private static void worldsmith$refreshWorldContent(FeatureFlagSet features, boolean permissions, HolderLookup.Provider lookup,
        CallbackInfoReturnable<Boolean> cir) {
        WorldsmithCreativeContentClient.synchronize(Minecraft.getInstance());
        long revision = WorldsmithCreativeContent.revision();
        if (revision != worldsmith$catalogRevision) {
            CACHED_PARAMETERS = null;
            worldsmith$catalogRevision = revision;
        }
    }
}
