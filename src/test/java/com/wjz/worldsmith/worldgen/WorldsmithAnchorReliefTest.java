package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.AnchorRelief;
import com.wjz.worldsmith.core.model.AnchorReliefSampler;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** JVM registry setup plus density-graph evaluation: no client, server, chunks or game loop. */
class WorldsmithAnchorReliefTest {
	@BeforeAll
	static void bootstrapRegistries() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void eightStackedLandformsEncodeWithLinearGrowthAndRoundTripWithoutCustomCodecs() {
		DensityFunction graph = DensityFunctions.yClampedGradient(-64, 320, -40.0, 130.0);
		DensityFunction footprint = DensityFunctions.yClampedGradient(-64, 320, 0.0, 1.0);
		DensityFunction texture = DensityFunctions.yClampedGradient(-64, 320, -2.0, 2.0);
		int previousSize = 0;
		int largestStep = 0;
		for (int i = 0; i < 8; i++) {
			AnchorRelief profile = i % 2 == 0
				? new AnchorRelief.Mesa(224, 0.9, 16.0)
				: new AnchorRelief.Caldera(-24, 224, 0.05, 0.15, 16.0);
			graph = WorldsmithAnchorRelief.apply(profile, footprint, footprint, graph, texture, 63);
			assertTrue(graph.minValue() > -2_000.0 && graph.maxValue() < 2_000.0,
				"smoothstep's known 0..1 blend bound was lost: " + graph.minValue() + ".." + graph.maxValue());
			var encoded = DensityFunction.CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
			int size = encoded.toString().length();
			System.out.println("anchor-relief graph: profiles=" + (i + 1) + ", bounds=" + graph.minValue()
				+ ".." + graph.maxValue() + ", serializedBytes=" + size);
			// A lerp implementation that serialized the previous height twice
			// would turn eight legal anchors into an exponentially large tree.
			int growth = size - previousSize;
			if (i < 2) largestStep = Math.max(largestStep, growth);
			else assertTrue(growth <= largestStep * 1.1, "anchor graph growth became superlinear: " + growth + " vs " + largestStep);
			assertTrue(size < 100_000, "eight landmark profiles should stay a bounded density document: " + size);
			previousSize = size;
			DensityFunction restored = DensityFunction.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
			for (int y = -64; y <= 320; y += 8) {
				var point = new DensityFunction.SinglePointContext(17, y, -31);
				assertEquals(graph.compute(point), restored.compute(point), 1.0E-9);
			}
		}
	}

	@Test
	void nativeCompositionKeepsTheDeclaredAnchorOrder() {
		DensityFunction centre = DensityFunctions.constant(1.0);
		DensityFunction base = DensityFunctions.constant(17.0); // Surface Y 80 with sea level 63.
		DensityFunction zero = DensityFunctions.zero();
		var mesa = new AnchorRelief.Mesa(112, 0.6, 0.0);
		var mound = new AnchorRelief.Offset(20.0);
		var mesaThenMound = WorldsmithAnchorRelief.apply(mound, centre, centre,
			WorldsmithAnchorRelief.apply(mesa, centre, centre, base, zero, 63), zero, 63);
		var moundThenMesa = WorldsmithAnchorRelief.apply(mesa, centre, centre,
			WorldsmithAnchorRelief.apply(mound, centre, centre, base, zero, 63), zero, 63);
		var point = new DensityFunction.SinglePointContext(0, 0, 0);
		assertEquals(132.0, mesaThenMound.compute(point) + 63, 1.0E-9);
		assertEquals(112.0, moundThenMesa.compute(point) + 63, 1.0E-9);
	}

	@Test
	void nativeDensityGraphMatchesTheOfflineSurfaceReference() {
		List<AnchorRelief> profiles = List.of(
			new AnchorRelief.Offset(90.0),
			new AnchorRelief.Offset(-55.0),
			new AnchorRelief.Mesa(112, 0.6, 3.0),
			new AnchorRelief.Mesa(100, 0.05, 0.0),
			new AnchorRelief.Caldera(72, 148, 0.25, 0.65, 2.0),
			new AnchorRelief.Caldera(-24, 224, 0.8, 0.9, 16.0)
		);
		var point = new DensityFunction.SinglePointContext(0, 0, 0);
		for (AnchorRelief relief : profiles) {
			for (double radius : new double[] {0.0, 0.05, 0.15, 0.25, 0.5, 0.6, 0.65, 0.85, 0.99, 1.0, 1.2}) {
				for (double base : new double[] {-25.0, 80.0, 200.0}) {
					for (double texture : new double[] {-2.0, 0.0, 0.5, 2.0}) {
						for (double falloff : new double[] {0.05, 1.0, 8.0}) {
							for (int seaLevel : new int[] {32, 63, 95}) {
								double footprint = Math.max(0.0, 1.0 - radius * radius);
								DensityFunction nativeProfile = WorldsmithAnchorRelief.apply(
									relief,
									DensityFunctions.constant(Math.pow(footprint, falloff)),
									DensityFunctions.constant(footprint),
									DensityFunctions.constant(base - seaLevel),
									DensityFunctions.constant(texture),
									seaLevel
								);
								double expected = AnchorReliefSampler.sample(relief, radius, base, texture, falloff);
								assertEquals(expected, nativeProfile.compute(point) + seaLevel, 1.0E-8,
									() -> relief + " at radius=" + radius + ", base=" + base + ", sea=" + seaLevel);
							}
						}
					}
				}
			}
		}
	}
}
