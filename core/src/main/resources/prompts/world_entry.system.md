# Worldsmith World Design

One Worldsmith pack is four documents submitted together: `terrain` shapes the
ground, `biomes` labels it and dresses it, `features` scatters things on it, and `structures` builds bounded architecture.
Each has its own contract. This page is what none of them can own on their own:
the order the decisions go in, and the places two documents have to agree.

## Order

**Terrain first.** It decides where material ends up - how much land there is,
how the coast runs, where the ground rises, where water collects. Biomes cannot
be chosen sensibly before that, because a biome is a label applied to terrain
that already exists, not a recipe that produces it.

**Biomes second**, from the terrain you just described. Read your own terrain
values back: an ocean-heavy `landRatio` needs aquatic biomes that actually claim
that space, and dominant `flats` needs climate boxes covering low relief.

**Features last.** They only decorate ground the first two documents produced.

## Where the documents meet

These are the joins a single contract cannot check on its own, and they are
where most rejected packs fail:

- A surface rule using a `hydrology` condition needs matching terrain: a dry
  riverbed rule needs non-zero `DRY` rivers, a wet one needs `FLUID` rivers, a
  lakebed rule needs non-zero lake density.
- Any `anchor` named by a band or by a biome's surface rule must be defined in
  the terrain document under that exact id.
- Every feature a biome references must exist in the feature library.
- At least one land biome must grow wood, or the world cannot be played.
- The land/water balance of the terrain and of the biome climate boxes must
  describe the same world.

## The two standards

The **player's prompt** is the only design standard. Biome count, distribution,
scale, relief, palette and density all come from it and from nothing else.

The **built-in pack** is a shape example only. Copy its field structure; never
copy its biome count, its climate partition, its palette or its theme. A pack
that echoes the example has answered a prompt nobody wrote.

Rejection is repair, not restart. Diagnostics name the exact path and code that
failed; change those and resend the whole document, keeping everything that was
already accepted.

## Structures

After terrain and biomes, design executable buildings under contract/structure. Use zero when none are requested, not a fixed twenty. Each blueprint owns its materials, geometry and local modules; placement references actual biome ids. Preview and validate individual blueprints before writing the whole pack. The source files are portable JSON; Minecraft templates are compiled artifacts. Do not make network or AI calls during chunk generation.
