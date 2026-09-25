package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.core.model.ReliefDistribution;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Bounded selection over only the authored, nonzero terrain families. */
final class WorldsmithReliefFields {
	private WorldsmithReliefFields() {}

	static DensityFunction select(DensityFunction selector, ReliefDistribution relief,
		List<DensityFunction> families, double transitionWidth) {
		double[] weights = {relief.getFlats(), relief.getHighlands(), relief.getPeaks()};
		double total = weights[0] + weights[1] + weights[2];
		List<DensityFunction> active = new ArrayList<>();
		DoubleArrayList thresholds = new DoubleArrayList();
		double cumulative = 0.0;
		for (int i = 0; i < weights.length; i++) {
			if (weights[i] <= 0.0) continue;
			if (!active.isEmpty()) thresholds.add(WorldsmithNoiseSettings.reliefThreshold(cumulative));
			active.add(families.get(i));
			cumulative += weights[i] / total;
		}
		if (active.isEmpty()) throw new IllegalArgumentException("Relief requires a positive family weight");
		if (active.size() == 1) return active.getFirst();
		if (transitionWidth == 0.0) return DensityFunctions.intervalSelect(selector, thresholds, active);
		DensityFunction result = active.getFirst();
		for (int i = 0; i < thresholds.size(); i++) {
			double threshold = thresholds.getDouble(i);
			double width = transitionWidth;
			// Keep adjacent blend windows disjoint, leaving at least 10% of a
			// rare middle family's selector interval wholly in that family.
			if (i > 0) width = Math.min(width, 0.45 * (threshold - thresholds.getDouble(i - 1)));
			if (i + 1 < thresholds.size()) width = Math.min(width, 0.45 * (thresholds.getDouble(i + 1) - threshold));
			if (width < 1.0E-6) {
				// Quantile clipping or vanishingly small positive shares may leave
				// no representable blend window. Avoid unbounded codec constants.
				result = DensityFunctions.rangeChoice(selector, -1_000_000.0, threshold, result, active.get(i + 1));
				continue;
			}
			DensityFunction t = DensityFunctions.mul(DensityFunctions.add(selector,
				DensityFunctions.constant(width - threshold)), DensityFunctions.constant(0.5 / width)).clamp(0.0, 1.0);
			DensityFunction eased = DensityFunctions.mul(t.square(), DensityFunctions.add(DensityFunctions.constant(3.0),
				DensityFunctions.mul(t, DensityFunctions.constant(-2.0)))).clamp(0.0, 1.0);
			result = DensityFunctions.lerp(eased, result, active.get(i + 1));
		}
		return result;
	}
}
