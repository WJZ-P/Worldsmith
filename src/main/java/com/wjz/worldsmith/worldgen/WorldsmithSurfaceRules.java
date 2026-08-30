package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.Anchor;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.HydrologyIntent;
import com.wjz.worldsmith.core.model.SurfaceAltitude;
import com.wjz.worldsmith.core.model.SurfaceAnchorBand;
import com.wjz.worldsmith.core.model.SurfaceConditions;
import com.wjz.worldsmith.core.model.SurfaceDefinition;
import com.wjz.worldsmith.core.model.SurfaceHydrology;
import com.wjz.worldsmith.core.model.SurfaceLayer;
import com.wjz.worldsmith.core.model.SurfaceNoise;
import com.wjz.worldsmith.core.model.SurfaceNoiseBand;
import com.wjz.worldsmith.core.model.SurfaceRuleDefinition;
import com.wjz.worldsmith.core.model.SurfaceSlope;
import com.wjz.worldsmith.core.model.SurfaceStack;
import com.wjz.worldsmith.core.model.SurfaceTemperature;
import com.wjz.worldsmith.core.model.SurfaceWater;
import com.wjz.worldsmith.core.model.TerrainShape;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Compiles the pack's ordered surface grammar into one dimension-wide rule tree. */
public final class WorldsmithSurfaceRules {
	private WorldsmithSurfaceRules() {
	}

	public static SurfaceRules.RuleSource build(CompiledPack pack, HolderGetter<Biome> biomes, MaterialResolver resolver) {
		WorldsmithSurfaceConditionTypes.initialize();
		List<SurfaceRules.RuleSource> perBiome = new ArrayList<>();

		for (CompiledBiome biome : pack.biomes()) {
			perBiome.add(SurfaceRules.ifTrue(
				SurfaceRules.isBiome(biomes, biome.key()),
				definition(pack, biome.definition().getSurface(), resolver)
			));
		}

		// Validation requires every Worldsmith biome to own a base stack. Stone
		// only closes the total rule tree against foreign biomes.
		perBiome.add(SurfaceRules.state(Blocks.STONE.defaultBlockState()));
		return SurfaceRules.sequence(perBiome.toArray(SurfaceRules.RuleSource[]::new));
	}

	private static SurfaceRules.RuleSource definition(
		CompiledPack pack,
		SurfaceDefinition surface,
		MaterialResolver resolver
	) {
		List<SurfaceRules.RuleSource> ordered = new ArrayList<>();
		for (SurfaceRuleDefinition rule : surface.getRules()) {
			ordered.add(conditioned(pack, rule.getConditions(), stack(rule.getStack(), resolver)));
		}
		ordered.add(stack(surface.getBase(), resolver));
		return SurfaceRules.sequence(ordered.toArray(SurfaceRules.RuleSource[]::new));
	}

	/** All condition sources are nested, which gives the JSON fields AND semantics. */
	private static SurfaceRules.RuleSource conditioned(
		CompiledPack pack,
		SurfaceConditions conditions,
		SurfaceRules.RuleSource result
	) {
		List<SurfaceRules.ConditionSource> compiled = new ArrayList<>();
		SurfaceAltitude altitude = conditions.getAltitude();
		if (altitude != null) {
			if (altitude.getMin() != null) {
				compiled.add(SurfaceRules.yStartCheck(VerticalAnchor.absolute(altitude.getMin()), 0));
			}
			if (altitude.getMax() != null) {
				compiled.add(SurfaceRules.not(
					SurfaceRules.yStartCheck(VerticalAnchor.absolute(altitude.getMax() + 1), 0)
				));
			}
		}

		SurfaceSlope slope = conditions.getSlope();
		if (slope != null) {
			compiled.add(slope == SurfaceSlope.STEEP ? SurfaceRules.steep() : SurfaceRules.not(SurfaceRules.steep()));
		}
		SurfaceWater water = conditions.getWater();
		if (water != null) {
			SurfaceRules.ConditionSource aboveWater = SurfaceRules.waterBlockCheck(0, 0);
			compiled.add(water == SurfaceWater.ABOVE_WATER ? aboveWater : SurfaceRules.not(aboveWater));
		}
		SurfaceTemperature temperature = conditions.getTemperature();
		if (temperature != null) {
			compiled.add(
				temperature == SurfaceTemperature.FREEZING
					? SurfaceRules.temperature()
					: SurfaceRules.not(SurfaceRules.temperature())
			);
		}
		SurfaceNoiseBand noise = conditions.getNoise();
		if (noise != null) {
			compiled.add(SurfaceRules.noiseCondition2d(noiseKey(noise.getNoise()), noise.getMin(), noise.getMax()));
		}
		SurfaceHydrology hydrology = conditions.getHydrology();
		if (hydrology != null) {
			compiled.add(hydrology(pack, hydrology));
		}
		SurfaceAnchorBand anchorBand = conditions.getAnchor();
		if (anchorBand != null) {
			compiled.add(anchor(pack, anchorBand));
		}

		SurfaceRules.RuleSource nested = result;
		for (int index = compiled.size() - 1; index >= 0; index--) {
			nested = SurfaceRules.ifTrue(compiled.get(index), nested);
		}
		return nested;
	}

