# Worldsmith Feature Contract

A feature is one piece of scatter placed on the surface: ground cover, a trunk,
a rock. It is declared once in the feature library and referenced by id from any
number of biomes, so the shape and material are written once and compiled once.

## Document

```json
{
  "schemaVersion": 1,
  "features": [
    {
      "id": "ash_tuft",
      "recipe": "GROUND_PATCH",
      "block": { "semanticRole": "dead_scrub", "preferredIds": ["minecraft:dead_bush"] },
      "density": 0.35
    }
  ]
}
```

- `id` is lowercase `a-z 0-9 _ . -` and unique in the library.
- `recipe` is one of the ten names below and nothing else.
- `block` is shorthand for the sole material role of a recipe that needs only
  one. A selector has a
  required `semanticRole` plus at least one of `preferredIds` or `requiredTags`.
  Semantic role first; ids are hints. Every block id and tag id is namespaced,
  for example `minecraft:stone` and `minecraft:logs`; tag ids do not carry `#`.
- `materials` replaces `block` for a recipe built from more than one material,
  keyed by role. Never write both.
- `density` is `0..1`. Its meaning depends on the recipe; see below.

## The recipes

Ten of them, and the vocabulary is closed on purpose. The pack chooses a recipe,
materials and documented semantic controls; Minecraft placer classes and their
version-specific fields belong to the compiler. An unknown recipe
fails while loading rather than producing a quietly empty world.

| recipe | shape | placement | use it for |
| --- | --- | --- | --- |
| `GROUND_PATCH` | a configurable cluster on the surface | many attempts per chunk | grass, dead bush, ash tufts, mushrooms, coral - the cover that makes a biome look like a place rather than a texture |
| `DEAD_TREE` | a configurable vertical column | a rarity filter | bare trunks, spars, masts, stone pillars and cactus columns |
| `BOULDER` | one or more overlapping irregular blobs | a rarity filter, on ground that accepts it | rocks, slag lumps, ice chunks, bone piles, rubble |
| `ORE_VEIN` | a configurable vein cut into stone | an authored underground Y range | a mineral that belongs to this place - ore, crystal, buried ice, a seam of something wrong |
| `CAVE_PATCH` | a configurable cluster standing on a cave floor | an authored Y range and downward scan | glowing moss, crystal shards, fungus, bones - what a player finds by going down |
| `SURFACE_LAYER` | one block on the ground | the same as a ground patch, but placed after everything else | settled ash, drifted petals, snow, dust - cover that lies **on top of** the trunks and boulders rather than beside them |
| `AQUATIC_PATCH` | a configurable cluster on the sea floor | many attempts per chunk, under water by default | seagrass, kelp beds, coral, anything that makes a seabed something other than bare sand |
| `HANGING_PATCH` | configurable columns grown **downward** | scans upward for something to hang from | vines, roots, icicles, moss beards under overhangs and cave ceilings |
| `FALLEN_LOG` | a configurable log lying on its side, with a stump | a rarity filter | the thing that makes a forest floor read as old rather than as newly placed |
| `TREE` | a custom trunk skeleton plus a custom crown volume | tree-specific placement | living woods whose geometry and materials remain independent |

### Non-tree shape controls

Every object is optional; omission keeps the documented default. A shape object
on a recipe that does not consume it is an error rather than ignored output.

Patch recipes (`GROUND_PATCH`, `CAVE_PATCH`, `SURFACE_LAYER`,
`AQUATIC_PATCH`, and `HANGING_PATCH`) may carry:

```json
"patch": {
  "attempts": 7,
  "horizontalSpread": 4,
  "verticalSpread": 2,
  "scanDepth": 18
}
```

- `attempts` is `1..32`: how many blocks or columns one cluster tries.
- `horizontalSpread` is `0..8`. More than one attempt needs spatial spread.
- `verticalSpread` is `0..8` and belongs only to `CAVE_PATCH` and
  `HANGING_PATCH`; surface recipes always re-find their surface after the
  horizontal offset.
