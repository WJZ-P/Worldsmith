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
architecture before publication. Format 4 freezes all eight typed modules, PNG assets, SDK geometry, metadata and source provenance. Old unreleased bundle formats require regeneration. Native export/readback and reload precede completion. No network or AI calls occur during chunk generation. A validated
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

## One theme, one immutable content bundle

Begin with `worldsmith_get_content_framework` and read theme/blocks/creatures/items using
`worldsmith_get_content_contract`. Establish the world's premise, player role,
rules, main conflict and narrative beats linked to real terrain/biomes/structures,
blocks or creatures. Narrative beats preserve creative intent; executable quests
and achievements are not yet installed.

Use `worldsmith_put_content_modules` for complete typed theme, terrain, features,
biomes, blocks, creatures and ordinary items documents. Architecture and frozen Java drawings keep
their dedicated tools. All changes use the SAME durable session revision: read
`worldsmith_get_content_draft`, send expectedRevision, and keep returned revisions.
A conflict means re-read/merge; never silently overwrite another agent's draft.

Textures are actual PNG bytes: upload via `worldsmith_put_texture_asset`, or author
indexed pixels with `worldsmith_create_pixel_texture`; inspect their returned PNGs.
Reference the returned asset SHA-256 in a block or creature model. The built-in
texture tool is deterministic pixel authoring, not an image model or text-to-image
service. Custom blocks use `worldsmith:content/<localId>` and fixed physical
profiles. Do not invent raw host slots, arbitrary state properties, custom stairs
or unsupported flight/swimming/behavior code.

Plan catalog links with `worldsmith_plan_world_content`; it never implies native
activation. `worldsmith_write_pack` freezes a format-4 bundle with all eight
modules and its verified PNGs at expectedRevision. `worldsmith_finish_world` also
requires native data reload, verified client resources and preset selection.
Bundles and slot assignments are embedded in the native datapack carried by the
save. Runtime has no AI/network calls during chunk generation or entity ticks.
Current generated content targets one local integrated-server world; remote
asset/binding negotiation is not installed. Formats 1/2 require regeneration; format 3 remains read-only with its original identity.

For creature construction, prefer `worldsmith_get_creature_authoring_contract` and
`worldsmith_build_creature` for named bones/cubes, mirrored limbs and automatic UVs.
Inspect actual textured models and the shared procedural poses with
`worldsmith_preview_creature`. Guide textures are diagnostic only; bind the painted
PNG before merging a definition. Joined-world creative content is available in the
Worldsmith tab as bound BlockItems and species-specific summoners; a future ordinary
item domain can join that catalog without exposing unbound native slots.

Use ordinary items and explicit reward links to connect exploration, creatures and
structures: `worldsmith:item/<id>` names a real world-bound item, not a raw registry
host. The items contract describes icons, stack limits, rarity and creature/container
drops. This layer provides obtainable resources/relics; do not claim crafting,
custom equipment, quests or achievements merely from a themed reward description.
