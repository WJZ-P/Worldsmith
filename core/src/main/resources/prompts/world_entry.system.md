# Worldsmith World Design

One Worldsmith pack is four documents submitted together: `terrain` shapes the
ground, `biomes` labels it and dresses it, `features` scatters things on it, and `structures` builds bounded architecture.
Each has its own contract. A fifth contract, `architecture`, coordinates building
groups, independent structures, a theme-defining landmark and readable interiors.
This page is what none of them can own on their own:
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

After terrain and biomes, proactively design world-specific architecture under
contract/architecture: at least two distinct groups, one independent structure,
and one monumental LANDMARK group that most strongly expresses the world theme.
Infer plausible functions and secondary places instead of waiting for the player
to list every building. Do not copy a fixed cross-player catalog or style.

Submit the architecture plan, then use contract/draw: build Java through the MCP
worker, inspect returned model images, revise, and attach frozen drawing ids to
contract/structure metadata. Required and optional roles must match actual assembly variants;
every occupied indoor space needs authored readable night lighting. Blueprints own
geometry/material choices; placement references real biome ids. Validate the complete
architecture before publication. Format 2 freezes SDK geometry plus JSON metadata and source provenance; old format 1
JSON remains readable. Native export/readback and reload precede completion. No network or AI calls occur during chunk generation. A validated
landmark plan is not proof of a placed instance.


## Efficient structure authoring

New MCP clients may call begin_world with detail=summary; contract indexes and section
retrieval avoid re-reading every document. Full contracts remain available. For buildings,
prefer source projects plus StructureProgram: representative components -> main mass ->
roof/facade -> circulation -> occupied lighting -> decoration -> secondary variants.
Read architecture sections `creative-brief`, `form-function-and-family` and
`visual-quality-loop`. Carry concrete theme-to-form choices in the existing plan
fields. Study the main building in clay before decoration, review side/rear
elevations and each occupied floor, then compose the group in top/opposite views.
Reuse component craft, not a single building shape for unrelated functions.
The summary entry retains `designGuide` and a `designReference`; it is not a reason
to skip the creative brief or inspect only machine-checkable counts.
Run worldsmith_preflight_structure early and use spatial model overlays to repair errors.
Do not confuse worker success with authoring checks, or authoring checks with publication.
Use worldsmith_authoring_stats for host work; never call request gaps model-thinking time.
