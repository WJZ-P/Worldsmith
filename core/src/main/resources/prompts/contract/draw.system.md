# Worldsmith Java drawing SDK

Use `com.wjz.worldsmith.core.draw` to author arbitrary voxel geometry in Java.
There is no mandatory architectural style, asset catalog, roof enum or socket grammar.
Write your own Java functions, loops, equations and composition. The SDK is a canvas,
not an agent runner: this reference tool does not execute source code.

For buildings, an architectural kit handles the block states that are easy to get
wrong one block at a time, so your own code goes to massing and character:
`Walls` (framed walls, windows, doors, arches, floors), `Roofs` (gable, hip, lean-to,
low pitch), `Stairs` (flights between storeys), `Railings` (railings, battlements),
`Furniture` and `Rooms` (single pieces, or a whole room furnished for its use). The
sections below document each; section "A whole building from the kit" puts them
together.

## Runtime boundary

- The drawing package is Java 21, using only JDK classes. No Python, NumPy,
  Minecraft classes or third-party runtime is needed to construct a drawing.
- In the Minecraft mod, `WorldsmithDrawExporter.write(drawing, path)` resolves
  real block states, applies native rotation/mirroring and writes gzip structure NBT.
  This requires the bootstrapped Minecraft classpath, not a running world.
- Core alone does not guess target-version block properties or NBT data versions.
- The Mod bundles ECJ 3.46.0 and the Java-only SDK in a separate `draw-worker`.
  Submit source with `worldsmith_build_drawing`; the current MC Java runtime launches
  hidden child processes for compilation and drawing. Generated classes never load
  into the game JVM. No installed JDK/javac/Python is required by the player.
- Source uses Java 21 grammar, preview features disabled and `-proc:none`. Entry:
  `public final class Hall implements DrawProgram { public DrawStructure generate(DrawContext context) { ... } }`.
  Context contains `seed()`, string `parameters()` and drawing `limits()` only.
- Source confirmation follows the host setting: automatic execution is on by default;
  when switched off, approval is per session and renewed after restart. MCP has no approval endpoint. This is
  resource/fault isolation, NOT a complete filesystem or network security sandbox.
- Submit 1..16 relative `.java` files, <=1 MiB total, 1..8 seeds, <=64 string parameters.
  Default queue concurrency 1; heap 1 GiB; compile timeout 30s; total drawing timeout
  120s across all seeds. Logs <=64 KiB (MCP excerpt <=8 KiB), binary result <=64 MiB.
  Failures/cancellation interrupt only that job; successful older drafts remain.
- Use `worldsmith_get_drawing_job(sessionId,jobId)` for stages and diagnostics. Reuse
  an identical `requestId` for transport retries; use a NEW id for a repair/retry.
  Results include revision, content hash, bounds, authored cells and drawing ids.
- `worldsmith_preview_drawing(sessionId,drawingId,view,sliceY)` returns actual PNG
  MCP image content; views include isometric/isometric_back/front/back/left/right/top/slice.
  Use renderMode=clay for form studies or material for approximate colours. Slice Y uses original drawing
  coordinates. This is a simplified voxel model, not a game screenshot or light-engine proof.
- `worldsmith_put_structure` references `blueprint.drawing.variants: [drawingId,...]`.
  Omit `build` and `modules`. Origin, ports, supports, rooms, indoorPassages, lighting,
  access, protection and interactions stay in structure metadata, not DrawProgram.
  All metadata uses ORIGINAL drawing coordinates; normalization transforms it with
  the geometry and named anchors. The origin Y must equal the canvas minimum Y.
- Native deployment keeps existing bounds: per logical drawing <=193x128x193,
  whole plan inside [-96,96] X/Z and height <=128, <=262144 authored cells including
  AIR, <=8192 footprint columns. The shared world sampling/write budgets are unchanged.
  Each logical SDK building becomes nonempty <=32x32x32 storage fragments. The
  logical-member limit is 16, with an independent maximum of 128 fragments per plan.
  Tiles share the building's fitted transform/datum; roads join authored entrances.
  Oversize output still supports model preview and `worldsmith_export_drawing`
  (native NBT returned as an MCP embedded resource), but deployment reports limits.
- Current format-5 world bundles contain structure-module schema 2 palette/RLE/gzip frozen drawings, source provenance and
  compiler/SDK/target-data-version metadata; hashes cover these plus structure policy.
  Loaders and chunk generation consume data ONLY. Neither published source nor
  resumed unfinished source is executed automatically. Legacy world bundles 3/4
  remain readable with their older semantics; world bundle formats 1/2 are rejected.
  Manual structure-module schema 1 remains supported; it is not a world bundle version.
- `worldsmith_list_sessions` and `worldsmith_resume_session` recover saved plans,
  source/job references and drafts; incomplete jobs become INTERRUPTED. Capacity and
  corrupt-record diagnostics are explicit, with files retained. Old drawing revisions
  require explicit `drawing.allowPreviousRevision=true`; never silently publish them.

## Coordinate and state semantics

`Vec3i(x,y,z)` = integer block centre. `Vec3d` = continuous modelling position.
X=east, Y=up, Z=south. `Box` endpoints are inclusive. Negative canvas coordinates work.

Missing voxel = KEEP the destination world. `BlockStateRef.AIR` = explicitly clear.
`shell` only draws faces; it does not silently clear its interior. `clear` authors AIR,
while `forget` removes authored data and returns the selected region to KEEP.
All shapes clip to the canvas and the painter's optional clip window.

