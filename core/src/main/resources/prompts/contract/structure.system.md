# Worldsmith Structure Builder

Design executable buildings, not Java source and not prose pretending to be geometry.
The player's prompt is the only authority for style, structure count and rarity.
Zero structures is valid. Templates below demonstrate grammar, not a mandatory architectural style.

## Ownership and transport

A `StructureLibrary` has `schemaVersion: 1` and `structures: []`.
Each entry has `id`, an inline `blueprint`, and `placement`.
Give ids simple lowercase letters, digits, underscores or dashes, at most 64 characters.
The final `worldsmith_write_pack` accepts this library in `structures`.
Alternatively, send each entry to `worldsmith_put_structure` with your `sessionId`;
omit the final `structures` argument to assemble the session's drafts. An explicit
empty library means no structures, even if drafts exist. Replacing one draft
preserves every other draft. Modifying a draft invalidates the previous saved result.

`worldsmith_get_structure_example` returns a complete, executable shrine blueprint.
`worldsmith_validate_structure` checks a blueprint without running Minecraft.
`worldsmith_preview_structure` additionally writes top/front/right and isometric
SVG views of the compiled voxels: these are schematics, not textured in-game screenshots.
Both inspection tools accept `sliceY` and return a text `floorPlan` of that local
Y layer (default 2, or the highest layer for shorter blueprints). Preview also
accepts `cutaway: true` to remove cells above `sliceY` from all SVG views. Inspect
each inhabited floor rather than only the exterior roof. Isometric rendering is
capped at 16000 exposed faces; excessive detail omits that view, not validation.
Lower the cutaway slice to inspect a dense building. A slice is not a proof that
every level is navigable. Final Minecraft block-state
resolution happens during world-creation export. Never claim the preview proves a world was played.

