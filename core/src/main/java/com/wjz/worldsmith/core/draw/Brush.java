package com.wjz.worldsmith.core.draw;

/** Receives local coordinates and the previous world-oriented cell (null means KEEP). Null output skips writing. */
@FunctionalInterface
public interface Brush {
	BlockStateRef sample(Vec3i local, DrawBlock previous);
	static Brush solid(BlockStateRef state) { java.util.Objects.requireNonNull(state); return (p, previous) -> state; }
	static Brush solid(String id) { return solid(BlockStateRef.parse(id)); }
	static Brush air() { return solid(BlockStateRef.AIR); }
}