`BlockStateRef.of("stone_brick_stairs").with("facing","north").with("half","bottom")`
stores a symbolic state. Core checks syntax; native export checks actual registry values.
Plain DrawStructure stores voxels, not arbitrary entity/block-entity NBT. Use the
StructureProgram/AuthoringContext helpers below for geometry-linked typed container
and BossSpawner metadata, or the manual blueprint.interactions route. Do not expect
an empty spawner block alone to know a world-scoped creature definition.

## Mistakes that cost whole rebuilds

**Every space a player stands in must be authored AIR.** KEEP is not an opening:
it is "whatever the destination world already has", so the structure validator
rejects it as unwalkable and the deployed build can have terrain in it. Drawing
the solid parts and leaving the rest unauthored is the single most common error.
Author AIR for all of these, not just the obvious room interior:

- terrace and plinth decks, and the tread of every flight of steps
- doorways and gate passages — skipping the wall there leaves KEEP, not a door
- the volume under an open pavilion, arch, bridge deck or market awning
- upper storeys: a walled box with no cleared interior is an unlit solid void

Clear before you place rails, columns and furniture, so those overwrite the AIR
rather than the AIR erasing them.

**Inspect real block vocabulary before committing fine detail.** Use
`worldsmith_query_block_states` for ids/properties and `worldsmith_preflight_structure`
on the frozen drawing for native checks. Export NBT when an export is needed, not
as a substitute for the cheaper registry query. Typical pitfalls include:

- wall side properties are `none` / `low` / `tall`, never `true` / `false`
- a bed's `part=head` must sit on the `facing` side of its `part=foot` block
- doors, beds and double plants are checked as pairs, emitters for lit state
- not every modern block id resolves here; `minecraft:chain` does not

**Record the canvas minimum corner.** Structure metadata uses original drawing
coordinates while exported NBT is normalised from zero. Deriving the origin from
the exported size only works if the canvas is symmetric about x=0 and z=0 — an
asymmetric `Box` shifts every declared coordinate by one and the error is silent.
Either keep canvases symmetric or carry the origin alongside the drawing id.

## Basic Java example

```java
var canvas = DrawCanvas.sized(96, 48, 80);
var stone = canvas.pen("stone_bricks");
stone.fill(Box.of(4, 0, 4, 91, 2, 75));
stone.shell(Box.of(12, 3, 12, 83, 24, 67), 2);
stone.clear(Box.of(14, 3, 14, 81, 22, 65));
stone.clear(Box.of(45, 3, 12, 50, 10, 14));
stone.bezier(List.of(new Vec3d(44, 10, 12), new Vec3d(47, 20, 12),
                    new Vec3d(51, 10, 12)), 0.8);
canvas.pen("deepslate_tiles").heightField(Box.of(10, 23, 10, 85, 39, 69),
    (x,z) -> 25 + 10 * Math.pow(1 - Math.abs(x - 47.5) / 37.5, 1.5), 1);
canvas.anchor("front_entry", new Vec3i(47, 3, 12));
DrawStructure drawing = canvas.snapshot();
// On the bootstrapped MC classpath:
WorldsmithDrawExporter.write(drawing, Path.of("build/draw/author_build.nbt"));
```

## Canvas and painter

- `new DrawCanvas(Box bounds[, DrawLimits limits])`, `DrawCanvas.sized(w,h,d)`.
- `canvas.pen(String blockId)` or `canvas.pen(Brush)` returns an immutable painter view.
- `painter.brush(brush)`, `.masked(mask)`, `.clipped(localBox)` create new views.
- `.translate(x,y,z)`, `.rotateY(quarterTurns)`, `.mirrorX()`, `.mirrorZ()` compose
  local-to-parent transforms. `pen.translate(30,0,20).rotateY(1)` rotates local geometry
  before placing it at (30,0,20). State orientation is deferred to native export.
- `set(x,y,z)`, `points(List<Vec3i>)`, `fill(Box)`, `shell(Box,thickness)`, `clear(Box)`, `forget(Box)`.
- `get(localPosition)` reads what the canvas holds there: empty for KEEP, else the stored block.
- One operation is transactional: if a callback or budget check throws, its voxel writes
  are discarded. Earlier successful operations remain. Work reservations are still charged.
  Do not make nested edits or snapshots from a brush/mask. The canvas is thread-confined.

## Fine geometry

- `line(Vec3d from,to,double radius)`, `polyline(List<Vec3d>,radius)` produce a
  six-connected raster centreline plus a capsule stroke. Radius 0 gives a one-block line.
- `bezier(List<Vec3d> controls,radius)` accepts 3 (quadratic) or 4 (cubic) controls.
- `arc(centre,radius,startRadians,endRadians,strokeRadius)` lies in the X/Z plane.
- `sphere(centre,radius)`, `ellipsoid(centre,radii)`.
- `cylinder(from,to,radius)` is flat-capped, along any 3D axis.
- `frustum(from,to,bottomRadius,topRadius)` makes tapered columns/cones.
- `torus(centre,majorRadius,tubeRadius)` has its symmetry axis along Y.
- `extrude(List<Vec2d> polygon,int minY,int maxY)` fills a concave polygon prism.
  Its X/Z boundary centres are included; outlines use the even/odd rule.
- `heightField(Box window,DoubleBinaryOperator height,int thickness)` evaluates y=f(x,z),
  rounding down. Thickness 0 fills from the window floor; positive values make a surface coat.
- `volume(Box,Predicate<Vec3i>)` is a completely custom voxel membership function.
- `field(Box,Field)` draws where an arbitrary scalar field is <=0. NaN is rejected.

