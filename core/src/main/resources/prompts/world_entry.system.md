# Worldsmith World Design

One current Worldsmith format 10 pack freezes twelve typed modules: `theme`, `terrain`,
`biomes`, `features`, `structures`, `blocks`, `creatures`, `items`, `quests`, `mechanics`, and `abilities`.
Each has its own contract. Mechanics compose actual player placement or main-hand
use with a block pattern, item cost, anchor state and typed actions; read
`worldsmith_get_content_contract(module="mechanics")` for executable interactions.
A WorldBible RULE or a narrative beat alone never executes one.
 `architecture` coordinates building groups, independent
structures, a theme-defining landmark and readable interiors; it is an authoring
contract, not an additional bundle module. This page connects their responsibilities.

## Plan the whole world before multiplying assets

For a new COMPLETE_WORLD, first read `world_bible` and save a persistent structured
WorldBible from the original prompt. Record hard constraints, assumptions, history,
regional ecology, main-line experience and resource/reward chains together. Read
the returned review context and submit an evidence-bound AI self-review; passing
the current review automatically continues, without a user-confirmation pause.
Then read `grand_world` section `world-atlas`, the live `authoringBudgets`,
`world_design` and `module_briefs`; save named targets and concrete derived briefs
before multiplying assets. Scale follows the player's prompt, not a sample pack.
Regional names and societies are session setting nodes, not invented runtime
module fields or installed faction/NPC mechanics. An unsupported promised mechanic
stays an explicit open decision, not a silently downgraded piece of lore.
Begin summaries retain a compact `worldPlanningGuide` and a section reference;
use those instead of loading every contract or skipping the macro-design step.
On resume preserve accepted content and continue from actual progress gaps.
Legacy sessions keep their old workflow unless explicitly upgraded through
`worldsmith_upgrade_world_authoring`. WORLDGEN_ONLY and STANDALONE stay lightweight.
The complete bible, briefs and reviews persist in the session, not in `.wspack`.

## Order

After the bible, AI review, plan and module briefs, **terrain first** is the
physical production order, not the order in which the story is invented. It decides where material ends up - how much land there is,
how the coast runs, where the ground rises, where water collects. Biomes cannot
be chosen sensibly before that, because a biome is a label applied to terrain
that already exists, not a recipe that produces it.

**Biomes second**, from the terrain you just described. Read your own terrain
values back: an ocean-heavy `landRatio` needs aquatic biomes that actually claim
that space, and dominant `flats` needs climate boxes covering low relief.

**Features follow terrain and biomes.** They decorate the ground these documents
produce, while the other content modules follow their actual dependencies.

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
architecture before publication. Format 10 freezes all twelve typed modules, PNG assets, SDK geometry, metadata and source provenance. Older bundle formats are rejected; local packs and saves are not rewritten. Native export/readback and activation precede completion. No network or AI calls occur during chunk generation. A validated
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

Use `worldsmith_get_content_framework` and the needed domain contracts. In the
new complete-world flow, derive theme from the reviewed WorldBible: the existing
runtime theme is a bounded projection, not a second editable fact source. Its
premise, player role, rules, conflict and beats must agree with the authoring
basis and link to actual content. Review that projection through its owning brief.
Narrative beats preserve creative intent; the separate quests schema 2 and story modules make branching fact/kill/delivery/committed-activation goals and actual marker-bound inhabitants executable; existing quests project into native advancements after reward claims; independent achievement authoring remains uninstalled.

Use `worldsmith_put_content_modules` for complete typed theme, terrain, features,
biomes, blocks, creatures, world-bound items, quests and mechanics documents. Architecture and frozen Java drawings keep
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
activation. Before publishing a new complete world, submit current AI alignment
reviews against actual modules, drawings and referenced assets, not just plans.
`worldsmith_write_pack` and `worldsmith_finish_world` recheck authoring provenance
and the existing engineering gates. Repeated identical blockers on the same input
have at most three automatic repair attempts; report the unresolved decision
instead of repeatedly self-approving. `worldsmith_write_pack` freezes a format-10 bundle with all twelve
modules and its verified PNGs at expectedRevision. `worldsmith_finish_world`
reports native preparation/activation separately; a real Create World selection
owns any required data/resource reload and preset activation. Do not reload the
whole client merely to inspect or validate a pack.
Bundles and slot assignments are embedded in the native datapack carried by the
save. Runtime has no AI/network calls during chunk generation or entity ticks.
Current generated content targets one local integrated-server world; remote
asset/binding negotiation is not installed. Older bundle formats are rejected; local packs and saves are not rewritten.

