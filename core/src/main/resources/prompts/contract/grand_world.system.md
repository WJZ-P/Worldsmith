# Worldsmith grand-world planning

Build scale through distinct places, ecological relationships, readable journeys
and consequences of one premise, not merely a larger list of assets. The player's
prompt sets ambition; a small island and a vast continent need different plans.
This is an authoring method, not a new runtime module or a mandatory world size.

Before detailed production in a new complete world, save and AI-review the
WorldBible under `world_bible`. Read `grand_world` section `world-atlas`, get the
live `authoringBudgets` from `worldsmith_get_content_framework`, then read
`world_design` and `module_briefs` and save named targets, links and derived
implementation briefs. Plan main-line experience and resource/reward chains
alongside regional ecology. Use COMPLETE_WORLD for a full
world; STANDALONE remains a focused artifact workflow. Preserve an existing plan
and completed assets when resuming; add this review at the current gap rather
than starting over.

Think at three scales: the world's physical skeleton; regions and travel routes;
individual habitats, settlements, ruins and encounters. Persist the atlas as
stable REGION, ECOLOGY, RESOURCE, HISTORY, STYLE and EXPERIENCE setting nodes in
the bible; derive existing `goal`, target `purpose`, theme and actual domain fields
from these facts. Region and faction labels are not new runtime JSON keys or
ContentKey kinds. Catalog counts are distinct definitions,
not the number of places generated in an unbounded world.

## World atlas

Produce an atlas before spending most of the run on models. In new complete
worlds its facts live in the structured bible, with deterministic readable MD;
do not maintain an independent atlas document or submit an unknown runtime module.
Legacy/lightweight sessions retain their existing compact-plan approach.

1. **Premise and scale.** State the player's role, central tension, physical
   consequences and exploration promise. Choose an intimate, regional or broad
   scope from the prompt, not a fixed biome/landmark quota. Explain what should
   feel far away, scarce, familiar or dangerous. Keep a quiet baseline so special
   places retain contrast; filling every chunk with a landmark destroys scale.
2. **Physical skeleton.** Sketch the land/water balance, major relief, rivers,
   cave/sky layers if relevant, and only the anchors needed for identifiable
   places. Translate these into the terrain contract before choosing climate
   boxes. Narrative compass directions alone do not move noise-based biomes.
3. **Regional identities.** For each proposed region record a readable name,
   ecological reason to exist, distinctive silhouette/palette, resource and
   creature roles, settlement/ruin functions, story role, neighbors and intended
   traversal. Separate a true biome distinction from an architectural variation
   or a surface feature. Share craft without making every region the same place
   recolored. Deliberately include transitions rather than only extreme zones.
4. **Travel graph.** Connect arrival, supplies, discoveries, optional detours and
   climactic encounters. For each connection record its reason, intended terrain,
   available provisions, navigation cue and the actual authoring field that can
   realize it. If no supported field implements a connection, label it design-only
   rather than inventing configuration. Mark route length and travel time as
   estimates until measured.
5. **Stable named inventory.** Turn the atlas into `WorldDesignPlan` targets and
   supported links. Use readable player-language titles for the progress UI,
   stable local IDs and concrete purposes. Assign shared assets to one owner;
   record cross-region reuse rather than duplicating IDs or budget counts.

Derive a concise atlas summary into `WorldDesignPlan.goal` and the appropriate
targets' `purpose` text. Project essential setting intent into the bundle's theme
`premise`, `worldRules` and beats, then review that projection through its brief.
The full atlas stays in the session bible within authoring budgets, not a new
`.wspack` module or a second independently edited document. All runtime links use installed
ContentKey kinds and real definitions; do not add `regions`, `factions`, `routes`
or new relationship verbs to typed documents. A supported `placement.region`
field is its exact existing statistical placement grammar, not this prose atlas.

## Content budgets

Read `authoringBudgets` in begin/framework responses instead of trusting a static
example's numbers. Limits describe this installed version, not recommended sizes
or a promise that a near-limit world will perform well. A null biome-specific cap
does not remove shared content-entry and document-byte limits.

Maintain a ledger: planned use, already committed use, remaining headroom and
owner for every bounded category. Count distinct targets/links and complete
library definitions, plus blueprints, assembly variants, per-plan geometry,
whole-catalog template geometry, block profile slots, creatures/Bosses, items,
quests and actual PNG bytes/pixels. A per-plan limit is not the whole-catalog
limit; distinct blueprint geometry variants share the catalog allowance, while
each assembled plan separately counts its authored parts, including repeated
instances. Reusing a template does not recharge its cells to the catalog. Count
explicit AIR where the relevant compiler counts it. Share one identical PNG by
its digest rather than charging it once per reference; changed pixels are a new
asset. Different creature UV layouts need compatible skins, not blind tile reuse.

Reserve headroom for revisions, transition content and essential missing roles.
An optional reserve target such as one fifth is a planning choice, not a validator
gate. Check both total blocks and each physical profile: free slots in one profile
do not solve exhaustion in another. Feature density is not a definition budget.
More biome names do not ensure more visible habitats if their climate ranges lose
selection everywhere. More structure definitions do not imply more placed sites.

When the desired scope exceeds the budget, first reuse compatible materials,
components and texture assets, reduce redundant variants, simplify expensive
geometry, or discuss the scope tradeoff. Never quietly drop promised categories,
weaken validation, switch a complete world to WORLDGEN_ONLY, or claim that splitting
the work into batches resets a per-world limit. Keep the ledger for the whole
bundle across all batches.

## Region routes