`Fields` supplies sphere, box, ellipsoid, capsule, cylinder, frustum and torus fields.
Fields compose with `union`, `intersect`, `subtract`, `offset`, `shell`, `translate`,
`rotateX/Y/Z(radians)` and uniform `scale`. Example:

```java
Field vaultedWall = Fields.sphere(new Vec3d(30,15,30), 12)
    .subtract(Fields.sphere(new Vec3d(30,15,30), 10));
canvas.pen("quartz_block").field(Box.of(17,15,17,43,29,43), vaultedWall);
```

For distance fields, offset/shell use block-distance units. Ellipsoid and frustum helpers
are level sets, not exact distances: shell thickness on those is not uniformly measured
in blocks. Continuous field rotations change geometry only, not block facing; use exact
painter transforms for block-oriented components. All rasterizations are voxel approximations.

## Roofs

The roof decides whether a building reads as architecture, and stairs are easy
to get wrong one block at a time. `Roofs` lays a whole roof over the top course
of a wall `Box`, in the painter's local frame, and resolves every stair's
`shape` with Minecraft's own neighbour rule, so hip corners come out as the
outer corners the placed structure will show.

```java
var tile = Roofs.Material.of("deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tiles");
var hall = Box.of(0, 0, 0, 12, 5, 8);                     // walls; the roof starts at y=6
Roofs.gable(pen, hall, Roofs.Ridge.X, tile,
    Roofs.Options.defaults().withOverhang(1).withGableWall("white_terracotta"));
var pavilion = Box.of(20, 0, 0, 26, 4, 6);                // columns' footprint
Roofs.hip(pen, pavilion, tile, Roofs.Options.defaults().withOverhang(2).withFlaredCorners());
var porch = Box.of(1, 0, 9, 11, 2, 12);                   // posts; leans on the hall's south wall
Roofs.shed(pen, porch, Walls.Side.SOUTH, tile, Roofs.Options.defaults().withLowPitch().keepingAttic());
```

- `gable(pen, walls, ridge, material, options)`: two slopes climbing one block per
  block toward a ridge along `Ridge.X` or `Ridge.Z`, capped with slabs; the gable
  ends are filled with `withGableWall(block)` or left open.
- `hip(pen, walls, material, options)`: four slopes; a square footprint closes to
  a point (a pavilion), a long one to a short slab ridge. Stack a smaller `hip`
  over a narrower upper storey for double eaves.
- `shed(pen, walls, low, material, options)`: one slope falling toward the `low`
  `Walls.Side` - a porch, a covered walk, a wing leaning on a larger wall. It
  overhangs the low side and both ends but stops at the high wall line, so give
  a lean-to a footprint that starts one block out from the wall it leans on and
  keep its top below that building's eave.
- `Options.defaults()` is overhang 1, straight corners, no gable wall, a full
  pitch, and AIR authored under the roof inside the walls so terrain cannot fill
  the attic; `keepingAttic()` leaves that space unauthored. Overhang is 0..3.
- `withGableWall(block)` closes the walls up to the roof: gable ends, a shed's
  high side, and under an overhang of 2 or more the strip between the wall top
  and the roof, which is otherwise an open slot into the attic seen from below.
- `withLowPitch()` climbs half a block per block in slabs instead of a block in
  stairs, for any of the three roofs: the shallow roofs of dry climates, long
  halls and porches. A slab row that rests on a wall is laid as the full block
  so no half-block slit opens above the wall.
- `withFlaredCorners()` lifts each hip eave corner half a block on an inverted
  stair - the upturned eave of pavilions and halls. A gable or shed refuses it,
  and so does a low pitch: the lift is a stair.

Walls, columns, openings and the interior stay yours to draw; `Roofs` only
writes the roof and the space directly under it.

## Walls, windows and doors

A wall of one flat material reads as a box. `Walls` lays the layers vernacular
building shares - a heavier base course, posts at the corners and at a rhythm,
a beam along the top, lighter panels between - and puts depth in the openings.
The `walls` box is the footprint: its X/Z edges are the wall line, `minY` the
floor a person stands on, `maxY` the course a roof sits on.

```java
var hall = Box.of(0, 1, 0, 12, 5, 8);
var frame = Walls.Frame.of("stripped_dark_oak_log", "dark_oak_log", "white_terracotta", "cobblestone").withBay(4);
Walls.frame(pen, hall, frame);
var window = Walls.Window.of("glass_pane").withSize(1, 2).withSill(1).withTrim("spruce_stairs");
Walls.windows(pen, hall, Walls.Side.SOUTH, frame, window, 6);   // skip the bay holding x=6
Walls.door(pen, hall, Walls.Side.SOUTH, 6, "spruce_door");
Roofs.gable(pen, hall, Roofs.Ridge.X, tile, Roofs.Options.defaults().withGableWall("white_terracotta"));
```

- `frame(pen, walls, Frame)`: posts at the corners and at most `bay` (2..16)
  apart, evened out along each wall; the plinth on the floor course, the beam on
  the top course, infill between. Logs, stems and pillars get their `axis`
  along the member. The room inside is cleared to AIR unless `keepingInterior()`.
- `window(pen, walls, side, center, Window)` puts one window at `center` along the
  side; `windows(pen, walls, side, frame, Window, skip...)` centres one in every
  bay wide enough, skipping bays that contain a `skip` position. With
  `withTrim(stairs)` an inverted stair juts out as a sill below and a stair hoods
  it above, outside the wall line; `withShutters(trapdoor)` folds an open
  trapdoor flat against the wall on each side. Windows stay clear of corners and
  the top beam.
- `door(pen, walls, side, center, door)` writes both halves facing into the room
  and authors AIR in front of and behind it, so the way in is walkable.