For creature construction, prefer `worldsmith_get_creature_authoring_contract` and
`worldsmith_build_creature` for named bones/cubes, mirrored limbs and automatic UVs.
Inspect actual textured models and the shared procedural poses with
`worldsmith_preview_creature`. Guide textures are diagnostic only; bind the painted
PNG before merging a definition. Joined-world creative content is available in the
Worldsmith tab as bound BlockItems, world-bound items and species-specific summoners,
without exposing unbound native slots.

Use world-bound items and explicit reward links to connect exploration, creatures and
structures: `worldsmith:item/<id>` names a real world-bound item, not a raw registry
host. Items schema 1 keeps ordinary resources/relics; schema 2 adds native melee
weapons, tiered mining tools, four armor slots, consumables and composed USE /
MELEE_HIT actions from heal/feed/status/projectile/blink. Read contract/items for
the actual DTO and independent armor UV atlas; schema 3 run_program uses the
shared abilities source runtime. Read contract/abilities and its live capability
signatures for events, waits, functions, branches and state, not a skill catalogue. Choose useful
gear or interactive rewards proactively rather than making every custom item a
delivery token. Link real sources and actual quests; lore alone installs no recipe,
passive worn effect, arbitrary spell script or new objective type.

Ordinary armor right-click equips it; held crouch-right-click invokes its active
ability. Food USE actions run at completed consumption and already consume one
survival item, so extra consumeCount must be zero. Cooldowns are per player/world/
logical item, not shared by every custom item host. Make the player's controls and
effects clear in natural world/playing language rather than implementation prose.
MELEE_HIT belongs only to non-armor weapon/tool equipment; materials and armor
can use USE instead. A failed food completion action consumes nothing and starts
no cooldown; successful ItemActions is the sole ability cooldown settlement.

Select an existing signature item or block with the write request's optional
`representativeContent: {"kind":"item","id":"existing_local_id"}` (or block).
This becomes manifest display metadata; the browser reuses the actual item/block
PNG through an isolated preview, without activating the selected world's resources.

For texture production, call `worldsmith_get_texture_workflow`. The same public
interfaces work for any MCP client: bounded deterministic pixel recipes, dedicated
PNG inbox import (optional explicit nearest resizing), raw PNG upload and actual
previews. External image-generation tools are owned by the AI client, not bundled
or required by this mod. Never assume another AI has the current client's image model.

For a playable main line, explicitly author quests linked to real species/items and
optional narrative beats. Only kill_creature/deliver_item are installed. Delivery
consumes actual main-inventory items on player request; claim is separate, once-only,
and requires reward space. The J journal reports server progress. MCP publication
completeness does not mean a player has completed these quests.

## Keep player prose inside the world

Manifest names/descriptions, theme titles/premise/playerRole/mainConflict/rules/
beats, item names/lore and quest titles/descriptions are player-facing writing.
Describe actual places, stakes, materials, controls and effects. Keep limitations,
tool/schema details, save-progress disclaimers, terrain-validation statements and
publication status in authoring diagnostics/receipts only, never pasted into that
prose. PlayerTextPolicy reports PLAYER_TEXT_ENGINEERING_LEAK at new authoring
publication; repair the authored field rather than silently filtering the UI.
This gate does not rewrite/reject existing immutable packs, embedded save text or
any previously created worlds. Old loading keeps its prior identity/format rules.

The arrival HUD uses the verified theme and the player's actual current quest for
an optional, default-on five-second introduction. It leaves controls active,
can be skipped with Esc and appears only once per real connection. Write concise
world background and meaningful objectives; add no invented intro/toast fields.

## Deferred player choice and native preparation

All installed packs are menu choices without native resource activation. The native World Type control and Tiangong header share one creation intent. Browsing, choosing a pack, and finish-world suggestions do not trigger client resource reloads. `WAITING_CREATION` / `requiresUserAction:true` means the player must choose that pack and press Create New World; report this action and pause instead of rebuilding or polling. Native preparation then resumes the original guarded creation action. Core/archive readiness remains distinct from actual native activation and from a played world.


Creature voices are authored data too: for each new species use creature/recipe
schema 3 and a sounds profile from the creatures contract's soundVocabulary and
soundVoices. Select real vanilla events and modulate pitch/volume/variation; do not
invent audio files. Preserve voices, drops and Boss profiles through texture rebuilds,
and never downgrade a schema-3 creature library when merging species.
