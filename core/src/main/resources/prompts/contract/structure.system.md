# Worldsmith Structure Builder

Generate portable executable JSON, not Java or prose pretending to be geometry.
The player prompt decides style, rarity and structure count. Zero structures is valid.
Examples teach grammar, not a mandatory architectural style. Schema/format remains 1.

## Workflow and documents

A `StructureLibrary` is `{ "schemaVersion": 1, "structures": [] }`.
Each definition has `id`, `blueprint`, `placement`, and optional `assembly`.
Ids are lowercase letters, digits, underscores or dashes, at most 64 characters.
`worldsmith_write_pack` accepts the entire library in `structures`. Alternatively,
submit definitions using `worldsmith_put_structure` with `sessionId`; omitting
`structures` from the final write uses those drafts. An explicit empty library
means no structures. Replacing a draft preserves the others and invalidates the
session's previous saved result.

Tools:
- `worldsmith_get_structure_example`, optional `id`: `forest_shrine` (basic),
  `wayfarer_lodge` (loft stairs, curved roof, variants, sign and loot),
  `arcane_observatory` (dome, arch, curve, banner), `connected_courtyard` (assembly).
  The courtyard returns `structure` as well as its root `blueprint`; replace its
  example biome ids with ids from your world.
- `worldsmith_validate_structure`: `blueprint`, optional `variant` and `sliceY`.
  Checks every configured variant and returns a text floor plan of the selected one.
- `worldsmith_preview_structure`: same inputs, optional `cutaway: true`.
  Writes top/front/right/isometric SVG views; cutaway removes cells above `sliceY`.
- `worldsmith_preview_assembly`: complete `structure`, optional plan `variant`.
  Returns piece offsets, rotations, graph edges, and a whole-layout SVG.

All of those checks run in Core, without booting Minecraft or resolving the entire
MC registry. Live block/property, item, block-entity and codec checks happen during
world-creation export. Never claim a schematic proves an in-game playtest.
The isometric view has a 16000 exposed-face limit; very detailed shapes retain the
orthographic views. Use a smaller piece or a lower cutaway to inspect detail.

## Blueprint basics

```json
{
  "schemaVersion": 1,
  "id": "forest_house",
  "size": {"x": 13, "y": 12, "z": 17},
  "origin": {"x": 6, "y": 0, "z": 8},
  "palette": {
    "stone": {"block": "minecraft:stone_bricks"},
    "wall": {"block": "minecraft:oak_planks"},
    "roof": {"block": "minecraft:dark_oak_planks"},
    "roof_stair": {"block": "minecraft:dark_oak_stairs"},
    "door": {"block": "minecraft:oak_door"}
  },
  "build": [
    {"op":"SHELL", "id":"hall", "from":{"x":2,"y":0,"z":3}, "to":{"x":10,"y":5,"z":13}, "material":"wall"},
    {"op":"DOOR", "id":"entry", "at":{"x":6,"y":1,"z":3}, "material":"door", "facing":"NORTH"},
    {"op":"ROOF", "id":"roof", "from":{"x":1,"y":6,"z":2}, "to":{"x":11,"y":10,"z":14}, "material":"roof", "stairMaterial":"roof_stair", "style":"GABLE", "ridgeAxis":"Z"}
  ],
  "modules": {},
  "keepClear": []
}
```

Positions are integer objects `{x,y,z}`, not arrays. X=east, Z=south, Y=up.
Box endpoints are inclusive, except polygon vertices describe grid lines.
Each blueprint is at most 64 blocks per axis; every final authored cell must lie
inside `0..size-1`. `origin` is a local horizontal pivot; its Y is zero.
A placed root needs solid authored floor cells at local Y=0.
Unspecified cells mean KEEP existing terrain. CLEAR and hollow interiors write
actual air. Only clear a yard/room intentionally, not every unused bounding cell.
Palette entries are exact `{block, properties}` descriptors, e.g.
`{"block":"minecraft:oak_log","properties":{"axis":"x"}}`.
Properties are string maps. Geometric rotations also rotate real MC block states.
Jigsaw/structure/structure_void control blocks are managed by the builder, not palette materials.

## Construction operations

Operations execute in order; later writes win. Every list has distinct operation ids.