- `scanDepth` is `1..32` and belongs only to cave and hanging patches. It is how
  far the origin searches down for a floor or up for a ceiling.

Other recipe-owned geometry is direct:

```json
"boulder":  { "blobs": 4, "spread": 3 }
"oreVein":  { "size": 48, "discardChanceOnAirExposure": 0.6 }
"column":   { "minLength": 3, "maxLength": 10 }
"fallenLog": { "minLength": 6, "maxLength": 12 }
```

- `boulder.blobs` is `1..8` and `spread` is `0..8`. Nearby blobs overlap into
  a larger, less regular rock formation instead of scaling one perfect sphere.
- `oreVein.size` is `1..64`; `discardChanceOnAirExposure` is `0..1` and hides
  that share of blocks exposed to a cave, useful for rare minerals.
- `column` belongs to `DEAD_TREE` and `HANGING_PATCH`, with lengths `1..16`.
- `fallenLog` belongs only to `FALLEN_LOG`, with horizontal log lengths `1..14`;
  the compiler accounts for Minecraft's two-block stump gap itself.

### Non-tree placement conditions

Every non-tree recipe may carry a `placement` object:

```json
"placement": {
  "minY": 70,
  "maxY": 180,
  "substrate": "STONE",
  "fluid": "DRY"
}
```

- `minY` and `maxY` are optional absolute block heights within `-64..319`.
  On surface recipes they filter the surface already found; they never replace
  it with a random floating Y. On ore and cave recipes they also constrain the
  sampled underground origin.
- `substrate` is `RECIPE_DEFAULT`, `NATURAL_SOIL`, `SAND`, `STONE`, or
  `ANY_SOLID`. It checks the support below ordinary features and the attachment
  above a hanging patch.
- `fluid` is `RECIPE_DEFAULT`, `DRY`, `SUBMERGED`, `SHALLOW_WATER`, or `ANY`.
  `SHALLOW_WATER` requires water at the feature and air within four blocks.
- `ORE_VEIN` replaces its stone target rather than standing on a substrate, so
  it accepts no substrate override and only `RECIPE_DEFAULT` or `ANY` fluid.
- `TREE` keeps these concerns in `tree.distribution` and `tree.substrate`; do
  not give it this general placement object.

An anchor or terrain-region condition is not part of this placement object yet:
decoration runs after density routing and has no direct access to the shared
terrain influence field. Such a condition must be connected to that field, not
approximated with a second unrelated noise source.

### Trees

`TREE` is the only living-tree recipe. It combines one independently authored
trunk path with one crown volume; `TRUNK` and `FOLIAGE` separately name what
those shapes are made from. These are Worldsmith geometry rules, not aliases for
vanilla oak or cherry trees.

```json
"tree": {
  "trunk": {
    "shape": "BRANCHING",
    "height": { "min": 9, "max": 13 },
    "thickness": 1,
    "bend": 0.0,
    "branches": {
      "count": 4,
      "length": 5,
      "start": 0.55,
      "upwardBias": 0.65,
      "spread": 0.8,
      "lengthVariation": 0.25
    },
    "taper": 0.0,
    "flare": 1,
    "stems": 1
  },
  "crown": {
    "shape": "CLUSTERED",
    "radius": 4,
    "height": 6,
    "density": 0.9,
    "irregularity": 0.35,
    "hangingLeaves": 0.2
  },
  "distribution": "GROVE",
  "substrate": "NATURAL_SOIL",
  "decorations": ["LEAF_LITTER"]
}
```

Trunk `shape` is one of:

| shape | rule |
| --- | --- |
| `STRAIGHT` | a vertical main stem; optional branches still work |
| `BENT` | the main stem gradually walks in one horizontal direction |
| `TWISTED` | the drift direction rotates as the stem rises |
| `TAPERED` | a 2x2 lower stem narrows to a 1x1 upper stem |
| `CROOKED` | an irregular stem changes drift direction instead of following one arc |
| `FORKED` | several main forks separate at `start` and keep climbing |
| `BRANCHING` | a straight main stem with an authored set of side branches |
| `MULTI_STEM` | two to four complete stems leave one shared root origin |

