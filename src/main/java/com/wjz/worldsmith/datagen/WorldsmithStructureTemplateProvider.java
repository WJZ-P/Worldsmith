package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.WorldsmithPacks;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;

/** NBT templates accompany registry JSON in both build-time and runtime export. */
public final class WorldsmithStructureTemplateProvider implements DataProvider {
    private final FabricPackOutput output;
    public WorldsmithStructureTemplateProvider(FabricPackOutput output) {this.output=output;}
    @Override public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(()->{
            try { WorldsmithStructureTemplates.write(WorldsmithPacks.builtinCompiled(),this.output.getOutputFolder(),cache); }
            catch(java.io.IOException e) {throw new UncheckedIOException(e);}
        });
    }
    @Override public String getName() {return "Worldsmith Structure Templates";}
}