- `arch(pen, walls, side, center, Arch)` cuts a round-headed opening from the
  floor: `Arch.of(width, spring)` is 2..15 wide with `spring` blocks of straight
  jamb, then a head of `withRise(rows)` (default half the width, a semicircle;
  fewer rows flatten it). `withDepth(n)` cuts through a wall n blocks thick, for
  gates in city walls; `withSurround(block)` dresses the jambs and one ring of
  voussoirs; `withStairs(stairs)` rounds each step of the head with an inverted
  stair. Like a door it authors AIR in front and behind. Arches every few blocks
  along one wall make an arcade; neighbours share their jamb as a pier.
- Sides are `NORTH`, `SOUTH`, `WEST`, `EAST`; positions along them are the canvas
  X (north/south walls) or Z (west/east walls) coordinate.

Give important buildings their own proportions rather than one frame
everywhere: a denser bay for a hall, a plain plastered wall for a storehouse, a
stone plinth on a slope.

## Floors, storeys and stairs

A second storey is another `walls` box whose `minY` is one above the storey
below's `maxY`; a jettied upper storey is one block wider on each side. Lay each
storey's floor, then its frame, then cut the stairs through.

```java
var lower = Box.of(0, 1, 0, 11, 5, 8), upper = Box.of(-1, 6, -1, 12, 10, 9);   // upper storey jettied out
Walls.floor(pen, lower, "cobblestone");
Walls.frame(pen, lower, frame);
Walls.door(pen, lower, Walls.Side.SOUTH, 5, "spruce_door");
Walls.floor(pen, upper, "spruce_planks");
Walls.frame(pen, upper, frame);
Vec3i landing = Stairs.flight(pen, new Vec3i(2, 1, 6), Walls.Side.NORTH, 5,
    Stairs.Flight.of("spruce_stairs").withSupport("spruce_planks"));   // lands at (2, 6, 1)
Roofs.gable(pen, upper, Roofs.Ridge.X, tile, Roofs.Options.defaults().withGableWall("calcite"));
```

- `Walls.floor(pen, walls, material)` lays the course under `minY` across the
  whole footprint, walls included, so a doorway has a threshold. It fills only
  unbuilt or AIR cells: over a lower storey it boards the room without covering
  that storey's beams, and under a jettied storey it runs out over them.
- `Stairs.flight(pen, start, up, rise, Flight)` lays `rise` steps from the lowest
  at `start`, one block up per block toward `up`, widening to the climber's
  right (1..8 wide). Every stair faces up the flight and the `headroom` (2..6,
  default 3) above each step is authored AIR, which cuts the stairwell through
  the floor above. The last step lies level with the floor it reaches, so
  climbing from feet level `y` to a floor laid at `y + rise - 1` takes `rise`
  steps. It returns the landing where a person stands at the top: use it in
  `access.destinations`. `withSupport(block)` fills under the steps; without it
  the space under the flight is left as it was.
- Leave a block of floor in front of the first step and at the landing; a flight
  that starts against a wall or lands in one is not walkable.

## Railings and battlements

An open edge reads as unfinished and, for a player, as a fall. `Railings` puts
rails round decks, balconies, bridges and terraces, and battlements on walls.

```java
var deck = Box.of(14, 0, 0, 26, 4, 8);                   // the deck's top course is its floor
Railings.around(pen, deck, Railings.Railing.of("spruce_fence").withPosts("stripped_spruce_log", 3),
    new Vec3i(20, 5, 8));                                 // leave a gap where a stair arrives
Railings.line(pen, new Vec3i(0, 1, -3), new Vec3i(9, 1, -3), Railings.Railing.of("stone_brick_wall"));
Railings.battlements(pen, keep, "stone_bricks");          // merlons on the keep's wall line
```

- `around(pen, deck, Railing, openings...)` runs one course above the deck's top
  surface round its edge, leaving out each railing-level cell in `openings`.
- `line(pen, from, to, Railing)` runs straight along X or Z at one height.
- `Railing.of(rail)` takes a fence, wall, pane, iron bars or any solid for a low
  parapet; `withPosts(block, spacing)` stands posts at the corners and at most
  `spacing` (2..16) apart. Fences, walls and panes are written with the
  connections they take to the rest of the railing; a wall shows its post at
  ends, corners and posts.
- `battlements(pen, walls, block)` puts merlons on the wall line one course above
  `walls`, at the corners and on every other block, crenels open between.

## Furnishing

An empty room reads as unfinished, and the pieces that furnish it hide their
orientation in states that mean something different on each block. `Furniture`
takes the intent and writes the state; directions are `Walls.Side` values.

```java
Furniture.table(pen, Box.of(7, 1, 3, 9, 1, 4), "spruce_slab");           // top slabs at sitting height
for (int x = 7; x <= 9; x++) {
    Furniture.seat(pen, new Vec3i(x, 1, 2), Walls.Side.SOUTH, "spruce_stairs");  // looking at the table
    Furniture.seat(pen, new Vec3i(x, 1, 5), Walls.Side.NORTH, "spruce_stairs");
}
Furniture.againstWall(pen, new Vec3i(4, 1, 1), Walls.Side.NORTH, "furnace");  // front to the room
Furniture.hangingLantern(pen, new Vec3i(8, 3, 4), 1, "lantern");             // one chain link to the ceiling
Furniture.wallTorch(pen, new Vec3i(1, 3, 4), Walls.Side.WEST, "torch");
Furniture.bed(pen, new Vec3i(9, 6, 2), Walls.Side.NORTH, "red_bed");           // pillow toward the north wall
```

