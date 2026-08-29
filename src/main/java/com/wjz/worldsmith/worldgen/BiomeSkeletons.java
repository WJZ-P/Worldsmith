package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.BiomeBehavior;
import com.wjz.worldsmith.core.model.BiomeLayoutPlan;
import com.wjz.worldsmith.core.model.BiomeSkeletonDefinition;
import com.wjz.worldsmith.core.model.ClimateBox;
import com.wjz.worldsmith.core.model.NumericRange;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.world.level.biome.Climate;

/** Compiles the biome layout JSON inside a Worldsmith pack into MC holders. */
public final class BiomeSkeletons {
	private static final List<BiomeSkeleton> ALL = compile(WorldsmithPacks.builtin().getBiomeLayout());
	private static final Map<String, BiomeSkeleton> BY_ID =
		ALL.stream().collect(Collectors.toUnmodifiableMap(BiomeSkeleton::id, Function.identity()));

	private BiomeSkeletons() {
	}

	public static List<BiomeSkeleton> all() {
		return ALL;
	}

	public static BiomeSkeleton byId(String id) {
		BiomeSkeleton skeleton = BY_ID.get(id);
		if (skeleton == null) {
			throw new IllegalArgumentException("Unknown biome skeleton '" + id + "'");
		}
		return skeleton;
	}

	private static List<BiomeSkeleton> compile(BiomeLayoutPlan layout) {
		return layout.getSkeletons().stream().map(BiomeSkeletons::compile).toList();
	}

	private static BiomeSkeleton compile(BiomeSkeletonDefinition definition) {
		BiomeBehavior behavior = definition.getBehavior();
		return new BiomeSkeleton(
			definition.getId(),
			WorldsmithBiomes.key(definition.getId()),
			BiomeArchetype.from(definition.getArchetype()),
			climate(definition.getClimate()),
			behavior.getTemperature(),
			behavior.getDownfall(),
			behavior.getHasPrecipitation()
		);
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
