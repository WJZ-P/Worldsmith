package com.wjz.worldsmith.core.draw;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Sparse, thread-confined voxel canvas. Unwritten cells are KEEP, not air.
 * Each drawing operation commits atomically; a failing mask/brush/budget leaves cells unchanged.
 * Work reservations are still charged on failure. Use ordinary Java functions and loops to author shapes.
 */
public final class DrawCanvas {
	private final Box bounds;
	private final DrawLimits limits;
	private final Map<Vec3i, DrawBlock> cells = new HashMap<>();
	private final Map<String, Vec3i> anchors = new LinkedHashMap<>();
	private long visitedCells;
	private boolean editing;

	public DrawCanvas(Box bounds) { this(bounds, DrawLimits.DEFAULT); }
	public DrawCanvas(Box bounds, DrawLimits limits) {
		this.bounds = Objects.requireNonNull(bounds); this.limits = Objects.requireNonNull(limits);
	}
	public static DrawCanvas sized(int width, int height, int depth) { return new DrawCanvas(Box.sized(width, height, depth)); }
	public Box bounds() { return bounds; }
	public DrawLimits limits() { return limits; }
	public int authoredCells() { return cells.size(); }
	public long visitedCells() { return visitedCells; }
	public Optional<DrawBlock> get(Vec3i point) { return Optional.ofNullable(cells.get(point)); }
	public Painter pen(Brush brush) { return new Painter(this, brush, Mask.all(), GridTransform.IDENTITY, Optional.of(bounds)); }
	public Painter pen(String blockId) { return pen(Brush.solid(blockId)); }
	public DrawCanvas anchor(String name, Vec3i point) {
		if (editing) throw new IllegalStateException("Do not mutate the canvas from a brush or mask");
		if (name == null || !name.matches("[a-zA-Z0-9_./-]{1,128}") || !bounds.contains(point))
			throw new IllegalArgumentException("Anchors need a name and a point inside the canvas");
		anchors.put(name, point); return this;
	}
	public DrawStructure snapshot() {
		if (editing) throw new IllegalStateException("Finish the drawing operation before taking a snapshot");
		return new DrawStructure(bounds, cells.entrySet().stream().map(e -> new DrawVoxel(e.getKey(), e.getValue())).toList(), anchors);
	}

	void edit(long work, Consumer<Batch> action) {
		if (editing) throw new IllegalStateException("Reentrant canvas edits are not supported");
		if (work < 0 || work > limits.maxVisitedCells() - visitedCells)
			throw new IllegalStateException("Drawing work budget exceeded: " + limits.maxVisitedCells());
		visitedCells += work;
		editing = true;
		try {
			var batch = new Batch(); action.accept(batch);
			batch.changed.forEach((p, block) -> { if (block == null) cells.remove(p); else cells.put(p, block); });
		} finally { editing = false; }
	}

	final class Batch {
		private final Map<Vec3i, DrawBlock> changed = new LinkedHashMap<>();
		private int count = cells.size();
		void set(Vec3i p, DrawBlock block) {
			if (!bounds.contains(p)) throw new IllegalArgumentException("Cell outside canvas: " + p);
			DrawBlock previous = changed.containsKey(p) ? changed.get(p) : cells.get(p);
			int next = count + (block == null ? 0 : 1) - (previous == null ? 0 : 1);
			if (next > limits.maxAuthoredCells()) throw new IllegalStateException("Authored-cell budget exceeded: " + limits.maxAuthoredCells());
			changed.put(p, block); count = next;
		}
		void paint(Vec3i local, GridTransform transform, Brush brush, Mask mask) {
			Vec3i world = transform.apply(local);
			DrawBlock previous = cells.get(world);
			if (!mask.allows(local, previous)) return;
			BlockStateRef state = brush.sample(local, previous);
			if (state != null) set(world, new DrawBlock(state, transform.orientation()));
		}
	}
}
