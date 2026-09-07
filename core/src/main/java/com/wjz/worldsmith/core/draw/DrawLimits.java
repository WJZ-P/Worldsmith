package com.wjz.worldsmith.core.draw;

/** Work/memory limits, independent of architectural style and native deployment limits. */
public record DrawLimits(int maxAuthoredCells, long maxVisitedCells, int maxPathSamples) {
	public static final DrawLimits DEFAULT = new DrawLimits(2_000_000, 32_000_000L, 200_000);
	public DrawLimits {
		if (maxAuthoredCells < 1 || maxVisitedCells < 1 || maxPathSamples < 2)
			throw new IllegalArgumentException("Drawing budgets must be positive");
	}
}
