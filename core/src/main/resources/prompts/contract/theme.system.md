# Worldsmith unified world theme contract — module schema 1, bundle format 7

Design one world from the player's prompt, not unrelated content catalogs.
For new COMPLETE_WORLD authoring, the session's WorldBible is the single source
of setting facts. Theme is its bounded player-facing runtime projection: a
premise, player role, rules, conflict, and named beats anchored to real content.
Read `world_bible` and `module_briefs` first. The AI derives this exact schema,
records its owning brief and reviews the actual projection against current
setting/content digests. Do not maintain contradictory facts in two documents.
Legacy and lightweight sessions retain their existing direct-theme workflow.
It is not an executable quest state machine or an achievement definition.
Current publications use bundle format 7 with ten typed modules: `theme`,
`terrain`, `features`, `biomes`, `structures`, `blocks`, `creatures`, `items`,
`quests`, and `mechanics`. Older bundle formats are rejected.
Local packs and saves are not automatically migrated or rewritten.
Theme remains schema 1; items schema 2 adds equipment, consumables and fixed
actions without introducing another theme field or another module.

## Exact document

Submit a `WorldTheme` object under the `theme` module:

```json
{
  "schemaVersion": 1,
  "id": "main",
  "title": "A title specific to this world",
  "premise": "The world's central situation and history",
  "playerRole": "Who the player is within that situation",
  "worldRules": ["One concrete rule shaping ecology, materials and societies"],
  "mainConflict": "The tension that gives exploration a direction",
  "beats": [{
    "id": "arrival",
    "title": "Read the land",
    "description": "What the player can discover and how the place expresses it",
    "content": [{"kind": "terrain", "id": "main"}]
  }]
}
```

This demonstrates fields, not reusable story text. Replace its creative text
with the player's world. `id` and beat IDs use `[a-z0-9][a-z0-9_.-]{0,63}`;
beat IDs are unique. `title` is nonblank and at most 160 characters; `premise`,
`playerRole`, and `mainConflict` are each nonblank and at most 4096 characters.
`worldRules` contains 1..32 nonblank strings, each at most 1024 characters.
`beats` contains 1..64 entries; each has a nonblank title (160 characters maximum),
nonblank description (4096 maximum), and 1..64 distinct `content` links.

## Player-facing story, author-facing diagnostics

All theme titles, premise, playerRole, mainConflict, worldRules and beat text
describe the world's history, ecology, people or actual play. The arrival HUD
draws background from the verified theme and objectives from saved quest progress;
write concise, evocative prose that still makes sense in the player's world.
Never append engineering notes about save progress, generated instances, terrain
validation, implementation gaps, snapshots, native preparation or tool output.
Technical constraints belong in authoring diagnostics/receipts, not theme prose.
Keep gameplay claims truthful by writing what the player can actually do instead
of making a larger promise and appending an implementation disclaimer.

`PlayerTextPolicy` checks new authoring publication and reports
`PLAYER_TEXT_ENGINEERING_LEAK` on known leaks; the author repairs that field.
This is not a display-time rewriting rule or an added old-pack loading gate.
Existing immutable packs, embedded saves and their original prose stay unchanged.

Each content link is exactly `{ "kind": "...", "id": "..." }` and resolves
inside this world. Use real `terrain/main`, anchor, biome, feature, structure,
blueprint, drawing, block, block_item, item, creature or quest definitions. Blueprint keys
are `structureId/blueprintId`; creature and block keys use local logical IDs,
not native registry addresses. Theme and narrative_beat self-links do not count
as concrete content anchors. Missing references are publication errors.

Choose distinct ecological, material, settlement and creature consequences of
the premise, then link the beats to the resulting definitions. Theme links
describe intended exploration; they do not create objectives, rewards, dialogue,
scripted triggers, or progression locks. The `quests` module is installed as one
bounded linear main line with `kill_creature` and `deliver_item` objectives,
explicit delivery and reward claiming; read contract/quests for its own fields.
Existing quests project into native world-specific advancements after reward claims.
`achievements` is not installed as a separately authored module. Do not invent quest or achievement fields in the
theme document: link an existing quest or use that quest's `themeBeat` instead.

