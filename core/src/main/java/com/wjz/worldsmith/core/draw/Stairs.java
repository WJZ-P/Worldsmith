package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Straight flights of steps, between storeys inside a building or up a terrace
 * outside it.
 *
 * <p>A flight goes wrong in ways one block at a time hides: a stair facing the
 * wrong way reads as a step down, the floor above left whole is a ceiling at
 * head height, and a step without headroom cannot be climbed. A flight here
 * climbs one block per block of run, every stair faces up the flight, and the
 * space a person walks through is authored AIR, which cuts the stairwell
 * through any floor above.
 */
public final class Stairs {
	/**
	 * One flight's stairs block, its width (1..8), the AIR cleared above each step
	 * (2..6 blocks) and an optional solid filled beneath the steps down to the
	 * foot of the flight. Without a support the space under the flight is left as
	 * it was.
	 */
	public record Flight(BlockStateRef stairs, int width, int headroom, BlockStateRef support) {
		public Flight {
			Objects.requireNonNull(stairs);
			if (!stairs.id().endsWith("_stairs")) throw new IllegalArgumentException("A flight is made of a stairs block: " + stairs.id());
			if (width < 1 || width > 8) throw new IllegalArgumentException("A flight is 1..8 blocks wide");
			if (headroom < 2 || headroom > 6) throw new IllegalArgumentException("A flight clears 2..6 blocks above each step");
		}
		public static Flight of(String stairs) { return new Flight(BlockStateRef.parse(stairs), 1, 3, null); }
		public Flight withWidth(int blocks) { return new Flight(stairs, blocks, headroom, support); }
		public Flight withHeadroom(int blocks) { return new Flight(stairs, width, blocks, support); }
		public Flight withSupport(String solid) { return new Flight(stairs, width, headroom, BlockStateRef.parse(solid)); }
	}

	private Stairs() {}

	/**
	 * Lays {@code rise} steps climbing toward {@code up}, the lowest at
	 * {@code start}, widening to the climber's right as the blueprint
	 * {@code STAIRCASE} does. The last step lies level with the floor it climbs
	 * to, so it reaches a floor whose surface block is at
	 * {@code start.y + rise - 1}. Returns where a person stands on arriving: one
	 * block past the last step, at {@code start.y + rise}, in line with
	 * {@code start}.
	 */
	public static Vec3i flight(Painter pen, Vec3i start, Walls.Side up, int rise, Flight flight) {
		Objects.requireNonNull(pen); Objects.requireNonNull(start); Objects.requireNonNull(up); Objects.requireNonNull(flight);
		if (rise < 1 || rise > 64) throw new IllegalArgumentException("A flight rises 1..64 blocks");
		int wx = -up.dz, wz = up.dx; // the climber's right
		var steps = new ArrayList<Vec3i>(); var headroom = new ArrayList<Vec3i>(); var support = new ArrayList<Vec3i>();
		for (int i = 0; i < rise; i++) for (int w = 0; w < flight.width(); w++) {
			int x = start.x() + i * up.dx + w * wx, z = start.z() + i * up.dz + w * wz, y = start.y() + i;
			steps.add(new Vec3i(x, y, z));
			for (int h = 1; h <= flight.headroom(); h++) headroom.add(new Vec3i(x, y + h, z));
			for (int s = start.y(); s < y; s++) support.add(new Vec3i(x, s, z));
		}
		pen.brush(Brush.air()).points(headroom);
		if (flight.support() != null && !support.isEmpty()) pen.brush(Brush.solid(flight.support())).points(support);
		pen.brush(Brush.solid(flight.stairs().with("facing", up.name().toLowerCase(java.util.Locale.ROOT)).with("half", "bottom").with("shape", "straight"))).points(steps);
		return new Vec3i(start.x() + rise * up.dx, start.y() + rise, start.z() + rise * up.dz);
	}
}
