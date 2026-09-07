package com.wjz.worldsmith.core.draw;

/** State plus deferred orientation, so native Minecraft can rotate modded blocks correctly too. */
public record DrawBlock(BlockStateRef state, GridTransform orientation) {
	public DrawBlock {
		java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(orientation);
		orientation = orientation.orientation();
	}
	public DrawBlock(BlockStateRef state) { this(state, GridTransform.IDENTITY); }
	public DrawBlock transformed(GridTransform transform) {
		return new DrawBlock(state, orientation.andThen(transform.orientation()));
	}
}
