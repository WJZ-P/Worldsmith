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
- `recipe` is one of the three names below and nothing else.
- `block` is a material selector: `semanticRole` is required, plus at least one
  of `preferredIds` or `requiredTags`. Semantic role first; ids are hints.
- `density` is `0..1`. Its meaning depends on the recipe; see below.

## The three recipes

The vocabulary is closed on purpose. The pack chooses a recipe and a material;
the shape and every placement rule belong to the compiler. An unknown recipe
fails while loading rather than producing a quietly empty world.

| recipe | shape | placement | use it for |
| --- | --- | --- | --- |
| `GROUND_PATCH` | one block on the surface | many attempts per chunk, only into air | grass, dead bush, ash tufts, mushrooms, coral - the cover that makes a biome look like a place rather than a texture |
| `DEAD_TREE` | a vertical column 2-5 blocks tall | a rarity filter | trunks, spars, masts, stone pillars, cactus columns. **The only recipe that can be wood** |
| `BOULDER` | an irregular blob | a rarity filter, on ground that accepts it | rocks, slag lumps, ice chunks, bone piles, rubble |

`DEAD_TREE` is a naming accident worth reading past: it compiles to a bare
column with no leaves, whatever block it is made of. It is not restricted to
dead worlds, and it is what you use for any living trunk too.

Most worlds want all three. A world with only trunks reads as a stage set: no
ground cover means bare terrain-coloured floor to the horizon, and no boulders
means nothing breaks up the silhouette.

## Density

Density is one number with two meanings, because the two placement styles are
not comparable:

- `GROUND_PATCH` becomes an attempt count per chunk, `1..24`. `0.1` is scattered
  tufts, `0.4` is ordinary cover, `1.0` is a carpet.
- `DEAD_TREE` and `BOULDER` become a rarity filter: roughly one attempt every
  `(1 - density) * 32` chunks. `0.05` is a landmark you find every few minutes of
  walking, `0.5` is one every sixteen chunks, above `0.9` is nearly every chunk.

One biome may spend at most 64 attempts per chunk in total. Exceeding it is the
`VEGETATION_BUDGET_EXCEEDED` error; the cap exists because an over-eager density
makes world generation crawl, and a slow world looks nothing like its cause.

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

## Required: the world must be playable

**At least one land biome must reference a `DEAD_TREE` feature whose block is a
log**, at a density of at least `0.15`. Minecraft survival starts by punching a
tree: with no wood there is no crafting table, no tools, and no way to play the
world at all.

This holds for dead and hostile worlds too. A petrified trunk, a fossil spar, a
wrecked mast and a charred pillar are all logs, and any of them keeps the world
playable while staying in character. Prefer a biome the player is likely to meet
early; wood that only grows at the bottom of the ocean does not count.

Reported as `NO_WOOD_IN_WORLD` when no feature uses the recipe, and
`NO_WOOD_ON_LAND` when one does but no land biome references it.
