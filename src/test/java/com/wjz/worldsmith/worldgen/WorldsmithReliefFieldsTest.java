package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldsmithReliefFieldsTest {
	private static final DensityFunction.FunctionContext POINT = new DensityFunction.SinglePointContext(0, 0, 0);
	@BeforeAll static void boot() { WorldsmithTestBootstrap.bootStrap(); }

	@Test void disabledFamiliesNeverLeakThroughNoiseTailsInEitherMode() {
		for (double width : new double[] {0.0, 0.12, 0.5}) {
			for (double selector : new double[] {-5.0, -1.001, -0.1, 0.1, 1.001, 5.0}) {
				assertTrue(value(selector, new ReliefDistribution(0.5, 0.5, 0.0), width, 0, 30, 10000) <= 30.0);
				assertTrue(value(selector, new ReliefDistribution(0.0, 0.5, 0.5), width, -10000, 30, 72) >= 30.0);
				assertTrue(value(selector, new ReliefDistribution(0.5, 0.0, 0.5), width, 0, 10000, 72) <= 72.0);
			}
		}
	}

	@Test void oneSelectedFamilyIsTheExactOriginalFieldWithoutSelectorsOrBlends() {
		var original = DensityFunctions.yClampedGradient(-64, 320, -10, 100);
		for (double width : new double[] {0.0, 0.12, 0.5}) {
			assertSame(original, WorldsmithReliefFields.select(DensityFunctions.constant(5), new ReliefDistribution(0, 1, 0),
				List.of(DensityFunctions.constant(-100), original, DensityFunctions.constant(1000)), width));
		}
	}

	@Test void smoothTransitionsAreContinuousConvexWhileZeroWidthRemainsSharp() {
		var weights = new ReliefDistribution(0.5, 0.5, 0.0);
		assertEquals(0.0, value(-1.0E-6, weights, 0, 0, 30, 72));
		assertEquals(30.0, value(1.0E-6, weights, 0, 0, 30, 72));
		assertTrue(Math.abs(value(-1.0E-6, weights, .12, 0, 30, 72) - value(1.0E-6, weights, .12, 0, 30, 72)) < .001);
		double previous = -1;
		for (int i = -100; i <= 100; i++) {
			double next = value(i / 100.0, weights, .12, 0, 30, 72);
			assertTrue(next >= previous && next >= 0 && next <= 30);
			previous = next;
		}
	}

	@Test void rareMiddleFamilyRetainsItsOwnCoreAndVanishingWindowsStillEncode() {
		var rare = new ReliefDistribution(.499, .001, .5);
		double left = WorldsmithNoiseSettings.reliefThreshold(.499);
		double right = WorldsmithNoiseSettings.reliefThreshold(.5);
		assertEquals(30.0, value((left + right) * .5, rare, .5, 0, 30, 72), 1.0E-9);
		var tiny = new ReliefDistribution(.5, 1.0E-14, .5);
		var graph = WorldsmithReliefFields.select(DensityFunctions.yClampedGradient(-64, 320, -2, 2), tiny,
			List.of(DensityFunctions.constant(0), DensityFunctions.constant(30), DensityFunctions.constant(72)), 1.0E-12);
		var json = DensityFunction.CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
		assertTrue(Double.isFinite(DensityFunction.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().compute(POINT)));
	}

	private static double value(double selector, ReliefDistribution relief, double width, double flats, double highlands, double peaks) {
		return WorldsmithReliefFields.select(DensityFunctions.constant(selector), relief,
			List.of(DensityFunctions.constant(flats), DensityFunctions.constant(highlands), DensityFunctions.constant(peaks)), width).compute(POINT);
	}
}
