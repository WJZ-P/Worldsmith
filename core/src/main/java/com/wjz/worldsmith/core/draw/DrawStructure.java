package com.wjz.worldsmith.core.draw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable drawing, suitable for copying, previewing and target-version structure export. */
public final class DrawStructure {
	private final Box bounds;
	private final List<DrawVoxel> voxels;
	private final Map<String, Vec3i> anchors;
	public DrawStructure(Box bounds, List<DrawVoxel> voxels, Map<String, Vec3i> anchors) {
		this.bounds = Objects.requireNonNull(bounds);
		var seen = new HashSet<Vec3i>();
		for (var voxel : voxels) if (!bounds.contains(voxel.position()) || !seen.add(voxel.position()))
			throw new IllegalArgumentException("Outside or duplicate cell: " + voxel.position());
		this.voxels = voxels.stream().sorted(Comparator.comparing(DrawVoxel::position)).toList();
		var names = new TreeMap<String, Vec3i>();
		anchors.forEach((name, p) -> {
			if (name == null || !name.matches("[a-zA-Z0-9_./-]{1,128}") || !bounds.contains(p))
				throw new IllegalArgumentException("Invalid anchor: " + name);
			names.put(name, p);
		});
		this.anchors = Collections.unmodifiableMap(names);
	}
	public Box bounds() { return bounds; }
	public List<DrawVoxel> voxels() { return voxels; }
	public Map<String, Vec3i> anchors() { return anchors; }
	public long nonAirCells() { return voxels.stream().filter(v -> !v.block().state().isAir()).count(); }
	public DrawStructure crop(Box region) {
		Box clipped = bounds.intersect(region).orElseThrow(() -> new IllegalArgumentException("Crop misses the drawing"));
		var names = new LinkedHashMap<String, Vec3i>();
		anchors.forEach((name, p) -> { if (clipped.contains(p)) names.put(name, p); });
		return new DrawStructure(clipped, voxels.stream().filter(v -> clipped.contains(v.position())).toList(), names);
	}
	/** Storage tiling only: it does not invent architectural modules or terrain-following joints. */
	public List<Tile> tiles(int width, int height, int depth) {
		if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException("Tile dimensions must be positive");
		var groups = new TreeMap<Vec3i, List<DrawVoxel>>();
		for (var voxel : voxels) {
			Vec3i p = voxel.position();
			var key = new Vec3i((int) (((long) p.x() - bounds.min().x()) / width),
				(int) (((long) p.y() - bounds.min().y()) / height), (int) (((long) p.z() - bounds.min().z()) / depth));
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(voxel);
		}
		List<Tile> result = new ArrayList<>();
		groups.forEach((key, cells) -> {
			Vec3i offset = new Vec3i(Math.multiplyExact(key.x(), width), Math.multiplyExact(key.y(), height), Math.multiplyExact(key.z(), depth));
			Vec3i min = bounds.min().add(offset);
			Vec3i max = new Vec3i((int) Math.min(bounds.max().x(), (long) min.x() + width - 1),
				(int) Math.min(bounds.max().y(), (long) min.y() + height - 1), (int) Math.min(bounds.max().z(), (long) min.z() + depth - 1));
			Box box = new Box(min, max); var names = new LinkedHashMap<String, Vec3i>();
			anchors.forEach((name, p) -> { if (box.contains(p)) names.put(name, p); });
			result.add(new Tile(offset, new DrawStructure(box, cells, names)));
		});
		return List.copyOf(result);
	}
	/** Offset is relative to the source drawing's minimum corner; exported NBT positions are tile-local. */
	public record Tile(Vec3i offset, DrawStructure structure) {}
}