- `height.min` and `height.max` are inclusive. Trunks stay within `1..36`, with
  `min` at most 32 and at most 24 blocks of variation. After rising branches and
  the upper crown are included, the whole tree may reach at most 44 blocks.
  Larger landmarks belong to structures.
- `thickness` is `1` or `2`.
- `bend` is `0..1` and is used by `BENT`, `TWISTED`, and `CROOKED`; those
  three shapes require a value greater than zero so their path is not silently
  identical to `STRAIGHT`.
- `TAPERED` requires `thickness: 2` and `taper` greater than zero. `taper` is
  `0..1`: it is the upper fraction of the trunk that has narrowed to 1x1. No
  other trunk shape consumes it.
- `flare` is `0..2` on every shape. It extends four roots horizontally by that
  many blocks from the trunk base; use it for old, heavy trees rather than as a
  substitute for trunk thickness.
- `stems` is `2..4` on `MULTI_STEM` and must remain `1` on every other shape.
  Each stem receives its own crown attachment. `MULTI_STEM` does not carry a
  `branches` object: stems describe its split directly.
- Optional `branches` has `count` `1..8`, `length` `1..8`, `start` `0.2..0.95`
  as a fraction of trunk height, `upwardBias` `0..1`, angular `spread` `0..1`,
  and `lengthVariation` `0..1`. Variation may shorten an individual branch by
  that fraction but never grows one beyond `length`. `spread: 0` groups branches
  near one direction; `spread: 1` distributes them around the stem. `FORKED`
  and `BRANCHING` require this object; every non-`MULTI_STEM` trunk may carry it.
  For `FORKED`, `count` is the number of main forks, `start` is their split
  height and `length` is how long those forks continue outward while rising.
  `upwardBias` controls their exact slope, but they rise at least every other
  step. Their highest possible attachment is split height plus `length`, rather
  than full nominal `height` plus `length`.

Crown `shape` is independent from the trunk:

| shape | rule |
| --- | --- |
| `ROUND` | an ellipsoidal broadleaf crown |
| `CONICAL` | narrow at the tip and wider downward |
| `LAYERED` | alternating wide and narrow tiers |
| `UMBRELLA` | an elevated parasol whose extra height extends a narrowing underside |
| `WEEPING` | a deep crown intended for trailing leaves |
| `CLUSTERED` | one full-height central crown plus smaller overlapping side lobes |
| `COLUMNAR` | a narrow vertical spindle, suitable for cypress-like silhouettes |
| `PAGODA` | discrete horizontal eaves that narrow toward the top |
| `WINDSWEPT` | each layer shifts with one world-seed-derived prevailing direction shared by the whole world, making asymmetric wind-cut forests |

- `radius` is `1..8`, `height` is `1..12`.
- Every crown shape consumes the full authored `height`; it is never accepted
  and then silently capped to a smaller number of layers.
- The validator combines trunk drift, stem separation, branch length and the
  crown's widest offset. That total horizontal reach must stay within 16 blocks,
  the bounded medium-tree clearance Minecraft can preflight atomically. Reduce
  bend, branches or crown radius when `TREE_HORIZONTAL_REACH_OUT_OF_RANGE`
  appears; larger silhouettes belong to the later structure layer.
- `density` is the chance to fill positions inside the chosen volume, `0.1..1`.
- `irregularity` is `0..1` and thins the boundary more than the interior.
- `hangingLeaves` is `0..1`; it grows one- and two-block extensions from the
  lower edge of any crown shape.
- Every trunk shape may combine with every crown shape. Use the prompt as the
  authority: a twisted trunk with a layered crown and pale leaves is one valid
  alien tree, not an error to normalize back into a vanilla species.
