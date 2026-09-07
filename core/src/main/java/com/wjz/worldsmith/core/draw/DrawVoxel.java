package com.wjz.worldsmith.core.draw;

/** Authored cell. A missing cell means KEEP; an AIR state means explicitly clear. */
public record DrawVoxel(Vec3i position, DrawBlock block) {
	public DrawVoxel { java.util.Objects.requireNonNull(position); java.util.Objects.requireNonNull(block); }
}
