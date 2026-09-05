package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.core.HolderLookup;

/** NBT templates accompany registry JSON in both build-time and runtime export. */
public final class WorldsmithStructureTemplateProvider implements DataProvider {
    private final FabricPackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;
    public WorldsmithStructureTemplateProvider(FabricPackOutput output,CompletableFuture<HolderLookup.Provider> registries) {this.output=output;this.registries=registries;}
    @Override public CompletableFuture<?> run(CachedOutput cache) {
        return registries.thenAcceptAsync(provider->{
            try { WorldsmithStructureTemplates.write(WorldsmithPacks.builtinCompiled(),provider,this.output.getOutputFolder(),cache); }
            catch(java.io.IOException e) {throw new UncheckedIOException(e);}
        });
    }
    @Override public String getName() {return "Worldsmith Structure Templates";}
}