- `bed(pen, foot, headToward, bed)`: the foot at `foot` and the head one block
  toward `headToward`, each half with its `part`.
- `seat(pen, at, facing, stairs)`: a stair for someone looking toward `facing`;
  its high side, the backrest, is behind them.
- `table(pen, top, slab)`: top slabs over a one-course box, open underneath.
- `againstWall(pen, at, wall, block)`: anything whose `facing` is its front -
  furnaces, smokers, barrels, lecterns, looms, shelves, chiseled bookshelves -
  standing against the `wall` side of its cell, front to the room.
- `hangingLantern(pen, at, chain, lantern)`: a hanging lantern (or soul/copper
  lantern) with `chain` iron chain links above it, up to the ceiling.
- `wallTorch(pen, at, wall, torch)`: `torch`, `soul_torch` or `copper_torch` in
  its wall form, fixed to the `wall` side of its cell.

Plain blocks need no helper: bookshelves, crafting tables, carpets as rugs,
potted plants. Keep a walkable way from the door to every bed, seat and stair,
and light each room: a room without a lantern or torch spawns monsters.

### Rooms furnished for their use

`Rooms.furnish` furnishes a whole room in one call for what it is used for, so
no building is left as empty shells:

```java
var room = Rooms.inside(walls);                      // within the wall line, floor to the course under the top
List<Rooms.Light> lights = Rooms.furnish(pen, room, Rooms.Use.TAVERN,
    Rooms.Style.of("spruce", "red").withSeed(context.seed()));   // doors and stairs already drawn are read
```

- Uses are `BEDROOM` (beds along the walls, nightstands, a rug), `KITCHEN`
  (furnace, smoker, cauldron, a table with seats), `TAVERN` (a counter, kegs,
  rows of tables with seats), `LIBRARY` (shelved walls, a lectern, a reading
  table), `FORGE` (blast furnace, anvil, smithing table, grindstone) and
  `STOREROOM` (stacked barrels and hay).
- Furniture stands in the band along the walls; the next ring in is always left
  free as a walkway, and tables, seats and rugs use only the middle beyond it.
  The room's centre cell, which `AuthoringContext.room` declares as the room's
  destination, stays bare floor.
- The canvas is read first. Doors, gates and openings in the walls are kept
  clear, so are the cells around anything already drawn on the room's floor
  (stair steps, your own pieces), and in front of a window only one-block-high
  pieces stand. Pass `keepClear` boxes for what the room cannot see: a stairwell
  cut in the floor below it, or anything you will draw after furnishing.
- `Style` picks the wood of seats and tables, the dye of beds and rugs, and a
  seed that varies where along the walls the pieces start.
- Lanterns stand on furniture or the floor, never hang from a ceiling that may
  not be there, and more are added on free wall cells until every free floor
  cell keeps block light 8 or more around the furniture, so the READABLE
  estimate passes. Each returned `Light` has the position, block and level to
  declare as a lighting source.
- In a `StructureProgram`, `a.furnishedRoom(id, interior, floor, use, style,
  keepClear...)` does it all at once: it declares and floors the room as
  `a.room` does, furnishes it, and declares every light as a fixture. Draw the
  walls and doors first, since it reads them.
- Preflight warns `BARE_ROOM` for a declared room of 12 or more floor cells
  with almost nothing standing on it. It never blocks publication: a hall, an
  arena or a cleared vault may stay bare on purpose.
- Furnish after the walls, floors, doors and stairs, and after
  `AuthoringContext.room`, which authors AIR through the room. Add your own
  pieces afterwards for a room that should feel particular: a shrine's altar, a
  captain's map table, the trophy over a hunter's hearth.

## A whole building from the kit

`com.wjz.worldsmith.core.draw.examples.Cottage` puts the pieces above together:
a framed ground floor, a jettied upper storey reached by a flight of stairs, a
gable roof, a lean-to porch and both rooms furnished. It passes the structure
access check from the porch to both floors. Treat it as grammar, not a style:
change every proportion, material and use to fit the world.

```java
import com.wjz.worldsmith.core.draw.*;

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
```

Declare the structure metadata alongside it as for any drawing: the porch step
`(0, 1, 9)` as the entrance, the room centres and the landing as destinations,
and, through a `StructureProgram`, each light `Rooms.furnish` returns.

## Brushes and masks

`Brush` is `(localPosition, previousDrawBlock) -> BlockStateRef`; null output skips.
Previous block null means KEEP. Callbacks see the canvas before the whole operation.
Material returned by a brush is oriented in the painter's local frame.

- `Brush.solid(state/id)`, `Brush.air()`.
- `Brushes.weighted(seed,patchScale,List<Brushes.Weighted>)` chooses coherent material patches.
- `Brushes.probability(brush,p,seed,scale)` leaves unselected cells unchanged.
- `Brushes.checker(a,b,scale)` makes an X/Z checkerboard; `layers(firstY,height,states)` cycles Y layers.
- All built-in random brushes key off local coordinates and an explicit seed, not call order.
- `Mask.all()`, `keepOnly()`, `airOnly()`, `solidOnly()`, `matching(blockId)`, `within(Box)`.
  Compose with `.and`, `.or`, `.not`, or write a Java lambda. `solidOnly` means authored
  non-air, not a native collision-shape or load-bearing guarantee.

## Copying, grouping, export

`canvas.snapshot()` freezes a drawing. `drawing.crop(Box)` extracts part of it.
`pen.paste(drawing, Vec3i at)` puts its minimum corner at `at` and includes explicit AIR.
`pen.paste(drawing, GridTransform transform, boolean includeAir)` transforms source
coordinates as-is. Paste ignores the pen's material brush but respects its mask and clip.
Anchor names are not automatically pasted; explicitly transform/rename them as needed.

