# Worldsmith custom creature contract — module schema 1

Create ground creatures whose silhouette, material and behavior belong to the
same world theme. The installed runtime supports data-driven cuboid rigs,
procedural role-based motion, passive wandering/fleeing and hostile melee.
It is not an arbitrary behavior-script executor or a GeckoLib animation-file
loader. Flight, swimming-specialized movement, mounts, projectiles, inventories,
custom drops and multipart bosses have no schema fields in this module.

## Exact library and definition fields

`CreatureLibrary` is `{"schemaVersion":1,"creatures":[...]}`. An empty library
is valid; the maximum is 128 definitions. Each definition has exactly:

- `id`: unique local lowercase identity, matching `[a-z0-9][a-z0-9_./-]{0,95}`;
  use a simple name without empty, `.` or `..` path segments.
- `displayName`: nonblank, at most 128 characters.
- `category`: `PASSIVE` or `HOSTILE`.
- `model`: the rig and texture document below (required).
- `attributes`, `behavior`, `spawn`: optional typed objects below, with defaults.
- `themeRole`: optional text at most 2048 characters describing the creature's
  ecological/narrative function; it is not executable behavior.

### Model and coordinates

`model` fields: `texture` (actual lowercase SHA-256 PNG asset ID), `textureWidth`
(default 64), `textureHeight` (default 64), and `bones` (required).
Both atlas dimensions are powers of two from 16 to 512 and must equal actual PNG
dimensions. Asset identity equals the real digest; a concept image or a guessed
digest is not a usable atlas. Generic PNG limits are 4 MiB encoded and verified
PNG chunks/CRC/decode. Pixel-grid authoring is limited to 256 on each edge;
larger permitted atlases must be uploaded as real PNG bytes.

The rig has 1..64 bones and 1..256 total cubes. Coordinates use model units
(1/16 block), X right, Y down, Z toward the back. A root pivot at Y=24 lies at
ground level. Each bone's pivot is parent-relative; cube origins are bone-local.

Each bone has:

- `id`: `[a-zA-Z][a-zA-Z0-9_]{0,47}`, unique in the rig.
- `parent`: optional existing bone ID. Hierarchies are acyclic, depth at most 16.
- `pivot`: `{x,y,z}`, default all zero, each finite within -128..128.
- `rotation`: `{x,y,z}` in degrees, default zero, each finite within -360..360.
- `role`: `NONE` (default), `HEAD`, `LEG_LEFT`, `LEG_RIGHT`, `ARM_LEFT`,
  `ARM_RIGHT`, or `TAIL`. Roles select installed procedural motion.
- `gaitPhase`: finite degrees -360..360, default 0; phase offset 180 on hind
  legs can distinguish diagonal quadruped gait from matching front/hind motion.
- `cubes`: list of cube objects, default empty.

Each cube has `origin: {x,y,z}`, `size: {x,y,z}`, `uv: {u,v}` (default zero), and
`mirror` (default false). Origins are finite within -128..128; sizes are integer
model units from 1 to 64. UV uses vanilla box unfolding: atlas footprint width
`2*(size.x+size.z)`, height `size.y+size.z`. Nonnegative integer u/v plus that
footprint must stay inside the declared atlas. Accumulated hierarchy/cube extent
must fit within 256 model units of the model origin, including parent pivots.

Plan the rig and UV islands first, then paint their intended atlas areas. A nice
standalone picture does not establish coherent UVs. Match collision dimensions
and foot placement to the visible model; inspect motion as well as a still pose.

### Attributes, defaults and inclusive bounds

| Field | Default | Range |
| --- | --- | --- |
| health | 20 | 1..2048 |
| speed | 0.25 | 0.01..1 |
| followRange | 24 | 4..64 |
| attackDamage | 3 | 0..100 |
| knockbackResistance | 0 | 0..1 |
| width | 0.8 | 0.2..4 blocks |
| height | 1.4 | 0.2..6 blocks |

All numbers are finite. Attribute values are not animation curve controls.

`behavior` fields:

- `passiveMode`: `WANDER` (default) or `FLEE_PLAYERS`.
- `territoryRadius`: integer 8..128 blocks, default 32.
- `attackReach`: finite 0.5..5 blocks, default 2.
- `windupTicks`: integer 1..100, default 12.
- `recoveryTicks`: integer 4..200, default 20.

The server owns target validity, melee windup/strike/recovery and damage. A
client's animation is not evidence that an attack landed. Do not invent custom
state-machine, event, script, spell or animation-JSON fields.

`spawn` fields:

- `biomes`: up to 128 unique existing logical biome IDs; default empty disables
  natural spawning. These are local biome IDs, not native registry addresses.
- `weight`: integer 1..1000, default 10.
- `minGroup`/`maxGroup`: integers satisfying 1 <= min <= max <= 8; defaults 1/3.
- `minLight`/`maxLight`: integers satisfying 0 <= min <= max <= 15; defaults 0/15.

## Shared MCP workflow

1. Read `worldsmith_get_content_contract(module: "creatures")`, the theme, and
   `worldsmith_get_content_draft(sessionId)` for modules/assets/shared revision.
2. Design body proportions, hierarchy, joint roles, atlas and behavior together.
   Author a palette-index atlas using
   `worldsmith_create_pixel_texture(sessionId, expectedRevision, palette, rows)`
   or upload actual PNG bytes with
   `worldsmith_put_texture_asset(sessionId, expectedRevision, pngBase64)`.
   Palette strings are exact #RRGGBB or #RRGGBBAA; rows are a rectangular index
   matrix. This is concrete pixel authoring/upload, not a claim of AI image generation.
3. Inspect the image and adopt its actual returned digest and new revision.
   Set model.textureWidth/textureHeight to its real atlas size.
4. Atomically save coherent changes with
   `worldsmith_put_content_modules(sessionId, expectedRevision, modules)`;
   use `creatures` plus related theme/biomes/blocks/terrain/features as needed.
   Structure creation still uses the existing drawing/architecture tools.
5. Link creature IDs into real theme beats and spawn biome IDs to real biomes.
   If a write conflicts, reread the shared draft and merge; do not overwrite
   concurrent changes or reuse an old revision after uploading an asset.
6. `worldsmith_write_pack` accepts inline `creatures` or the session module and
   freezes all session assets with the theme and worldgen data. Model, asset,
   reference and capability validation precede native preparation. Report actual
   activation separately; never infer a playable entity merely from a valid PNG.

If related geometry uses custom blocks, use `worldsmith:content/<blockId>` with
no property suffix; source does not name native block slots. This creature
schema has no drops field, so it does not create block drops from prose alone.

## Construction and preview tools

For new models, read `worldsmith_get_creature_authoring_contract` and prefer a
`worldsmith_build_creature` recipe over hand-calculating every UV. Named bones/cubes,
mirrored limbs and automatic box UVs compile to this same runtime schema. Inspect
`worldsmith_preview_creature` model/sheet/uv images before committing a definition.
A diagnostic UV guide is not a finished skin. Upload a painted PNG of the exact
atlas size, rebuild with textureAsset, then merge the returned definition into the
current CreatureLibrary at its shared draft revision. Build artifacts never activate
a world or implement special Boss phases automatically.

## Obtainable rewards

Format-4 creatures may include `drops` entries with `item`, `minCount`, `maxCount`,
`chance`, and `requirePlayerKill`. Read the `items` content contract for aliases,
limits and examples. This is ordinary server-side death loot, not a new attack or
quest system. Keep drops in the authoring recipe too when rebuilding a creature;
otherwise a freshly generated definition correctly has no authored drops.