- These rules extend vanilla rather than replace it: vanilla logs and leaves
  remain valid materials, while the Worldsmith trunk and crown placers provide
  silhouettes the vanilla tree presets do not expose. Choose geometry from the
  requested world's visual language, not from the nearest vanilla species name.

- `distribution` is required: `SCATTERED`, `GROVE`, `FOREST`, or
  `DENSE_FOREST`. Grove and forest modes use one broad noise field, so trees
  gather into stands with clearings instead of forming a uniform grid.
- `substrate` is required: `NATURAL_SOIL` follows ordinary sapling rules,
  `SAND` makes a dry or alien sand tree, and `ANY_SOLID` accepts any sturdy
  exposed surface. `SHALLOW_WATER` starts on a sturdy lake or sea floor only
  when air is within four blocks, for willow and mangrove-like growth. These
  non-soil modes preserve the block under the trunk rather than replacing a
  themed surface with dirt.
- `decorations` may contain `VINES` and `LEAF_LITTER`. Do not repeat one.

`DEAD_TREE` is a naming accident worth reading past: it compiles to a bare
column with no leaves, whatever block it is made of. Use it where a bare column
is the intended silhouette; living trees belong to `TREE`.

`ORE_VEIN` replaces stone, so its block should be something that reads as being
*in* the rock. It is the only way a biome can own part of the underground: two
biomes with the same surface and different veins are two different places to
mine.

## Materials by role

Most recipes read one material and take the `block` shorthand. A recipe built
from more than one names them instead:

| recipe | roles |
| --- | --- |
| `TREE` | `TRUNK`, `FOLIAGE` |
| `DEAD_TREE`, `FALLEN_LOG` | `TRUNK` |
| everything else | `BLOCK` |

```json
{
  "id": "sakura",
  "recipe": "TREE",
  "materials": {
    "TRUNK":   { "semanticRole": "sakura_wood",  "preferredIds": ["minecraft:cherry_log"] },
    "FOLIAGE": { "semanticRole": "sakura_bloom", "preferredIds": ["minecraft:cherry_leaves"] }
  },
  "density": 0.7,
  "tree": {
    "trunk": {
      "shape": "BRANCHING",
      "height": { "min": 8, "max": 11 },
      "branches": {
        "count": 3,
        "length": 4,
        "start": 0.6,
        "spread": 0.75,
        "lengthVariation": 0.3
      },
      "flare": 1
    },
    "crown": {
      "shape": "CLUSTERED",
      "radius": 4,
      "height": 5,
      "density": 0.9,
      "irregularity": 0.3,
      "hangingLeaves": 0.2
    },
    "distribution": "GROVE",
    "substrate": "NATURAL_SOIL",
    "decorations": ["LEAF_LITTER"]
  }
}
```

A role the recipe does not read is rejected rather than ignored, because effort
spent on a material that never reaches the world is worse than an error.

`requiredTags` are intersected against the block tags already loaded in the
active Minecraft registry. They may name vanilla tags, loader/mod tags, or
Worldsmith tags that were registered before this pack is compiled. Several tags
mean the chosen block must satisfy all of them. A brand-new tag declared only
inside the same not-yet-exported generated pack is outside this single-stage
lookup; register/load that tag first or use explicit `preferredIds`.

## One role, several blocks

A selector may pick between alternatives per block instead of naming one:

```json
"block": {
  "semanticRole": "meadow_flora",
  "weighted": [
    { "material": { "semanticRole": "grass",     "preferredIds": ["minecraft:short_grass"] }, "weight": 8 },
    { "material": { "semanticRole": "dandelion", "preferredIds": ["minecraft:dandelion"] },   "weight": 2 },
    { "material": { "semanticRole": "poppy",     "preferredIds": ["minecraft:poppy"] },       "weight": 1 }
  ]
}
```

A weighted selector carries no `preferredIds` of its own, holds at most eight
entries, and nests only one level deep. Weights are relative, from 1 to 64.

This is what separates ground that reads as a meadow from ground that reads as
one plant repeated. Use it for cover and undergrowth especially; a single
species carpeting a whole biome is the most common way a world looks generated.

