package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomePlan;
import com.wjz.worldsmith.core.model.ClimateBands;
import com.wjz.worldsmith.core.model.ClimateBox;
import com.wjz.worldsmith.core.model.ClimatePlacement;
import com.wjz.worldsmith.core.model.ClimateSlot;
import com.wjz.worldsmith.core.model.NumericRange;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Compiles the biome plan inside a Worldsmith pack into Minecraft holders.
 *
 * <p>Stateless on purpose. The compiled biomes of a pack live in
 * {@link CompiledPack}, so more than one pack can exist at a time.
 */
public final class CompiledBiomes {
	private CompiledBiomes() {
	}

	public static List<CompiledBiome> compile(BiomePlan plan, Function<String, ResourceKey<Biome>> keyFactory) {
		return plan.getBiomes().stream().map(definition -> compile(definition, keyFactory)).toList();
	}

	private static CompiledBiome compile(BiomeDefinition definition, Function<String, ResourceKey<Biome>> keyFactory) {
		return new CompiledBiome(
			definition,
			keyFactory.apply(definition.getId()),
			BiomeArchetype.from(definition.getArchetype()),
			resolveBoxes(definition).stream().map(CompiledBiomes::climate).toList()
		);
	}

	/**
	 * A slot is expanded through the shared band table; a raw box is taken as
	 * written. The validator has already rejected declaring neither or both,
	 * and {@code allPlacements} is what folds the one-placement shorthand into
	 * the same shape as the list so only one path is ever exercised.
	 */
	private static List<ClimateBox> resolveBoxes(BiomeDefinition definition) {
		List<ClimateBox> boxes = new ArrayList<>();
		for (ClimatePlacement placement : definition.getAllPlacements()) {
			ClimateSlot slot = placement.getSlot();
			if (slot != null) {
				boxes.add(ClimateBands.INSTANCE.resolve(slot));
				continue;
			}
			ClimateBox raw = placement.getClimate();
			if (raw != null) {
				boxes.add(raw);
			}
		}
		if (boxes.isEmpty()) {
			throw new IllegalStateException("Biome '" + definition.getId() + "' declares no climate");
		}
		return List.copyOf(boxes);
	}

	public static Climate.ParameterPoint climate(ClimateBox box) {
		return new Climate.ParameterPoint(
			parameter(box.getTemperature()),
			parameter(box.getHumidity()),
			parameter(box.getContinentalness()),
			parameter(box.getErosion()),
			parameter(box.getDepth()),
			parameter(box.getWeirdness()),
			Climate.quantizeCoord(box.getOffset())
		);
	}

	private static Climate.Parameter parameter(NumericRange range) {
		return Climate.Parameter.span(range.getMin(), range.getMax());
	}
}