`drawing.tiles(w,h,d)` returns nonempty storage tiles. Each has an offset relative to
the original minimum corner; native export normalizes each tile to its own minimum.
This is storage splitting, not a joint system or per-tile terrain fitting policy.
Named anchors remain model metadata; vanilla structure NBT has no matching anchor semantics.

The SDK has no 64-block architectural axis limit. Default budgets: 2,000,000 authored
cells including AIR, 32,000,000 raster work units, 200,000 path samples. Callers may
explicitly choose `DrawLimits`. A tiny brush inside a huge custom scan still incurs
scan cost. These are SDK resource budgets, not permission to exceed native worldgen bounds.

Native `.nbt` is a building template, not a whole world/save or a distribution rule.
Use the target MC version's data pack `data/<namespace>/structure/<name>.nbt` layout
or a structure-loading workflow. Use the explicit drawing-id metadata route above to deploy; the SDK itself never registers world content.

Reference implementation: `com.wjz.worldsmith.core.draw.examples.DrawGallery`.
It is a demonstrator only; every player/world may author entirely different geometry.

## Complete MCP entry point

Submit the following as sources["CourtyardProgram.java"], entryClass="CourtyardProgram".
parameters.kind can demonstrate different footprints; author new designs for real prompts.
The example is an open pavilion, hence no enclosed-room exception is being used.

```java
import com.wjz.worldsmith.core.draw.*;

/** Field-shape demonstration only: every world should author its own design. */
public final class CourtyardProgram implements DrawProgram {
    public DrawStructure generate(DrawContext context) {
        String kind = context.parameters().getOrDefault("kind", "grand");
        int n = switch (kind) { case "grand" -> 19; case "court" -> 8; case "wing" -> 7; default -> 4; };
        int h = switch (kind) { case "grand" -> 35; case "court" -> 10; case "wing" -> 12; default -> 8; };
        var canvas = context.canvas(Box.of(-n, 0, -n, n, h - 1, n));
        canvas.pen("stone_bricks").fill(Box.of(-n, 0, -n, n, 3, n));
        canvas.pen("air").fill(Box.of(-n, 4, -n, n, h - 1, n));
        for (int x : new int[] {-n + 2, n - 4})
            for (int z : new int[] {-n + 2, n - 4})
                canvas.pen("stone_bricks").fill(Box.of(x, 4, z, x + 2, h - 3, z + 2));
        canvas.pen("dark_oak_planks").fill(Box.of(-n, h - 2, -n, n, h - 2, n));
        canvas.anchor("north_entry", new Vec3i(0, 4, -n));
        return canvas.snapshot();
    }
}
```


## Authoring workbench

Prefer source projects and StructureProgram for new architectural work. DrawProgram
and full inline sources remain compatible. Keep geometric primitives in core.draw;
the optional com.wjz.worldsmith.authoring package manages geometry-linked semantics.
It has no world, player, biome-selection or group-policy objects.

1. Call worldsmith_put_drawing_source with sessionId, name, expectedRevision (0 for
   creation), changes:{relative.java:sourceText|null}, and targets:{target:{entryClass,
   files:[...]}}. Each target lists its complete source dependency set. Projects allow
   64 files/4 MiB; each target retains 16 files/1 MiB. Returned projectId and revision
   identify an immutable source version. Null removes a file; dangling target files fail.
2. Build with sessionId, name, requestId, sourceRef:{projectId,revision,target}, seeds
   and parameters. Do not also provide inline entryClass/sources. Parameters vary
   drawings without recompiling unchanged source. Only affected target dependencies
   invalidate compilation; different build requests still execute code.
3. Read source later with worldsmith_get_drawing_source(sessionId,projectId,revision,
   files), or use jobId to recover an older inline-source job. No filesystem access
   is needed on the AI client. Source reads never execute code.
4. Query worldsmith_query_block_states before guessing target-version ids/properties.
   ids may contain block[state=value] strings; invalid states still return the allowed
   property vocabulary. This is native registry inspection, not whole-pack export.

StructureProgram.generate(AuthoringContext) returns AuthoredStructure. The context
exposes seed(), parameters(), random(streamName), canvas(bounds), origin(position),
material(name,state), room(id,interior,floor), furnishedRoom(id,interior,floor,use,style,keepClear...), indoorPassage(id,interior,floor),
entrance(id,feet,facing,floor,headroom), lightFixture(id,at,state,level), support(at),
protect(region), keepClear(region), component(id,region), instance(id,authoredComponent,transform),
container(at,state,items), bossSpawner(at,creatureId),
bossSpawner(at,creatureId,respawnTicks,requiredPlayerRange,spawnRange), storyAnchor(at,place), storyAnchor(at,place,character) and snapshot().
Item is AuthoringContext.Item(slot,item,count). Read section boss-encounters for the
spawner's exact bounds, world binding and arena-clearance responsibilities.

protect(region) records variation/decay protection, not vegetation clearance.
keepClear(region) records an inclusive Box in semantics.keepClear and requires it
to stay inside the canvas. It only declares intent: it does not carve AIR, remove
fixtures or invent a route. Author actual empty geometry separately and exclude
floors, lights, columns and furniture from air-only yard clearances. When entrances
are declared, these volumes also become access.requiredClear and are checked by
preflight. Components transform both declarations with their geometry. Native
vegetation exclusion and terrain placement still need their own validation.

