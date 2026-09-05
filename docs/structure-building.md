# Structure building

Worldsmith structures use portable source JSON, not generated Java or a mandatory
catalog of building presets. Minecraft receives compiled NBT templates and
`worldgen/structure` + `worldgen/structure_set` resources scoped by the pack hash.
This first implementation supports rigid single-template structures assembled from
local reusable modules. It does not yet implement Jigsaw settlements, anchor-only
placements, selected floating-island layers, loot or arbitrary block-entity data.

## The source of truth

The AI contract is `core/src/main/resources/prompts/contract/structure.system.md`.
`worldsmith_begin_world` exposes it alongside terrain, biome and feature contracts.
`worldsmith_get_structure_example` returns an executable shrine example; its style
is not a world-generation requirement. Structure count may be zero.

A library entry contains `id`, `blueprint`, and `placement`. A blueprint owns its
palette, dimensions, local pivot, build operations, modules and clearance regions.
Block positions are objects `{x,y,z}`. Box endpoints are inclusive. `op` selects:
SET, FILL, SHELL, CLEAR, LINE, ROOF, REPEAT or INSTANCE. Rotations transform both
coordinates and actual Minecraft block states. No implicit air fills a template:
missing coordinates preserve the world, while CLEAR/shell interiors explicitly carve.

Geometry expands in Core with operation, nesting, coordinate and work limits.
Live block/property checks happen in the MC adapter before registry publication.
Generation samples terrain without loading new chunks, chooses one rigid base
height, and persists bounded foundation columns in the structure piece. Chunk
placement only writes inside the current chunk clip and uses private placement
settings, so chunks do not mutate a shared clip box.

## MCP workflow

1. Begin a world and read the contracts.
2. Design terrain, biomes and features; derive buildings from that world.
3. Use `worldsmith_validate_structure` and optionally `worldsmith_preview_structure`
   with a `blueprint` object. Preview writes a three-view SVG; no Minecraft boot or
   API key is involved. It is a schematic, not a textured screenshot.
4. Submit each `{id, blueprint, placement}` to `worldsmith_put_structure` with the
   session id. Re-submission replaces only that structure and invalidates an older
   saved result for the session.
5. Call `worldsmith_write_pack`, omitting `structures` to use the session's drafts,
   or supplying a complete `StructureLibrary`. An explicitly empty list chooses
   no structures rather than inheriting drafts.
6. Finish the world. Saved/validated/activation-requested is distinct from MC
   export/reload success; the creation screen performs that remaining step.

Portable storage has a `structures.json` index and one
`structures/<blueprint-id>.json` per distinct blueprint. All referenced source
files participate in the generation hash. Previews and compiled caches do not.
The exporter includes the NBT assets with the datapack copied into the world.

## Placement and limits

- Biome eligibility references this pack's local biome ids.
- Spacing and separation are in chunks. They describe random-spread candidate
  cells, not exact distances or guaranteed building counts. Different definitions
  have independent structure sets; global exclusion between separate structures
  is not yet a guarantee.
- LAND_SURFACE requires dry support; OCEAN_FLOOR requires submerged support.
- NONE requires a level floor. FILL supports the authored Y=0 footprint.
  PILLARS only supports the declared local Y=0 points. Foundation material must
  resolve to a dry full supporting block.
- Excessive slope, water mismatch, insufficient support or world-height overflow
  skips the candidate. Foundation depth is bounded at 16 blocks; building dimensions
  at 64 blocks per axis. Larger/multi-piece landmarks belong to the next layer.
- Worldsmith features avoid the 3D reserved volumes using structure references;
  an elevated structure leaves unrelated lower ground decoration eligible.
  Unmodified third-party features are outside this protection.

## Developer checks

`./gradlew.bat :core:previewStructure` writes an SVG for the bundled shrine to
`build/structure-previews/forest_shrine.svg`. Override `-PblueprintFile=...` and
`-PpreviewFile=...` to inspect another blueprint.

Core tests exercise geometry expansion, explicit air/KEEP, module cycles, budgets,
hash coverage, MCP drafts and SVG XML. MC tests exercise state validation, template
NBT, structure/set codec readback, actual generation-point probing, piece persistence,
and forward/reverse chunk placement. Final artistic judgment remains an in-game test.

Unpublished format remains 1. Packs authored against the old three-document layout
should be regenerated; no old-layout migration path is retained.