| op | Fields and semantics |
|---|---|
| SET | `at`, `material`; one cell |
| FILL | `from`, `to`, `material`; solid cuboid |
| SHELL | `from`, `to`, `material`, `thickness` default 1; six faces plus explicit interior air |
| CLEAR | `from`, `to`; explicit air |
| LINE | `from`, `to`, `material`; six-connected voxel line |
| ELLIPSOID | `from`, `to`, `material`, `thickness` 0..8; zero=solid, positive=shell with air inside |
| CYLINDER | `from`, `to`, `material`, `topScale` 0..1 default 1, `thickness` 0..8; upright elliptical column, topScale=0 gives a cone; hollow forms retain caps |
| POLYGON | `points:[{x,z},...]`, `minY`, `maxY`, `material`; extrude a simple concave or convex polygon; 3..32 distinct nonintersecting vertices on grid lines |
| ARCH | `from`, `to`, `springY`, `material`, `thickness` 1..8, `spanAxis` X/Z; vertical legs below springY and elliptical arch above, opening is carved air |
| CURVE | 3 or 4 `points` (quadratic/cubic Bezier), `material`, `radius` 0..8; zero=single-voxel connected beam, positive=thick tube/branch |
| DOOR | `at` lower half, `material`, cardinal `facing`, optional `hinge` left/right and `open`; writes both halves and their state properties |
| STAIRCASE | `at` first step block, uphill cardinal `facing`, `steps` 1..48, `material`, `width` 1..8, `headroom` 2..6, optional `fillMaterial`; one-block rise per step, width grows to the right, clears above each step |
| ROOF | `from`, `to`, `material`, optional `stairMaterial`, `style`, `ridgeAxis`, `profile` |
| REPEAT | `count` 1..64, coordinate `step`, nonempty `build`; repeats at 0, step, 2*step... |
| INSTANCE | `module`, `at`, optional `rotation`; reuse a local operation list |
| CHOOSE | `choices:[{"module":"...","weight":1}]`, optional `at`, `rotation`; select one module for each precompiled variant |

Facing is NORTH/EAST/SOUTH/WEST. Rotation is NONE/CLOCKWISE_90/CLOCKWISE_180/
COUNTERCLOCKWISE_90, around local (0,0,0), then translated. Module coordinates can
be negative, but the transformed result must fit the blueprint.
Curves must leave room for their radius. A shape box spans at most 64 blocks.
Module cycles fail; nesting <=8; declared and expanded operations each <=2048;
voxel visits <=524288; final authored cells <=131072 per blueprint variant.

Roofs: FLAT is one Y layer without stair material; GABLE has a ridge along X/Z;
HIP slopes inward on all sides. Non-flat rise is 1..half the cross-span.
Optional `profile` is 2..16 ordered `{at,height}` knots, both values 0..1;
`at` runs from edge=0 to ridge=1, whose height must be 1. Heights interpolate
between knots and scale into the declared Y range. They may dip then rise for
curved eaves. Adjacent discrete roof bands must differ by at most one block.
Higher bands receive backing blocks inside the declared roof box, closing diagonal seams.
Gable end walls, porches, beams and interior floors remain authored geometry.
Staircase headroom is an intentional CLEAR write; build later details carefully.

Diagnostics `OPERATION_FULLY_OVERWRITTEN` and `CLEAR_REGION_REFILLED` are warnings:
inspect them, but deliberate framing can be valid. They are not pathfinding.

## Optional circulation checks

```json
"access": {
  "entrances": [{"x":6,"y":1,"z":3}],
  "destinations": [{"x":6,"y":1,"z":9}],
  "headroom": 2,
  "requiredClear": []
}
```

These are FEET positions. Every entrance and destination must connect to the first
entrance, with an authored supporting floor and clear headroom (2..4). Horizontal
moves allow at most one block of rise/fall and check transitional headroom too.
KEEP is unknown, not air. DOOR marks a traversable opening; a closed iron door
still blocks this check. Redstone, swimming, ladders, exact stair/slab collision
shapes and full MC movement physics are not simulated. Supply actual floor/stair
connections instead of inventing passability metadata.
Limits: 1..32 entrances, 1..128 destinations, <=32 required-clear boxes.
Multiple walkable ports are also checked for internal connectivity automatically,
even without `access`; explicit destinations extend this check into rooms/floors.

## Optional variants and weathering

```json
"variation": {
  "count": 4,
  "seed": 71,
  "materials": {"wall":[{"material":"oak_wall","weight":2},{"material":"pale_wall","weight":1}]},
  "decay": [{"materials":["stone"],"probability":0.15,"replacement":"mossy_stone","exposedOnly":true}],
  "protectedAreas": []
}
```