For hanging lamps, prefer hangingLightFixture(id,at,anchor), which defaults to a
level-15 hanging lantern and vertical iron_chain. Its full overload is
hangingLightFixture(id,at,anchor,lightState,chainState,level); use a real hanging
lantern/soul_lantern and a vertical axis=y chain, then native-check its emission.
The anchor must be an existing roof/beam block directly above the lamp, with a clear
shaft inside the canvas. The helper never overwrites obstructing geometry. It fills
only the intervening chain cells and rechecks lamp, chain and anchor at snapshot,
including transformed components. Author curved roofs first and derive each anchor
from its actual underside; a guessed uniform beam height creates floating chains.
intentionallyDark(reason) explicitly marks a deliberately dark logical building;
reason is printable, nonblank and <=512 characters, and stays authoring-only.
Ordinary interiors instead receive theme-appropriate distributed fixtures by default.

room and indoorPassage author AIR throughout the interior and floor below it. Their
occupied-space declaration is the floor plane of THAT storey, not furniture tops;
upper floors need their own room declarations. entrance connects a physical doorway
to the canvas boundary with an authored floor/AIR corridor and records both positions.
lightFixture places the block and its light declaration together. These helpers do not
invent fixture placement, a style or a building layout. Snapshot data and semantics are
immutable. instance transforms/prefixes geometry, rooms, entrances, supports, fixtures
and protected regions together. Named components are declared spatial regions used
for debug filtering, not independent worldgen pieces.
Cover every real floor, stair platform and usable roof void. An unused roof cavity
should be filled or opened into the room rather than left as a dark spawning shelf.
Lighting defaults do not mean placing lamps during chunk loading or rewriting old saves.

Submit an authored blueprint as {"id":"hall","authored":{"variants":["drawingId"]},
"portBindings":{"north":{"pool":"halls","required":true}}}. Do not also submit hand-
written geometry/semantic fields. Port bindings set pool/required/chance only; internal
coordinates and lighting come from the frozen result. Root placement/assembly remain
external. PILLARS consumes authored support points. Multiple variants must have the
same semantic layout; split genuinely different layouts into separate definitions.

worldsmith_preflight_structure takes sessionId plus ONE of structure, blueprint or
 drawingId. Stages geometry/semantics/native/assembly/deployment report PASSED, FAILED
or NOT_RUN with bounded spatial diagnostics. Execution SUCCEEDED is separate from
these checks. Missing assembly context is NOT_RUN, not a failed connection. Preflight
never certifies full data-pack reload or a placed world instance. Valid frozen geometry
remains previewable when rooms/ports/lighting need repair. Publishing stays strict.
Default preflight checks legal declarations/source blocks, not per-point brightness.
Optional estimateLighting:true requests a bounded advisory estimate; dark samples do
not fail publication. Explicit overlays:["lighting"] also request that estimate.

worldsmith_preview_structure supports region/frame (BuildBox), components/hideComponents,
views (up to four), renderMode:material|clay, cutaway and overlays:[ports,access,clearance,lighting,errors]. Its sliceY is
normalized blueprint Y; preview_drawing sliceY is original drawing Y. Returned frame
can be reused across edits; automatic framing stays stable for the same session/name.
Views are isometric, isometric_back, front, back, left, right, top and slice. Front is
north (-Z), back south (+Z), left west (-X), right east (+X); top looks down from +Y.
Slice displays one layer. Cutaway hides everything ABOVE sliceY and keeps chosen
views (e.g. top + isometric) so furniture, floors and low walls remain visible.
Assembly preview accepts these views/modes/frame/region/cutaway options too; its
sliceY uses original assembled coordinates, not a member's local floor index.
Overlays are x-ray debugging samples. Lighting is an estimate, not the game light engine.
Assembly failure can return separate member images; layoutPreviewAvailable=false says
explicitly that those images are not a successful assembled plan.

For aesthetic iteration follow architecture section visual-quality-loop. Preview
clay massing before adding ornament, inspect all elevations and occupied floors,
then the assembled place. Compare actual images after a specific change. A material
preview approximates colours and shapes vanilla slabs, stairs, trapdoors and carpets,
and draws thin blocks - fences, walls, panes, bars, chains, doors, lanterns,
candles, torches, flower pots - from their model boxes; panes and fences written
without side states are joined to their neighbours as the world would. Other
blocks remain cubes. This is not texture sampling, transparency, neighbor updates
or a physical-light screenshot.

Use worldsmith_put_architecture_draft(sessionId,expectedRevision,architecture,structures,
remove) to commit related plan/member changes as one revision. Repairable drafts can be
saved; write_pack/finish_world still enforce all publication constraints. A repeated
error across distinct creative revisions raises a repetition hint; read-only polls do not.

worldsmith_authoring_stats reports persisted host phases, request phase timings and
cache hits. It does not estimate model reasoning time. Compilation cache is bounded at
256 entries/512 MiB; parsed drawings at 128 MiB, with oversize entries not retained.
Only rebuildable caches are evicted. worldsmith_archive_session archives terminal jobs
and the session to release capacity, preserving sources and frozen results. Resume
restores data, not code execution. Finish/cancel active jobs before archiving.

## Measured form and theme evidence

Preview replies include `visualEvidence` from the actual selected non-air voxels,
not from component names or author descriptions. These measurements retain whole
occupied cells, independently of the shape-aware PNG. It reports `frame`,
`occupiedBounds`, `nonAirCells`, `footprintColumns`, `projections` and bounded
`review` observations. Each orthographic projection includes visible cell count,
depth layers/span, relief edges, largest planar panel region, skyline profile runs
and visible material counts. Isometric views use orthographic companion
measurements rather than pretending to measure a perspective facade.

