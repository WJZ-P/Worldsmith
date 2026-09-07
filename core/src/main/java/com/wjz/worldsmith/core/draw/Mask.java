package com.wjz.worldsmith.core.draw;

/** Composable selection; existing state is sampled before the entire drawing operation. */
@FunctionalInterface
public interface Mask {
	boolean allows(Vec3i local, DrawBlock previous);
	default Mask and(Mask other) { java.util.Objects.requireNonNull(other); return (p, b) -> allows(p, b) && other.allows(p, b); }
	default Mask or(Mask other) { java.util.Objects.requireNonNull(other); return (p, b) -> allows(p, b) || other.allows(p, b); }
	default Mask not() { return (p, b) -> !allows(p, b); }
	static Mask all() { return (p, b) -> true; }
	static Mask keepOnly() { return (p, b) -> b == null; }
	static Mask airOnly() { return (p, b) -> b != null && b.state().isAir(); }
	static Mask solidOnly() { return (p, b) -> b != null && !b.state().isAir(); }
	static Mask matching(String blockId) {
		String id = BlockStateRef.of(blockId).id(); return (p, b) -> b != null && b.state().id().equals(id);
	}
	static Mask within(Box box) { return (p, b) -> box.contains(p); }
}