Use statistical terrain and biome distribution for repeating ecological zones.
For a named location or corridor, use the terrain contract's fixed/scattered/line
anchors and explicit climateBias as appropriate, with actual biome coverage and
structure placement restrictions. Anchor influence is not a hard regional border.
Line anchors deform a finite corridor; they do not automatically construct roads,
bridges or linked settlements. Authored paths and crossing structures require real
geometry and a placement strategy. Anchored starts still undergo biome, terrain,
clearance and reservation checks; an intended capital may fail to generate.

Make travel legible through contrasting relief, horizon silhouettes, material
families and repeated wayfinding motifs. Verify entrances, slopes, waterways and
occupied spaces rather than counting path names. Provide practical early supplies
and a reachable progression idea before designing remote spectacles. Distinguish
scarcity from chores: repeating one long trip is not additional world depth.

Narrative societies and historical tensions may shape ruins, materials and theme
text. Actual inhabitants require story character/place markers and dialogue/routine
definitions; reputation can use declared player facts. Prose alone does not install
these systems, faction territory control or dynamic wars. Regional difficulty is an authored distribution of the
supported creature attributes/behavior and resources, not automatic level scaling.

Quest schema 2 supports branching kill/delivery/activation/fact objectives with
conditions, acceptance and explicit reward claims. Optional journeys can use actual
place discovery facts; do not invent a visit-region objective or infer discovery
from a declared place without a generated marker. Check that every
delivery has an obtainable earlier producer; earlier rewards and fixed/line
supplies are finite and consumed by prior deliveries. Exclude the task's own and
future rewards as prerequisites. A positive random loot chance is not a guaranteed
drop. A typed BossSpawner connects an authored building to a repeatable local Boss
encounter; it does not guarantee a placed building or a globally unique final Boss.

## Production batches

Organize work by dependencies and the largest current uncertainty, not by a rigid
sequence that repeats already finished work. A useful initial path is:

1. Save and AI-review the bible, then save the named plan and module briefs.
   Terrain-first now means physical production after these authoring foundations.
   Establish terrain/biome coverage,
   material families, item roles, habitats, narrative beats and progression links.
2. Build a representative slice: one distinctive inhabited place and its terrain,
   block textures, creature skin, obtainable reward and applicable quest link.
   Review actual model/texture views before multiplying the art direction.
3. Expand regional families with reusable components and compatible assets while
   varying massing, function, circulation, ecology and encounter role. Do not use
   the same building shell for every purpose. Freeze accepted drawings, reuse
   their IDs and record which regional targets each batch fulfills.
4. Assemble cross-region links, remaining transitions and major encounters.
   Reconcile the shared ledger and submit evidence-bound implementation reviews
   against actual candidate content before final freezing, not only a prose plan.

Independent source/recipe design and visual reviews can run in parallel. Shared
session mutations require the latest expectedRevision; a coordinator should merge
them or re-read after conflict. Separate source files per assignment. Preserve
successful jobs and pending approvals; do not resubmit work to bypass approval.
After interruption read `worldsmith_resume_session(detail: "summary")`, the saved
plan and `worldsmith_get_generation_progress`. Work from actual gaps/failed-write
diagnostics, not a reconstructed imagined inventory.

Use content-addressed reuse and existing resource-pack tools for verified compatible
assets. Importing a `.wspack` stores a whole immutable bundle; it does not silently
merge its modules into the current session. A reused recipe is not a finished
texture, and a referenced drawing is not present until its frozen data is resolved.

## Global review

Evaluate the whole world as a journey, not only one beautiful model per batch.
Keep a brief finding/evidence/repair ledger and inspect the highest-impact gaps.
Use `worldsmith_get_authoring_review_context` and the persisted bible/alignment
review tools, with current digests, required checks and real evidence paths.
AI PASS automatically continues; it is not user approval. The same input/issue
gets at most three identical automatic repair failures before an explicit blocker
or scope decision is reported. Keep accepted geometry and textures during repair.

| Review | Questions and evidence |
| --- | --- |
| Geographic identity | Do the claimed land/water balance, transitions and named locations have actual fields behind them? Inspect available terrain/biome previews or native samples; prose and climate boxes alone are not a sampled map. |
| Regional distinctness | Are terrain, palette, ecology, resources, architecture functions and encounter roles meaningfully different? Detect both identical recolors and unrelated art directions. |
| Travel and readability | Are provisions, entrances, crossings and navigation cues usable? Record unmeasured travel and unverified placement instead of claiming them as played routes. |
| Progression | Do real kill/delivery objectives bind the intended species/items and beats? Check sources, finite supply, reward consumption, dependencies and encounter access. Coverage is not a playthrough. |
| Budget and repetition | Reconcile the total ledger, per-profile slots, geometry and PNG limits. Inspect variants and density; preserve quiet terrain and horizon contrast. Passing maximums is not a performance benchmark. |
| Completion | Compare the immutable candidate with every named target/link and inspect real geometry/texture outputs. Repair the strongest visible weakness, not only the easiest diagnostic. |

Report evidence levels separately: planned intent; saved draft definitions; actual
rendered assets; validated frozen bundle/`.wspack`; native export/readback;
current-context reload/activation; observed gameplay. Never promote one level into
the next. The progress UI counts named draft declarations, not elapsed effort,
generated map instances or completed player exploration.

## Runtime boundaries

This guide adds planning discipline, not engine capabilities. Current content
targets one local integrated-server world with the existing Overworld dimension
envelope. A larger atlas does not create new dimensions or lift height/asset limits.
Chunk generation uses frozen deterministic content; it does not call an AI to
invent a new region for each explored chunk. Repeating catalogs can support broad
landscapes, but geographic separation, unique capitals, continuous roads and exact
travel times need actual implementation and observation before being promised.

Final delivery follows `world_design`: verify coverage, export the real single-file
`.wspack`, and report resource readiness independently from native publication.
Do not replace a pending native-context result with a success claim or require
foreground/player-world interaction when the user has deferred that verification.
