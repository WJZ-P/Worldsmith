package com.wjz.worldsmith.core.draw.examples;

import com.wjz.worldsmith.core.draw.*;
import java.util.List;

/** Example author program, not a style restriction or a shared world asset catalog. */
public final class DrawGallery {
	private DrawGallery() {}
	public static DrawStructure create(long seed) {
		var canvas = DrawCanvas.sized(80, 40, 64);
		var stone = BlockStateRef.of("stone_bricks");
		var moss = BlockStateRef.of("mossy_stone_bricks");
		var pale = canvas.pen("smooth_quartz");
		var weathered = canvas.pen(Brushes.weighted(seed, 3, List.of(new Brushes.Weighted(stone, 8), new Brushes.Weighted(moss, 1))));
		// Platform and an explicitly empty courtyard. Outside this authored region remains KEEP.
		weathered.fill(Box.of(2, 0, 2, 77, 1, 61));
		pale.clear(Box.of(3, 2, 3, 76, 30, 60));
		canvas.pen(Brushes.checker(BlockStateRef.of("polished_andesite"), BlockStateRef.of("smooth_stone"), 3))
			.fill(Box.of(5, 2, 5, 74, 2, 58));
		// One independently authored column copied with exact transforms.
		var column = DrawCanvas.sized(7, 18, 7);
		column.pen("smooth_quartz").cylinder(new Vec3d(3, 1, 3), new Vec3d(3, 14, 3), 1.5)
			.fill(Box.of(1, 0, 1, 5, 0, 5)).fill(Box.of(1, 15, 1, 5, 16, 5));
		var component = column.snapshot();
		for (int x = 9; x <= 65; x += 14) {
			pale.paste(component, new Vec3i(x, 3, 10)); pale.paste(component, new Vec3i(x, 3, 45));
		}
		// Freely designed pointed arch strokes; a Java function controls every profile.
		for (int x = 12; x <= 54; x += 14) for (int z : new int[]{13, 48}) {
			pale.bezier(List.of(new Vec3d(x, 17, z), new Vec3d(x + 2, 23, z), new Vec3d(x + 7, 26, z)), .8);
			pale.bezier(List.of(new Vec3d(x + 7, 26, z), new Vec3d(x + 12, 23, z), new Vec3d(x + 14, 17, z)), .8);
		}
		// SDF subtraction creates a vaulted shell with an open underside.
		Field dome = Fields.sphere(new Vec3d(40, 17, 31), 12).subtract(Fields.sphere(new Vec3d(40, 17, 31), 10.5));
		canvas.pen("oxidized_copper").field(Box.of(27, 17, 18, 53, 30, 44), dome);
		// Polygon extrusion and a torus add detail without any dedicated architectural opcode.
		pale.extrude(List.of(new Vec2d(33, 24), new Vec2d(47, 24), new Vec2d(51, 31), new Vec2d(47, 38), new Vec2d(33, 38), new Vec2d(29, 31)), 3, 4);
		canvas.pen("gold_block").torus(new Vec3d(40, 6, 31), 6, .75);
		// Curved roof over a side canopy, expressed directly as y=f(x,z).
		canvas.pen("deepslate_tiles").heightField(Box.of(5, 12, 22, 22, 25, 40),
			(x, z) -> 22 - 4 * Math.sin(Math.PI * (x - 5) / 17), 1);
		// Native block states are deferred through mirroring/rotation, not guessed by Core.
		var stair = BlockStateRef.of("stone_brick_stairs").with("facing", "south").with("half", "bottom");
		canvas.pen(Brush.solid(stair)).translate(68, 3, 31).rotateY(1).mirrorX().fill(Box.of(0, 0, 0, 4, 0, 1));
		// Conditional replacement touches stone only, preserving clear air and other details.
		canvas.pen(Brushes.probability(Brush.solid(moss), .15, seed + 1, 2)).masked(Mask.matching("stone_bricks"))
			.fill(Box.of(2, 0, 2, 77, 1, 61));
		canvas.anchor("entrance", new Vec3i(40, 3, 5));
		return canvas.snapshot();
	}
	public static void main(String[] args) {
		DrawStructure drawing = create(args.length == 0 ? 20260906L : Long.parseLong(args[0]));
		System.out.println("DrawGallery " + drawing.bounds() + "; authored=" + drawing.voxels().size()
			+ "; nonAir=" + drawing.nonAirCells() + "; tiles32=" + drawing.tiles(32, 32, 32).size());
	}
}