## Blueprint

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
    "roof_stair": {"block": "minecraft:dark_oak_stairs"}
  },
  "build": [
    {"op":"FILL", "id":"floor", "from":{"x":1,"y":0,"z":1}, "to":{"x":11,"y":0,"z":15}, "material":"stone"},
    {"op":"SHELL", "id":"hall", "from":{"x":2,"y":1,"z":3}, "to":{"x":10,"y":5,"z":13}, "material":"wall", "thickness":1},
    {"op":"CLEAR", "id":"doorway", "from":{"x":5,"y":1,"z":3}, "to":{"x":7,"y":3,"z":3}},
    {"op":"ROOF", "id":"roof", "from":{"x":1,"y":6,"z":2}, "to":{"x":11,"y":10,"z":14}, "material":"roof", "stairMaterial":"roof_stair", "style":"GABLE", "ridgeAxis":"Z"}
  ],
  "modules": {},
  "keepClear": []
}
```

Coordinates are integer objects `{x,y,z}`, not arrays. Box endpoints are inclusive.
Positive X is east, positive Z is south, Y is up. `size` is the complete declared
box including eaves and yards. Every final cell must be in `0..size-1`.
`origin` is the local pivot placed at the selected world position; Y must be zero.
The foundation datum is local Y=0, with authored solid floor cells at that level.
Empty unspecified cells mean KEEP the existing world. `CLEAR` and shell interiors
mean actual air and remove material. Never fill the whole unused bounding box with air.

Palette entries are exact `{block, properties}` descriptors. Properties are optional
string maps, for example `{"axis":"x"}` for a beam or
`{"facing":"north","half":"bottom"}` for stairs. Only use properties the block
actually supports. Geometric module rotation is applied to the real Minecraft
BlockState too, so logs, doors, stairs, fences and rails rotate consistently.
Do not place jigsaw/structure/structure_void control blocks directly; this builder
owns template control and KEEP semantics. Block-entity payloads, loot, entities,
commands and arbitrary scripts are not fields of this first structure grammar.

## Build operations

Every operation has a unique `id` within its own list. Operations execute in array
order; later writes override earlier ones. Carve openings after walls, then add frames.
Inspection returns nonblocking `diagnostics`: `OPERATION_FULLY_OVERWRITTEN` means
an operation has no surviving cells; `CLEAR_REGION_REFILLED` means a later write
filled an explicitly cleared opening or space. Review these warnings before
saving. Deliberate framing can be valid; warnings are not automatic rejection.

| op | required geometry and meaning |
| --- | --- |
| `SET` | `at`, `material`; one cell |
| `FILL` | `from`, `to`, `material`; solid cuboid |
| `SHELL` | `from`, `to`, `material`, optional `thickness` (default 1); all six outer faces, explicit air inside |
| `CLEAR` | `from`, `to`; explicit air |
| `LINE` | `from`, `to`, `material`; face-connected voxel line, including both ends |
| `ROOF` | `from`, `to`, `material`, optional `stairMaterial`, `style`, `ridgeAxis` |
| `REPEAT` | `count`, `step` coordinate, `build` operation list; repeats at 0, step, 2*step... |
| `INSTANCE` | `module`, `at`, optional `rotation`; places a named local module |

Roofs: `FLAT` is one Y layer and has no stair material. `GABLE` has a ridge along
`X` or `Z`; `HIP` slopes inward from all four sides. Rise must be positive and
at most half the short cross-span (no disconnected steep stair layers). The roof's
main `material` closes the ridge; optional `stairMaterial` forms slopes with
generated facing, bottom half and straight stair state. Build gable end walls
explicitly below the roof: the roof instruction does not invent rooms or facades.

Modules are maps from local names to operation lists and share the blueprint's
palette. Their coordinates may be negative; the expanded final result must fit.
Rotation is around local (0,0,0), then translated by `at`:
`NONE`, `CLOCKWISE_90`, `CLOCKWISE_180`, `COUNTERCLOCKWISE_90`.
Module cycles are errors. Repeat count is 1..64 with a nonempty body; total nesting
at most 8; declared operations (including unused modules) and expanded operations
are each capped at 2048; voxel visits at most 524288; final authored cells
at most 131072; each size component at most 64. Large-world infrastructure and
Jigsaw settlement assembly are future layers, not free-form fields to invent.

## Placement

```json
{
  "biomes": ["sakura_forest", "willow_hills"],
  "spacingChunks": 24,
  "separationChunks": 8,
  "clearanceBlocks": 2,
  "rotations": ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"],
  "terrainFit": {
    "surface": "LAND_SURFACE",
    "maxHeightDifference": 4,
    "foundation": {"mode":"FILL", "material":"stone", "maxDepth":6}
  }
}
```

Biome ids must exist in the same pack. Spacing and separation are CHUNKS (16
blocks), not blocks or exact distances. Require 2..4096 spacing and
1 <= separation < spacing. `clearanceBlocks` is 0..16, default 2, and expands each
building's horizontal reservation beyond its declared size. Reservations include
all allowed rotations around the pivot, so off-centre origins can reserve more
space. Overlapping candidates in the same pack compete by a stable seed-derived
rank; only nonoverlapping reservations survive, independent of chunk load order.
This is conservative: a winning reservation still excludes neighbours if it later
fails terrain or biome checks. Other packs and vanilla/third-party structures are
outside this guarantee. Never promise an exact count from a spacing value.

`LAND_SURFACE` requires dry support; `OCEAN_FLOOR` requires submerged support.
The structure stays rigid and is raised to its highest footprint sample. All
authored X/Z columns (including roof and explicitly cleared interiors) are checked,
not just pillar endpoints; an unsampled hill must not protrude through a room.
Height difference is 0..12 blocks. Each allowed rotation is tried once in a
seed-determined order using shared column samples. Failures skip the candidate
rather than sinking or stretching the building. Layer-specific sky islands and
anchors are not placement modes yet.

Foundations:
- `NONE`: no material/depth/supports; requires level floor support.
- `FILL`: uses authored non-air Y=0 footprint cells, fills short gaps beneath them.
- `PILLARS`: only supports named `{x,y:0,z}` points, up to 64 distinct points.
FILL/PILLARS require `material` from the blueprint palette and `maxDepth` 1..16.
Pillars deliberately fill only their declared points, while terrain fitting still
checks the complete authored footprint. Inspect the span between supports.
Total foundation fill is capped at 4096 blocks per structure; sites exceeding
this work budget are skipped. The foundation is persisted with the structure
start, not recomputed as neighbours load.

Worldsmith's vegetation avoids the authored geometry's three-dimensional bounding
box, its foundations, and explicit `keepClear` boxes. Put extra yard/entrance
reservations in `keepClear`, within `size`. Neither `size` nor `clearanceBlocks`
automatically clears terrain or blocks vegetation in every unused cell. Use CLEAR
where actual air is wanted; KEEP preserves unspecified terrain. Other mods'
decoration is outside this guarantee.

## Design for a result worth visiting

Build from large silhouette to details: foundation, rooms, beams, roof, openings,
trim, light. Give habitable rooms a real entrance with two blocks of headroom.
Add a porch/overhang, structural corner beams and recessed windows rather than
calling a hollow cube a finished house. Keep palette roles coherent across a
world. Use modules for repeatable columns/windows, not a mandatory building preset.
Check the preview for roof gaps, blocked doors and flat silhouettes. Geometry
validation proves bounds and references, not architectural beauty or complete navigability.