Give useful items real capabilities through items schema 2 when they support the
theme: a weapon, mining tool, wearable armor, consumable or composed USE/MELEE_HIT
effects. Read contract/items rather than inventing a magic, equipment or script
field in WorldTheme. Link the actual item and a real acquisition route.

## Relationship to architecture

The shared theme informs architecture's existing `worldTheme`, `themeFit`,
`layoutIntent`, and `distinction`; keep their meaning consistent. Architecture
groups, blueprints and layout constraints belong to the architecture/structure
contracts, not to the theme document. A narrative beat is neither a building
group nor a requirement to copy the same landmark into every world. When using
an architecture plan, satisfy that plan's own contract and visual review loop.

## Atomic shared authoring workflow

1. Begin or reuse a world session. Read `worldsmith_get_content_contract` with
   `module: "theme"` and read `worldsmith_get_content_draft(sessionId)` for the
   current shared `revision`, modules and available assets. For new complete
   worlds, first save/review the bible, then establish the plan and owning briefs.
2. Design stable logical IDs across the theme, terrain, biomes, features, blocks,
   creatures, items, quests, mechanics and architecture before constructing cross-links.
3. Create a real PNG using
   `worldsmith_create_pixel_texture(sessionId, expectedRevision, palette, rows)`,
   or upload an existing PNG with
   `worldsmith_put_texture_asset(sessionId, expectedRevision, pngBase64)`.
   Read the returned digest and revision. Pixel-grid authoring is deterministic
   image construction, not evidence that an image-generation model ran.
4. Save coordinated domain changes atomically with
   `worldsmith_put_content_modules(sessionId, expectedRevision, modules)`.
   Its `modules` accepts `theme`, `blocks`, `creatures`, `items`, `quests`, `terrain`,
   `biomes`, and `features`. Use exact typed domain documents. Structures retain their existing
   drawing/architecture/put_structure tools; do not put structures in this call.
5. All writes share one revision. After any write, use its returned revision;
   after a conflict, reread the draft, preserve other changes, and merge before
   retrying. Avoid parallel stale-revision writes to the same session.
6. Run content/link checks and domain previews. Repair missing references and
   rejected capabilities rather than substituting unrelated vanilla content.
   For the new workflow read the current review context for theme's brief and
   submit `worldsmith_review_world_alignment`: check that the premise, rules,
   player role and beats preserve the bible's claims and actual scope. A theme
   link resolving is engineering evidence, not proof of semantic consistency.
7. `worldsmith_write_pack` accepts `theme`, `blocks`, `creatures`, `items`, and `quests` inline or
   uses their session module drafts. Publication binds all session assets to
   the frozen format-7 pack; assets must exist and their bytes must validate.
   Empty optional libraries are valid only when they satisfy the session mode and
   its named complete-world coverage promises. A successful
   draft write is not a claim of native preparation or actual world activation.

When the theme needs a new setting fact, update the structured WorldBible first,
review its new revision, then update the derived theme and affected briefs.
The readable bible Markdown is generated, never edited as a parallel source.
Keep full authoring evidence in the session; no new fields are added to this
runtime schema or to the existing `.wspack` format.

Choose a signature existing item or block with the write request's optional
`representativeContent: {"kind":"item","id":"local_item_id"}` (or kind=block).
It is manifest display metadata, not a theme field or another generated image.
The world's browser card reuses that content's real PNG without world activation.

Use `worldsmith:content/<blockId>` without properties in existing material or
structure fields that refer to custom blocks. Source never chooses native host
slots or raw `worldsmith:content/block/...` addresses. The world binding owns them.