All names reference the blueprint palette. Choices select existing descriptors,
not arbitrary new blocks. `count` 1..8 is a finite precompiled catalog; the world
seed and structure start choose a catalog entry. CHOOSE and material swaps are
keyed deterministic choices; unrelated operations do not consume a shared RNG
stream. This is not an unbounded runtime Cartesian product.
Decay `replacement` omitted means air; optional `region` is an inclusive BuildBox.
Ground datum Y=0, doors, interaction blocks, ports, declared walking routes and
`protectedAreas` are protected. Explicitly protect beams or other critical supports;
this is not a structural load simulation. All variants must pass the same checks.
Placement foundation material is specified separately from authored block variation.

## Optional multi-piece assembly

A definition may add `assembly` with reusable `pieces` (map of blueprint id to
complete blueprint), `pools` (map to weighted piece references), `variants` 1..8,
`maxPieces` 1..16, `maxDepth` 0..8 and `maxRadius` 16..96 blocks.
Assembly variants must cover the root's variant count. Use the courtyard example
for an executable full document rather than inventing undocumented fields.

Blueprint ports:
```json
"ports": [{
  "id":"east_gate", "at":{"x":12,"y":1,"z":8},
  "facing":"EAST", "type":"hallway", "pool":"halls", "required":true,
  "passage":true
}]
```

Ports lie on the declared bounding face. Walkable ports need two authored
traversable cells, a floor, and a horizontal facing. Solid attachment sockets use
`passage:false`, an authored supporting block and any face including UP/DOWN;
these support stacked towers, branches and roofs without pretending to be doors.
A child port must match `type` and `passage` and face the opposite direction.
Connected port cells are adjacent, not overlapping. A pool selects child blueprint
ids; the child port consumed by the connection does not spawn another child.
`required:false` permits a dead end; `required:true` must connect in every plan.
Include terminal/cap pieces in pools. Budget-aware selection reserves room for
pending required connections, but there is no exponential backtracking search.

Pieces have independent NBT templates; the full layout can exceed 64 blocks.
The assembly is rigid, with collision-free declared piece boxes and connected
sockets, not a whole-world road planner or automatic terrain-following village.
Only the lowest assembled datum gets general foundations; upper storeys do not
fill columns through lower rooms. Solid joints do not imply walkable routes.
Limits: <=16 child blueprint definitions, <=32 pools, <=16 distinct weighted choices
per pool; <=128 vertical span; <=262144 placed cells per plan; <=8192 terrain
columns. Across the pack: <=64 distinct blueprints, <=1,000,000 compiled variant
cells and <=4,000,000 voxel visits. Layouts, selected pieces and seeds are fixed
before chunk placement and remain stable across save/load and exploration order.

## Placement: random by default, anchors optional

```json
{
  "biomes": ["sakura_forest", "willow_hills"],
  "spacingChunks": 24, "separationChunks": 8, "clearanceBlocks": 2,
  "rotations": ["NONE","CLOCKWISE_90","CLOCKWISE_180","COUNTERCLOCKWISE_90"],
  "terrainFit": {
    "surface":"LAND_SURFACE", "maxHeightDifference":4,
    "foundation":{"mode":"FILL","material":"stone","maxDepth":6}
  }
}
```

Biome ids must exist in this pack. Without `anchor`, use vanilla random spread:
spacing 2..4096 CHUNKS, 1<=separation<spacing. Never promise exact building counts.
`clearanceBlocks` 0..16 pads reservations covering every plan, rotation and allowed
search offset. Same-pack anchored candidates outrank random candidates; equal
modes use seeded ranks. This is conservative: a reservation still excludes peers
if its site later fails terrain/biome checks. Vanilla/other-pack/third-party
structures are outside this non-overlap guarantee.

Optional `anchor:{"id":"holy_peak","offsetX":0,"offsetZ":0}` references an
existing procedural terrain anchor. Fixed yields at most one start; scattered uses
the same seeded lattice centres as terrain; line uses `along` 0..1, default midpoint.
Offsets are world-axis blocks in +/-4096. Omit `along` outside line mode and omit
non-default spacing/separation in anchor mode. Biome/terrain checks still apply;
no forced success or fallback to a random site. With searchRadius=0 the fixed
pivot is exact. Anchors, structures and circulation declarations are all optional.

