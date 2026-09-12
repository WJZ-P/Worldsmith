# Named complete-world design and current generation progress

The Mod provides deterministic authoring, persistence and checks; the connected
AI develops the world from the player's prompt. It does not call a hidden LLM.
Use stable names and an explicit plan so a long run can resume without replacing
finished work or quietly dropping a requested category.
Current publication is bundle format 6 with the same nine typed modules; items
schema 2 supplies equipment, consumables and fixed actions. Formats 3/4/5 remain
read-only for restoration and unchanged re-embedding, not implicit migrations.

For a new world-sized request, first use `worldsmith_get_contract` with
`id: "grand_world", section: "world-atlas"` and read the current `authoringBudgets`.
Translate macro geography, regional identity and travel intent into the named
targets below; these are planning decisions, not additional runtime module types.
For an existing session, preserve its plan and continue from actual gaps.

## Choose the scope honestly

`worldsmith_begin_world(prompt, mode, detail)` accepts:

- `COMPLETE_WORLD`: a complete themed world, with a named design plan and actual
  biomes, buildings, blocks, items, creatures, a main line and Boss profiles.
- `WORLDGEN_ONLY`: the backward-compatible guided terrain/biome/feature/architecture
  route. The existing architecture policy still applies; other modules may be empty.
- `STANDALONE`: the requested drawing or creature artifact, without compulsory
  world modules, building groups, quests or native whole-world publication.

Mode defaults to WORLDGEN_ONLY for existing clients. An AI fulfilling a full-world
prompt should explicitly select COMPLETE_WORLD, usually with detail=summary.
An existing COMPLETE_WORLD promise is not silently downgraded to make completion
easier. If the user changes the goal to a focused artifact, begin that explicit scope.

## Design plan schema 1

Call `worldsmith_put_world_design_plan(sessionId, expectedRevision, plan, mode?)`.
The entire plan is saved atomically with the session. Exact fields:

```text
WorldDesignPlan {
  schemaVersion: 1,
  goal: string,
  targets: [{ key: {kind, id}, title: string, purpose: string }],
  links: [{ from: {kind, id}, to: {kind, id}, relation: string }],
  bosses: [{ creature: localCreatureId, quest: localQuestId }]
}
```

`goal` is nonblank, at most 8192 characters. Use 1..512 uniquely named targets;
their titles are 1..160 characters and purposes 1..2048. A complete-world plan
names at least one of every required kind: `biome`, `structure`, `creature`,
`block`, `item`, `quest`, plus at least one Boss (at most 16).
Titles are player-facing names displayed by the Create World progress tab before
the definitions exist. Use readable names in the player's language rather than
copying machine IDs; keep IDs stable when improving a display title.
Keep these titles and any player-visible goal/purpose text rooted in the world
and its gameplay. New manifest/theme/item/quest prose also follows PlayerTextPolicy:
engineering notes about save records, native validation, terrain checks or missing
features belong only in authoring diagnostics/receipts. Repair
PLAYER_TEXT_ENGINEERING_LEAK findings at new publication; do not install a new
loading gate or a display filter on old immutable packs or their saved prose.
This is a minimum coverage check, not a suggested catalog size or a fixed style.
Derive diversity and scale from the prompt. A repeatable test example is not a
catalog to copy into every world.

Targets may also name `terrain`, `anchor`, `feature`, `blueprint`, `theme`, and
`narrative_beat`. Terrain identity is `terrain/main`; blueprint identities are
`structureId/blueprintId`. Items use logical `item/id` ContentKeys, not native
host addresses. A block's pickup form is a `block_item/id` endpoint referring
to the same planned `block/id`.

Proactively choose useful item roles rather than a catalog made only of delivery
tokens. Add weapons, mining tools, wearable armor, consumables and/or composed
fixed abilities when they serve the world's exploration and combat. These are
items schema 2 capabilities under contract/items, not new WorldDesignPlan fields
or a new capability relation. Give each planned item a real producer and a theme
or quest role; tell the player its actual controls in world-appropriate language.

At most 2048 distinct links are allowed. Each substantive endpoint must be a
declared target; existing theme, terrain, anchor, feature and blueprint endpoints
may be implicit. Every complete-world target participates in a promised link.

| relation | from → to | What is checked in actual content |
| --- | --- | --- |
| placed_in_biome | structure → biome | structure.placement.biomes |
| spawns_in_biome | creature → biome | creature.spawn.biomes |
| uses_block | terrain/biome/feature/structure → block | selected material declaration or compiled structure voxel |
| uses_feature | biome → feature | the biome's configured feature references |
| drops_item | creature → item/block_item | positive-chance creature drop entry |
| contains_reward | structure → item/block_item | actual container item or weighted reward in an executable plan |
| contains_encounter | structure → creature | actual typed boss_spawner in an executable plan |
| kill_objective | quest → creature | a real kill_creature objective |
| delivery_objective | quest → item/block_item | a real deliver_item objective |
| quest_reward | quest → item/block_item | a positive one-stack quest reward |
| prerequisite | quest → quest | the first quest actually requires the second |
| theme_anchor | theme/narrative_beat → content | explicit beat content or a quest's themeBeat binding |

