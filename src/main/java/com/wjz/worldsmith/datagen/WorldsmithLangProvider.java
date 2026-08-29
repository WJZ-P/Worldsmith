package com.wjz.worldsmith.datagen;

import com.wjz.worldsmith.worldgen.CompiledBiome;
import com.wjz.worldsmith.worldgen.CompiledBiomes;
import com.wjz.worldsmith.worldgen.WorldsmithWorldPresets;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/**
 * English names for the generated biomes and the world preset.
 *
 * <p>Without these the F3 screen and {@code /locate biome} show raw identifiers,
 * which makes it impossible to tell at a glance whether the right biome
 * generated.
 */
public final class WorldsmithLangProvider extends FabricLanguageProvider {
	public WorldsmithLangProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
		builder.add(WorldsmithWorldPresets.WASTELAND.identifier().toLanguageKey("generator"), "Wasteland");

		for (CompiledBiome biome : CompiledBiomes.all()) {
			builder.add(biome.key().identifier().toLanguageKey("biome"), biome.definition().getDisplayName());
		}
	}
}
