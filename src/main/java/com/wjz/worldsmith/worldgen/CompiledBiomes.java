package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeDefinition;
import com.wjz.worldsmith.core.model.BiomePlan;
import com.wjz.worldsmith.core.model.ClimateBands;
import com.wjz.worldsmith.core.model.ClimateBox;
import com.wjz.worldsmith.core.model.ClimateSlot;
import com.wjz.worldsmith.core.model.NumericRange;
import java.util.List;
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

	public static List<CompiledBiome> compile(BiomePlan plan) {
		return plan.getBiomes().stream().map(CompiledBiomes::compile).toList();
	}

	private static CompiledBiome compile(BiomeDefinition definition) {
		return new CompiledBiome(
			definition,
			WorldsmithBiomes.key(definition.getId()),
			BiomeArchetype.from(definition.getArchetype()),
			climate(resolveBox(definition))
		);
	}

	/**
	 * A slot is expanded through the shared band table; a raw box is taken as
	 * written. The validator has already rejected declaring neither or both.
	 */
	private static ClimateBox resolveBox(BiomeDefinition definition) {
		ClimateSlot slot = definition.getSlot();
		if (slot != null) {
			return ClimateBands.INSTANCE.resolve(slot);
		}
		ClimateBox raw = definition.getClimate();
		if (raw == null) {
			throw new IllegalStateException("Biome '" + definition.getId() + "' declares no climate");
		}
		return raw;
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