The plan does not create these relationships by declaring them. The corresponding
domain documents must actually implement them. Put a custom block first in a
material's preferredIds or use an explicit block field; appending it behind an
already available vanilla candidate is not guaranteed use. An unused blueprint
palette entry does not satisfy a compiled structure's uses_block promise.

Every Boss entry names a planned creature and a planned quest, with the matching
`kill_objective` link. Actual completion requires creature module schema 2, a real
nonempty Boss profile, and a positive natural habitat or typed BossSpawner in an
enabled structure placed in a defined biome. BossSpawner was introduced in format
5 and remains supported in new format-6 bundles with structure/creature module
schema 2; it references a hostile Boss definition.
High health,
a Boss-looking model, or a word in its name is not a Boss profile. This version
does not promise that natural spawning places the Boss inside a named building.
A typed spawner is a repeatable local encounter, not global Boss uniqueness.

## Resume by gaps, not by repeating a universal checklist

1. Read `worldsmith_get_generation_progress(sessionId)` after meaningful changes.
   It returns scope, shared revision, named counts, current missing modules/assets/
   drawing jobs/quests/Bosses, `nextTool`, `nextArguments`, and `nextInstruction`.
2. `nextArguments` contains known arguments, not invented creative documents.
   Fill the listed `requiredAuthoring` fields from the prompt and current plan.
   Contract pointers in begin/resume summaries show exactly where to fetch the
   relevant full domain grammar without repeatedly loading every contract.
3. Commit theme/worldgen/blocks/items/creatures/quests through
   `worldsmith_put_content_modules` with the latest expectedRevision. Textures
   use the provider-independent texture workflow; drawings use the existing SDK,
   source jobs, preview and architecture tools. All edits share this revision.
4. Preserve queued and successful jobs. WAITING_APPROVAL needs the host's user
   action; it is not a reason to submit a replacement job or grant approval yourself.
5. Use `worldsmith_resume_session(sessionId, detail: "summary")` for recovery
   without large geometry documents or job logs. Request full detail only when needed.

Progress checks are cheap draft inspection: they do not repeatedly compile large
drawings, decode every PNG, reload Minecraft, prove aesthetics or claim actual
world placement. Missing referenced texture handles are reported; byte integrity
and real frozen geometry are checked at publication.
The session retains only its latest failed write receipt (at most 32 bounded
diagnostics). At the same revision, progress prioritizes its exact repair path;
after a content edit it is marked stale rather than blocking on old findings.
Successful publication clears it. Inline inputs from a failed attempt are named
but not silently saved: commit corrected documents before retrying.

## Completion boundary

In COMPLETE_WORLD mode, `worldsmith_write_pack` checks the immutable candidate
against every named target, promised relationship and Boss/quest link. Planned
blocks need actual use; items need a producer plus a quest/theme role; planned
creatures need a valid habitat; main-line quests bind real narrative beats.
An empty library does not fill a named category. Keep the existing guided
architecture quality contract, inspect the actual visuals, and repair the largest
visible weakness rather than treating machine checks as a beauty score.

Choose the signature item/block for the browser through the write request's
optional `representativeContent: {"kind":"item","id":"existing_local_id"}`
(kind may be block). It is manifest.representativeContent, not a design-plan
field. Reuse that content's actual PNG rather than producing another generic
world thumbnail; browsing the preview does not activate or reload its world.

The write records the actual frozen input snapshot back into the same session and
automatically exports the final single-file resource pack to
`resource-packs/exports/<bundleId>.wspack`. Its reply includes `resourcePackReady:true`
and a `resourcePack` receipt with the actual path, archive SHA/size and bundle identity;
deliver that file, not only the internal bundle directory. `resourcePackFilename`
is an optional plain `.wspack` name on both write_pack and finish_world. Follow the
returned nextArguments so a chosen custom name is reused by finish without adding
archive state to the draft. Repeating the same export is idempotent.

An archive-name conflict or export I/O failure preserves the existing file and the
already-frozen Core pack/session. `resourcePackReady:false` plus
`resourcePackError.code: "RESOURCE_PACK_EXPORT_FAILED"` is not a module/texture failure:
follow its `worldsmith_export_resource_pack` retry arguments with an unused filename,
then its continueArguments for finish. Do not rebuild drawings, assets or modules.
Finish ensures/returns the archive before requesting native publication; an archive
failure blocks that request and is not hidden by a native completion flag.

If identical content already exists, its stored display metadata is reported
truthfully instead of claiming a requested rename was written. `finish_world`
checks coverage again, ensures the same `.wspack`, then asks for the native publication
receipt. `completeWorldCoverageVerified`, `resourcePackReady`, and native
`complete`/activation are independent: the resource file can be ready while
WAITING_NATIVE_CONTEXT still needs a player action. A saved
pack, model preview, configured habitat, or content plan is not a created/played
world or a guarantee of particular structure instances.

Design the main line so earlier obtainable materials and quest rewards support
later deliveries. Do not require an item produced only by the very task that
demands its delivery. The player actively submits items in installments and
claims completed rewards separately; quest prose does not consume inventory.
Earlier quest rewards and fixed/line-anchor structure supplies are finite and
delivery consumes them. Mutually exclusive structure variants contribute their
maximum individual supply, not their sum. This static bound rejects impossible
deliveries; it does not guarantee a random loot roll or an actual generated site.