`ORE_VEIN` and `BOULDER` cannot take one: Minecraft hands those a single block
state rather than a provider, so the list would collapse to its first entry, and
that is reported as an error rather than done quietly.

Most inhabited landscapes want ground cover, upright silhouettes and something
that breaks up the ground plane. A world with only trees reads as a stage set:
no undergrowth means bare terrain-coloured floor to the horizon, and no rocks or
fallen wood means nothing interrupts it.

## Density

`density: 0` disables any feature exactly. Positive density is mapped according
to the cost and scale of the recipe:

- `GROUND_PATCH`, `SURFACE_LAYER` and `AQUATIC_PATCH` become a cluster count per
  chunk, `0..24`. Each cluster then spends `patch.attempts`; `0.1` is scattered
  tufts, `0.4` is ordinary cover, `1.0` is a carpet when the patch is small.
- `DEAD_TREE`, `BOULDER` and `FALLEN_LOG` remain rare props: positive density
  ranges from roughly one attempt every 32 chunks to one each chunk.
- `TREE` reads `tree.distribution`. `SCATTERED` treats density as the chance of
  one tree in a chunk. `GROVE` places up to 6 trees only on the wooded side of a
  noise field. `FOREST` places up to 4 in clearings and 10 in wooded chunks;
  `DENSE_FOREST` raises those ceilings to 8 and 16. Density scales the counts.
- `ORE_VEIN`, `CAVE_PATCH` and `HANGING_PATCH` become a vein or cluster count per
  chunk, `0..16`. Cave and hanging clusters multiply by `patch.attempts`; large
  ore veins are charged in proportion to their size. `0.25` is roughly as
  common as vanilla iron for a default-size vein.

One biome may spend at most 64 attempts per chunk in total. Exceeding it is the
`VEGETATION_BUDGET_EXCEEDED` error; the cap exists because an over-eager density
makes world generation crawl, and a slow world looks nothing like its cause.
For trees, one attempt is scaled by trunk volume and by every branch-tip crown;
large many-crowned trees therefore consume more of the same budget. Boulder
blob count, column/fallen-log length, cave scan depth and patch cluster size are
charged too.

## Where each one runs

The recipe also decides which stage of chunk generation the feature belongs to,
and that is ordering rather than position: it decides what is already there when
the feature runs. Ore is cut before anything stands on the ground, a boulder is
a change to the land rather than something growing out of it, plants grow after
both, and a surface layer settles last onto whatever the others left. You do not
choose the stage; naming the right recipe chooses it.

## Referencing from a biome

```json
"features": [
  { "feature": "ash_tuft" },
  { "feature": "dead_spruce_trunk", "density": 0.08 }
]
```

Omit `density` to use the library value, which lets every biome share one
compiled placement. Override it only when a biome genuinely needs a different
amount - a trunk that is common in the forest and rare on the plain.

The order of entries in a biome's `features` array has no placement meaning.
The compiler always normalizes shared features to their declaration order in
this library because Minecraft builds one global feature order across all
biomes. Express abundance with `density`, not by reordering the array.

## Required: the world must be playable

**At least one land biome must reference `TREE` or `DEAD_TREE` with a log in its
`TRUNK` role**, at a density of at least `0.15`. Minecraft survival starts by
punching a tree: with no wood there is no crafting table, no tools, and no way
to play the world at all. Prefer `TREE` wherever the world has living growth;
reserve `DEAD_TREE` for places where a bare column is what you mean.

This holds for dead and hostile worlds too. A petrified trunk, a fossil spar, a
wrecked mast and a charred pillar are all logs, and any of them keeps the world
playable while staying in character. Prefer a biome the player is likely to meet
early; wood that only grows at the bottom of the ocean does not count.

Reported as `NO_WOOD_IN_WORLD` when no feature has a trunk, and
`NO_WOOD_ON_LAND` when land biomes do not reference enough of it.