### Surface selection and optional earthwork

| surface | Selection |
|---|---|
| LAND_SURFACE | Highest dry solid surface, same as the previous default |
| OCEAN_FLOOR | Highest solid support below the native fluid surface |
| WATER_SURFACE | Water/air boundary; NONE floats, FILL/PILLARS can extend down to bounded support; works for exposed or underground water |
| SKY_SURFACE | Solid surface in the height window with an air gap below its supporting column |
| CAVE_FLOOR | Floor of an enclosed air interval |
| CAVE_CEILING | Suspend the declared extent below an enclosed air interval's ceiling; foundation must be NONE |

SKY/CAVE require `verticalRange:{"minY":140,"maxY":280}` and optionally `layer`
0..15 (top down). The range selects floors except in CAVE_CEILING, where it selects
ceilings. A sky surface additionally uses `minAirBelow` 1..64, default 8. This is
local-column geometry, not proof that an entire island is globally disconnected.
All columns must fit a coherent nearby surface with enough vertical clearance.
A height window must intersect the world's dimensions.

`searchRadius` 0..16 defaults to zero. It tries a bounded nearest-first grid of at
most 16 nearby pivots and all allowed rotations, sharing a candidate-local cache.
It may skip a valid but unsampled site. Every attempt still checks the biome.
Max 12288 unique noise columns per start; pathological sites skip rather than stall.

Foundations: NONE requires level support (except floating water/ceiling modes).
FILL uses authored bottom-datum floor cells; PILLARS uses <=64 distinct root local
Y=0 `supports`. FILL/PILLARS require a dry full-block palette `material` and depth
1..16. Default total foundation work <=4096 blocks.
Optional `earthwork:{"maxCut":2,"maxBlocks":4096}` requires LAND_SURFACE + FILL.
It chooses a median base, cuts at most 1..8 blocks per affected column, and fills
using the existing foundation depth limit. Combined cut/fill budget is 1..8192.
Max height difference remains 0..12. Nothing is flattened unless explicitly requested.
Cuts and fills are persisted and clipped; the root applies them before children.

Worldsmith vegetation avoids authored geometry bounds, foundations, cuts and
explicit `keepClear` boxes. Extra yards belong in keepClear within each piece.
Reservations do not automatically clear terrain; use CLEAR where actual air is wanted.
Other mods' decoration is outside this guarantee.

## Typed interaction content

Place the actual block with SET/FILL, then attach content at that local coordinate.
At most 128 entries, one per position. Interaction kind is `kind`, not `op`.

```json
"interactions": [
  {"kind":"container","at":{"x":4,"y":1,"z":7},
   "items":[{"slot":0,"item":"minecraft:bread","count":3}]},
  {"kind":"sign","at":{"x":5,"y":2,"z":3},
   "front":["Welcome","Literal text"],"back":[],"color":"black","glowing":false},
  {"kind":"banner","at":{"x":8,"y":1,"z":8},
   "patterns":[{"pattern":"minecraft:border","color":"yellow"}]}
]
```

Container content is one of: explicit `items`; `lootTable` resource reference;
or inline `loot:{"minRolls":1,"maxRolls":3,"entries":[{"item":"minecraft:emerald",
"weight":1,"minCount":1,"maxCount":2}]}`. Inline pools become real namespaced
loot-table JSON in the exported datapack. References must be supplied by vanilla
or an installed pack; this grammar does not invent external tables. Loot requires
an MC loot-capable container; explicit items use the actual capacity and stack limits.
Source limits: slots 0..53, counts 1..64; inline rolls 0..8, entries 1..32,
weights 1..10000. Empty slots should be omitted, not filled with air items.

Signs have at most four literal lines per side, <=160 characters per line;
there are no click commands or rich-text actions. Banners have <=16 namespaced
patterns with dye colors; the live registry checks their existence. Block-entity
payloads use Minecraft's codecs and are read back before export. Container loot
seeds are deterministic per piece/chunk. Entities, arbitrary NBT and executable
scripts are not fields of this grammar.

## Design quality

Start with silhouette, floor plan and circulation; then beams, roof, openings,
lighting and details. Use compound shapes and material roles instead of decorated
hollow cubes. Check every inhabited floor, every configured variant and assembly
connections. Exposed weathering is optional; protect critical beams explicitly.
A complete generated document is a design, not proof of beauty or a game playtest.