Read the indicated view and region in the PNG before changing geometry. Relate the
observation to a current prompt/bible claim: does this exposed wall need a recess,
does the secondary wing compete with the focal mass, or is restraint intentional?
Repair a named component and compare the same view/mode/frame/filter. A higher
depth count or a smaller flat panel is not inherently better. Hidden material
variation does not count as facade variety; recolouring does not add spatial depth.

The report follows region/component/cutaway filtering; a `slice` projection uses
its selected layer. A frame sets image scale and is not a clip box. Profile and
material lists are bounded while their total counts remain explicit. Air is
excluded, but occupied cubes such as glass are not rendered physical transparency.
Measurements do not establish walkability, in-game light, structural strength or
successful placement. Use Core checks and image comparisons without starting the
game when runtime validation has been deferred.

The PNG renderer separately recognizes vanilla `_slab` states (`type` bottom/top/double)
and `_stairs` states (`facing`, `half`, and straight/inner/outer `shape`). It uses a
bounded half-block grid with partial-neighbor occlusion; unknown families and
invalid shape properties fall back to a cube. Deferred orientation follows the
current native exporter's mirror-then-rotate state semantics, including stair-corner
mirror behavior, rather than inventing an ideal reflection or updating neighbors.
Joined faces are filled without per-face antialias seams and the complete image is
downsampled; real openings remain. This improves roof/eave readability without
claiming a native model, transparent glass, collision or game lighting result.

## Boss encounters

For a real repeatable landmark Boss, use `StructureProgram` from
`com.wjz.worldsmith.authoring`, whose `generate(AuthoringContext a)` returns an
`AuthoredStructure`. After creating its canvas, either exact Java overload works:

```java
a.bossSpawner(new Vec3i(0, 2, 0), "observatory_warden");
// Or, at the same chosen position, use the configurable overload instead:
a.bossSpawner(new Vec3i(0, 2, 0), "observatory_warden", 2400, 16, 4);
```

Use ONE call per position, not both example lines. The helper writes the surviving
`minecraft:spawner` block and typed interaction together; later geometry must not
erase it. Its arguments are:

| Argument | Meaning | Default | Inclusive range |
| --- | --- | --- | --- |
| at | original drawing-space Vec3i | required | inside the canvas |
| creatureId | this world's logical hostile Boss id | required | valid creature id, not a native entity id |
| respawnTicks | fixed interval between later spawn cycles | 2400 | 200..30000 ticks |
| requiredPlayerRange | nearby-player activation distance | 16 | 8..32 blocks |
| spawnRange | native horizontal spawn-attempt range | 4 | 1..8 blocks |

The corresponding manual blueprint interaction is exactly:

```json
{"kind":"boss_spawner","at":{"x":0,"y":2,"z":0},"creatureId":"observatory_warden","respawnTicks":2400,"requiredPlayerRange":16,"spawnRange":4}
```

The last three fields are optional with the defaults above. Manual authors must
place the actual spawner block at `at`; StructureProgram authors submit the frozen
`blueprint.authored.variants` result without duplicating its semantic fields.
Component instances, normalization and storage tiling transform `at` with geometry.
The logical `creatureId` is preserved, not renamed by a component's id prefix.

This requires bundle format 5, structure library schema 2, and creature library
schema 2 containing that `category:"HOSTILE"` definition with a valid `boss` profile.
Read `worldsmith_get_content_contract(module:"creatures")` and
`worldsmith_get_creature_authoring_contract` for its rig, PNG and simple 2..3 stat phases, or schema-4 ability binding with no automatic phases.
Normal session structure assembly derives schema 2 from typed content; an explicit
inline library must declare schemaVersion 2. World scope and native host identity
come from the immutable bundle at native export. Authors supply no entity NBT,
commands, arbitrary spawn payload or script.

**Arena clearance is authored geometry, not an automatic spawner feature.** Use
`a.room`/`a.indoorPassage` or explicit AIR and solid floor for the standing/movement
area, with lighting and a real player route. Size horizontal margins and headroom
for the Boss's actual attributes.width/height throughout the spawn-attempt area,
not merely the small spawner cube or default host body. Furniture, walls, ceiling,
water, the spawner itself, and KEEP can obstruct a large body; room declarations
alone do not prove a valid Boss navigation route. Inspect floor/cutaway previews
and leave enough clear space outside the solid spawner block for spawn attempts.

Native export binds and reads back the typed spawner using the target game's codecs.
It attempts one Boss per cycle, initially after a short 20-tick delay while active,
then the configured interval. A dedicated encounter host has a local same-class cap
of one within the vanilla spawner's spawnRange neighborhood. Ordinary guards and
natural-Boss hosts do not occupy that class cap. A Boss moving outside it allows a
later spawn: this is repeatable local throttling, not world uniqueness or a one-time
quest event. It does not apply naturalSpawnChance/naturalSpacingBlocks or natural
biome/light selection; peaceful mode, loading, nearby players, world bounds and
actual initialized-body collision/obstruction still govern successful spawning.
Different spawners can have overlapping local caps; do not promise independent or
globally exclusive encounters from this setting.

Core checks typed fields, a surviving spawner block, module/creature references and
compiled-plan coverage. A positive placement/region route is required to count as
an obtainable encounter in complete-world coverage. Link structure -> creature with
`contains_encounter`, and an actual quest -> creature with `kill_objective` when
promised by the design. Read progress after write failures and repair its named path.
Native bundle export/readback, source-job success, offline Boss phase images and a
saved pack are separate receipts; none alone proves an in-world spawn or a playable
fight. `worldsmith_finish_world` reports native activation separately. A standalone
raw drawing has no world creature snapshot and does not publish this encounter.
