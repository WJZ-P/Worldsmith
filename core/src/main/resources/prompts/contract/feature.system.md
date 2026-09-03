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
  Semantic role first; ids are hints.
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
| `GROUND_PATCH` | one block on the surface | many attempts per chunk, only into air | grass, dead bush, ash tufts, mushrooms, coral - the cover that makes a biome look like a place rather than a texture |
| `DEAD_TREE` | a vertical column 2-5 blocks tall | a rarity filter | bare trunks, spars, masts, stone pillars and cactus columns |
| `BOULDER` | an irregular blob | a rarity filter, on ground that accepts it | rocks, slag lumps, ice chunks, bone piles, rubble |
| `ORE_VEIN` | a vein of about 33 blocks cut into stone | underground, from near bedrock up to y 64 | a mineral that belongs to this place - ore, crystal, buried ice, a seam of something wrong |
| `CAVE_PATCH` | one block standing on a cave floor | underground, dropped into open air and walked down onto solid ground | glowing moss, crystal shards, fungus, bones - what a player finds by going down |
| `SURFACE_LAYER` | one block on the ground | the same as a ground patch, but placed after everything else | settled ash, drifted petals, snow, dust - cover that lies **on top of** the trunks and boulders rather than beside them |
| `AQUATIC_PATCH` | one block on the sea floor | many attempts per chunk, refused unless it is under water | seagrass, kelp beds, coral, anything that makes a seabed something other than bare sand |
| `HANGING_PATCH` | a short column grown **downward** | scans upward for something to hang from | vines, roots, icicles, moss beards under overhangs and cave ceilings |
| `FALLEN_LOG` | a log lying on its side, with a stump | a rarity filter | the thing that makes a forest floor read as old rather than as newly placed |
| `TREE` | a custom trunk skeleton plus a custom crown volume | tree-specific placement | living woods whose geometry and materials remain independent |

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
    "branches": { "count": 4, "length": 5, "start": 0.55, "upwardBias": 0.65 }
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
| `FORKED` | an upper stem split; requires at least two branches |
| `BRANCHING` | a straight main stem with an authored set of side branches |

- `height.min` and `height.max` are inclusive. Trees stay within `1..40`, with
  `min` at most 32 and at most 24 blocks of variation. Forty blocks is already
  slightly taller than an ordinary large jungle tree; larger landmarks belong
  to structures.
- `thickness` is `1` or `2`.
- `bend` is `0..1` and is used by `BENT` and `TWISTED`.
- Optional `branches` has `count` `1..8`, `length` `1..8`, `start` `0.2..0.95`
  as a fraction of trunk height, and `upwardBias` `0..1`. `FORKED` and
  `BRANCHING` require it; every other trunk may also carry branches.

Crown `shape` is independent from the trunk:

| shape | rule |
| --- | --- |
| `ROUND` | an ellipsoidal broadleaf crown |
| `CONICAL` | narrow at the tip and wider downward |
| `LAYERED` | alternating wide and narrow tiers |
| `UMBRELLA` | a shallow, elevated canopy |
| `WEEPING` | a deep crown intended for trailing leaves |
| `CLUSTERED` | several overlapping crown lobes around branch tips |

- `radius` is `1..8`, `height` is `1..12`.
- `density` is the chance to fill positions inside the chosen volume, `0.1..1`.
- `irregularity` is `0..1` and thins the boundary more than the interior.
- `hangingLeaves` is `0..1`; it grows one- and two-block extensions from the
  lower edge of any crown shape.
- Every trunk shape may combine with every crown shape. Use the prompt as the
  authority: a twisted trunk with a layered crown and pale leaves is one valid
  alien tree, not an error to normalize back into a vanilla species.

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
      "branches": { "count": 3, "length": 4, "start": 0.6 }
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

- `GROUND_PATCH`, `SURFACE_LAYER` and `AQUATIC_PATCH` become an attempt count per
  chunk, `0..24`. `0.1` is scattered tufts, `0.4` is ordinary cover, `1.0` is a
  carpet.
- `DEAD_TREE`, `BOULDER` and `FALLEN_LOG` remain rare props: positive density
  ranges from roughly one attempt every 32 chunks to one each chunk.
- `TREE` reads `tree.distribution`. `SCATTERED` treats density as the chance of
  one tree in a chunk. `GROVE` places up to 6 trees only on the wooded side of a
  noise field. `FOREST` places up to 4 in clearings and 10 in wooded chunks;
  `DENSE_FOREST` raises those ceilings to 8 and 16. Density scales the counts.
- `ORE_VEIN`, `CAVE_PATCH` and `HANGING_PATCH` become a vein or cluster count per
  chunk, `0..16`. `0.25` is roughly as common as vanilla iron; above `0.6` a
  player will not be able to walk a cave without seeing it.

One biome may spend at most 64 attempts per chunk in total. Exceeding it is the
`VEGETATION_BUDGET_EXCEEDED` error; the cap exists because an over-eager density
makes world generation crawl, and a slow world looks nothing like its cause.

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
