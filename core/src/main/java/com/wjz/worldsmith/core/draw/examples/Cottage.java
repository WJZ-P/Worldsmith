package com.wjz.worldsmith.core.draw.examples;

import com.wjz.worldsmith.core.draw.*;

/**
 * A whole building from the architectural kit: a framed ground floor, a jettied
 * upper storey reached by a flight of stairs, a gable roof, a lean-to porch over
 * the door and both rooms furnished for their use. A teaching example, not a
 * style: every world should choose its own proportions and materials.
 */
public final class Cottage implements DrawProgram {
	public DrawStructure generate(DrawContext context) {
		String wood = context.parameters().getOrDefault("wood", "spruce");
		var canvas = context.canvas(Box.of(-9, 0, -8, 9, 18, 10));
		var pen = canvas.pen("stone");

		// Ground storey: a stone plinth course, posts every 4 blocks, plaster between.
		var lower = Box.of(-6, 1, -4, 6, 5, 4);
		var frame = Walls.Frame.of("stripped_" + wood + "_log", wood + "_log", "calcite", "cobblestone");
		Walls.floor(pen, lower, "cobblestone");
		Walls.frame(pen, lower, frame);
		var window = Walls.Window.of("glass_pane").withTrim(wood + "_stairs").withShutters(wood + "_trapdoor");
		Walls.windows(pen, lower, Walls.Side.SOUTH, frame, window, 0);
		Walls.windows(pen, lower, Walls.Side.EAST, frame, window);
		Walls.windows(pen, lower, Walls.Side.NORTH, frame, window);
		Walls.door(pen, lower, Walls.Side.SOUTH, 0, wood + "_door");

		// Upper storey jettied one block out, boarded over the room below.
		var upper = Box.of(-7, 6, -5, 7, 10, 5);
		var upperFrame = Walls.Frame.of("stripped_" + wood + "_log", wood + "_log", "calcite", wood + "_planks").withBay(3);
		Walls.floor(pen, upper, wood + "_planks");
		Walls.frame(pen, upper, upperFrame);
		Walls.windows(pen, upper, Walls.Side.SOUTH, upperFrame, window);
		Walls.windows(pen, upper, Walls.Side.NORTH, upperFrame, window);

		// A flight up the west side cuts its own stairwell through the boards.
		var flight = Box.of(-5, 1, -3, -5, 6, 2);
		Stairs.flight(pen, new Vec3i(-5, 1, 2), Walls.Side.NORTH, 5, Stairs.Flight.of(wood + "_stairs").withSupport(wood + "_planks"));

		var tile = Roofs.Material.of("deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tiles");
		Roofs.gable(pen, upper, Roofs.Ridge.X, tile, Roofs.Options.defaults().withGableWall("calcite"));

		// A lean-to porch tucked under the jetty, on two posts, over a boarded deck.
		// The space under it is walked through, so it is authored AIR, not left to the terrain.
		pen.brush(Brush.solid(BlockStateRef.of(wood + "_planks"))).fill(Box.of(-3, 0, 5, 3, 0, 9));
		pen.clear(Box.of(-3, 1, 5, 3, 3, 9));
		for (int x : new int[]{-3, 3}) pen.brush(Brush.solid(BlockStateRef.of("stripped_" + wood + "_log"))).fill(Box.of(x, 1, 8, x, 3, 8));
		Roofs.shed(pen, Box.of(-3, 1, 6, 3, 3, 8), Walls.Side.SOUTH, tile, Roofs.Options.defaults().withLowPitch());

		// Furnish last. The door, the windows and the flight's steps are read from the
		// canvas; the stairwell lies below the upper room's floor, so name it there.
		var style = Rooms.Style.of(wood, "red").withSeed(context.seed());
		Rooms.furnish(pen, Rooms.inside(lower), Rooms.Use.KITCHEN, style);
		Rooms.furnish(pen, Rooms.inside(upper), Rooms.Use.BEDROOM, style, flight);

		canvas.anchor("front_door", new Vec3i(0, 1, 9));
		return canvas.snapshot();
	}
}
