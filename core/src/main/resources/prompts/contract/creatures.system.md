# Worldsmith custom creature contract — module schemas 1 and 2

Create ground creatures whose silhouette, material and behavior belong to the
same world theme. The installed runtime supports data-driven cuboid rigs,
procedural role-based motion, passive wandering/fleeing, hostile melee, bounded
death drops, and explicit schema-2 ground-melee Boss phases.
It is not an arbitrary behavior-script executor or a GeckoLib animation-file
loader. Flight, swimming-specialized movement, mounts, projectiles, inventories,
and multipart bosses have no schema fields in this module.

## Exact library and definition fields

`CreatureLibrary` has `schemaVersion` (1 by default, or 2) and `creatures`.
Schema 1 preserves ordinary creature definitions and omits `boss`. A non-null
`boss` requires schema 2 and current bundle format 5; formats 3/4 reject Boss
semantics rather than silently dropping them. An empty library is valid outside
a complete-world plan's named coverage promises; the maximum is 128 definitions.
Each definition has exactly:

- `id`: unique local lowercase identity, matching `[a-z0-9][a-z0-9_./-]{0,95}`;
  use a simple name without empty, `.` or `..` path segments.
- `displayName`: nonblank, at most 128 characters.
- `category`: `PASSIVE` or `HOSTILE`.
- `model`: the rig and texture document below (required).
- `attributes`, `behavior`, `spawn`: optional typed objects below, with defaults.
- `themeRole`: optional text at most 2048 characters describing the creature's
  ecological/narrative function; it is not executable behavior.
- `drops`: optional bounded death-reward list; see Obtainable rewards below.
- `boss`: optional `CreatureBossProfile` below; absent/null means ordinary behavior.

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
The 2048 health bound preserves the Core schema-1 reading contract; the current
native max_health limit is 1024. Author new playable definitions at health <=1024.
Native preparation checks every requested attribute and Boss-derived speed/damage
against the target game's actual attribute limits; a value that would be clamped
is reported as an error, not silently changed. Boss Core validation also caps
health at 1024 and requires positive base attackDamage.

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

## Schema 2 Boss profile

Use `category: "HOSTILE"`, `CreatureLibrary.schemaVersion: 2`, and an explicit
`boss` object. A large model, name or health number alone does not create a Boss.
The exact profile fields and defaults are:

- `phases`: required list of 2..3 phase objects, in threshold order below.
- `barTitle`: optional printable text <=128 characters, default `""` uses displayName.
- `barColor`: `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE` (default), or `WHITE`.
- `naturalSpawnChance`: finite 0.001..0.05, default 0.01; an extra acceptance roll
  after natural species selection, not a spawner probability.
- `naturalSpacingBlocks`: integer 32..256, default 128; nearby live loaded
  same-species Bosses suppress natural spawns within this distance.

Each phase has `name` (distinct nonblank printable text, <=48 characters),
`healthThreshold`, `speedMultiplier` (default 1), `damageMultiplier` (default 1),
`windupTicks` (default 12), `recoveryTicks` (default 20), and `poseIntensity` (default 1).
The first threshold is exactly 1.0; later thresholds strictly decrease and stay
above zero. A phase becomes eligible at remaining-health ratio <= its threshold.
Speed multiplier is 0.25..2.5 with base speed * multiplier still 0.01..1.0;
damage multiplier is 0.25..3.0 with derived attackDamage <=100. Windup is 1..100
ticks, recovery 4..200 ticks, and finite poseIntensity 0.5..2.0. Adjacent phases
must change speed, damage, windup or recovery; changing only name/pose is invalid.

Example value for `boss` (choose the names and combat pacing from the world prompt):

```json
{
  "barTitle": "Observatory Warden",
  "barColor": "PURPLE",
  "naturalSpawnChance": 0.01,
  "naturalSpacingBlocks": 128,
  "phases": [
    {"name":"Watchful","healthThreshold":1.0,"speedMultiplier":1.0,"damageMultiplier":1.0,"windupTicks":20,"recoveryTicks":30,"poseIntensity":1.0},
    {"name":"Fractured","healthThreshold":0.5,"speedMultiplier":1.2,"damageMultiplier":1.3,"windupTicks":14,"recoveryTicks":22,"poseIntensity":1.4}
  ]
}
```

The server selects and saves the phase; healing does not reverse it. Phase changes
apply real melee attributes/timings, and tracked players receive the native Boss
health bar with phase name. This is grounded melee, not arbitrary spells/scripts.
Natural Boss habitats additionally require spawn weight 1..3 and minGroup=maxGroup=1.
An empty spawn.biomes list disables natural spawning and may be used for a typed
structure-only encounter. Natural rarity/spacing is local and repeatable, not a
global uniqueness ledger or a guarantee that a Boss appears inside a building.

For a real landmark encounter, use StructureProgram's
`a.bossSpawner(at, creatureId[, respawnTicks, requiredPlayerRange, spawnRange])`;
read `worldsmith_get_contract(id:"draw", section:"boss-encounters")` for both exact
overloads, typed `boss_spawner` fields, room clearance and native checks. This route
needs structure module schema 2 as well as creature schema 2. It uses a separate
encounter host with a local cap, not natural habitat/light/chance/spacing selection.
Declare `contains_encounter` (structure -> creature) and `kill_objective`
(quest -> creature) in a complete-world design plan when those are actual promises.

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
no property suffix; source does not name native block slots. Explicit `drops`
entries may reward those block-item aliases; theme prose alone does not create loot.

## Construction and preview tools

For new models, read `worldsmith_get_creature_authoring_contract` and prefer a
`worldsmith_build_creature` recipe over hand-calculating every UV. Named bones/cubes,
mirrored limbs and automatic box UVs compile to this same runtime schema. Inspect
`worldsmith_preview_creature` model/sheet/uv images before committing a definition.
A diagnostic UV guide is not a finished skin. Upload a painted PNG of the exact
atlas size, rebuild with textureAsset, then merge the returned definition into the
current CreatureLibrary at its shared draft revision. Build artifacts never activate
a world. A Boss recipe must set recipe.schemaVersion=2 and carry its actual `boss`
profile; the build reply reports runtimeSchema=2. Merge it into a schema-2 library.
Preview with `worldsmith_preview_creature(sessionId, buildId, mode:"sheet", bossPhase:1)`;
the exact argument is `bossPhase`, zero-based and bounded by the actual 2..3 phases.
Ordinary creatures accept only phase 0. Model/sheet previews use that phase's shared
pose evaluator; UV mode remains an atlas view. Inspect every authored phase, but
do not treat a preview as combat, collision, native activation or playtest evidence.

## Obtainable rewards

Bundle formats 4/5 support up to 16 `drops` entries per creature. Each has `item`
(a native item or logical `worldsmith:item/<id>` / `worldsmith:content/<blockId>`),
`minCount`/`maxCount` (defaults 1/1, 1 <= min <= max <=64), `chance` (default 1,
finite 0..1), and `requirePlayerKill` (default false). Each entry is an independent
roll producing one stack; its maximum also respects the actual item's stack limit.
Read the `items` content contract for alias rules. Format 3 rejects death drops.
Keep drops in the authoring recipe when rebuilding a creature. Main-line gameplay
uses the separate `quests` module: a real kill_creature objective can target the
Boss, and a claimed quest reward can guarantee a later delivery item independently
of random Boss drops. Death loot and quest rewards are distinct producers.
