# Worldsmith Java drawing SDK

Use `com.wjz.worldsmith.core.draw` to author arbitrary voxel geometry in Java.
There is no mandatory architectural style, asset catalog, roof enum or socket grammar.
Write your own Java functions, loops, equations and composition. The SDK is a canvas,
not an agent runner: this reference tool does not execute source code.

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
material(name,state), room(id,interior,floor), indoorPassage(id,interior,floor),
entrance(id,feet,facing,floor,headroom), lightFixture(id,at,state,level), support(at),
protect(region), component(id,region), instance(id,authoredComponent,transform),
container(at,state,items), bossSpawner(at,creatureId),
bossSpawner(at,creatureId,respawnTicks,requiredPlayerRange,spawnRange) and snapshot().
Item is AuthoringContext.Item(slot,item,count). Read section boss-encounters for the
spawner's exact bounds, world binding and arena-clearance responsibilities.

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
preview approximates colours and renders blocks as cubes; do not tune fine native
stairs/glass/light effects against those approximations as if they were screenshots.

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
`worldsmith_get_creature_authoring_contract` for its rig, PNG and 2..3 phases.
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