	private static SurfaceRules.ConditionSource hydrology(CompiledPack pack, SurfaceHydrology signal) {
		if (!(pack.terrain().getShape() instanceof TerrainShape.Procedural procedural)) {
			throw new IllegalStateException("Hydrology surface conditions require procedural terrain");
		}
		HydrologyIntent intent = procedural.getHydrology();
		return new WorldsmithHydrologyConditionSource(
			signal,
			procedural.getLandRatio(),
			procedural.getContinentScale(),
			procedural.getCoastRoughness(),
			intent.getRiverCoverage(),
			intent.getRiverWidth(),
			intent.getRiverMeander(),
			intent.getRiverFill(),
			intent.getLakeDensity(),
			intent.getLakeScale()
		);
	}

	/**
	 * A ring of one anchor's influence.
	 *
	 * <p>The anchor's geometry is copied into the condition because surface
	 * rules see a position and nothing else. Both sides read the same anchor
	 * from the same pack and share the lattice arithmetic, so the ring the
	 * materials are painted in cannot drift away from the ground that rose.
	 */
	private static SurfaceRules.ConditionSource anchor(CompiledPack pack, SurfaceAnchorBand band) {
		if (!(pack.terrain().getShape() instanceof TerrainShape.Procedural procedural)) {
			throw new IllegalStateException("Anchor surface conditions require procedural terrain");
		}
		Anchor anchor = procedural.getAnchors().stream()
			.filter(candidate -> candidate.getId().equals(band.getAnchor()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Unknown anchor '" + band.getAnchor() + "'"));

		AnchorPlacement placement = anchor.getPlacement();
		if (placement instanceof AnchorPlacement.Fixed fixed) {
			return new WorldsmithAnchorConditionSource(
				band.getMin(), band.getMax(), anchor.getRadius(), anchor.getFalloff(),
				false, fixed.getX(), fixed.getZ(), 0, 0.0
			);
		}
		AnchorPlacement.Scattered scattered = (AnchorPlacement.Scattered) placement;
		return new WorldsmithAnchorConditionSource(
			band.getMin(), band.getMax(), anchor.getRadius(), anchor.getFalloff(),
			true, 0, 0, scattered.getSpacing(), scattered.getJitter()
		);
	}

	/** Builds fixed-thickness layers from the exposed block downward. */
	private static SurfaceRules.RuleSource stack(SurfaceStack stack, MaterialResolver resolver) {
		List<SurfaceRules.RuleSource> layers = new ArrayList<>();
		int cumulativeDepth = 0;
		for (SurfaceLayer layer : stack.getLayers()) {
			cumulativeDepth += layer.getDepth();
			layers.add(SurfaceRules.ifTrue(
				SurfaceRules.stoneDepthCheck(cumulativeDepth - 1, false, CaveSurface.FLOOR),
				SurfaceRules.state(resolver.resolve(layer.getMaterial(), Blocks.STONE))
			));
		}
		SurfaceRules.RuleSource foundation = SurfaceRules.state(
			resolver.resolve(stack.getFoundation(), Blocks.STONE)
		);
		layers.add(foundation);
		SurfaceRules.RuleSource nearSurface = SurfaceRules.sequence(layers.toArray(SurfaceRules.RuleSource[]::new));
		return SurfaceRules.sequence(
			SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), nearSurface),
			SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR, foundation)
		);
	}

	private static ResourceKey<NormalNoise.NoiseParameters> noiseKey(SurfaceNoise noise) {
		return switch (noise) {
			case PATCH -> Noises.PATCH;
			case GRAVEL -> Noises.GRAVEL;
			case CALCITE -> Noises.CALCITE;
			case SURFACE -> Noises.SURFACE;
			case SECONDARY -> Noises.SURFACE_SECONDARY;
			case RUGGED -> Noises.BADLANDS_SURFACE;
		};
	}
}
