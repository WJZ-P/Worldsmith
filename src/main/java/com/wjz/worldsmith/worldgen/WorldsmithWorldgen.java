package com.wjz.worldsmith.worldgen;

/**
 * Registration boundary for Worldsmith's world-generation components.
 */
public final class WorldsmithWorldgen {
	private WorldsmithWorldgen() {
	}

	public static void initialize() {
		WorldsmithSurfaceConditionTypes.initialize();
	}
}
