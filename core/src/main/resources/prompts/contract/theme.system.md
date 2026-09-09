# Worldsmith unified world theme contract — module schema 1, bundle format 3

Design one world from the player's prompt, not six unrelated content catalogs.
The theme is persistent creative intent: a premise, a player role, world rules,
one main conflict, and named narrative beats anchored to real world content.
It is not an executable quest state machine or an achievement definition.

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

Each content link is exactly `{ "kind": "...", "id": "..." }` and resolves
inside this world. Use real `terrain/main`, anchor, biome, feature, structure,
blueprint, drawing, block, block_item or creature definitions. Blueprint keys
are `structureId/blueprintId`; creature and block keys use local logical IDs,
not native registry addresses. Theme and narrative_beat self-links do not count
as concrete content anchors. Missing references are publication errors.

Choose distinct ecological, material, settlement and creature consequences of
the premise, then link the beats to the resulting definitions. Theme links
describe intended exploration; they do not create objectives, rewards, dialogue,
scripted triggers, or progression locks. `quests` and `achievements` are future
modules and are rejected today. Do not invent their fields in this document.

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
   current shared `revision`, modules and available assets.
2. Design stable logical IDs across the theme, terrain, biomes, features, blocks,
   creatures and architecture before constructing cross-links.
3. Create a real PNG using
   `worldsmith_create_pixel_texture(sessionId, expectedRevision, palette, rows)`,
   or upload an existing PNG with
   `worldsmith_put_texture_asset(sessionId, expectedRevision, pngBase64)`.
   Read the returned digest and revision. Pixel-grid authoring is deterministic
   image construction, not evidence that an image-generation model ran.
4. Save coordinated domain changes atomically with
   `worldsmith_put_content_modules(sessionId, expectedRevision, modules)`.
   Its `modules` accepts `theme`, `blocks`, `creatures`, `terrain`, `biomes`, and
   `features`. Use exact typed domain documents. Structures retain their existing
   drawing/architecture/put_structure tools; do not put structures in this call.
5. All writes share one revision. After any write, use its returned revision;
   after a conflict, reread the draft, preserve other changes, and merge before
   retrying. Avoid parallel stale-revision writes to the same session.
6. Run content/link checks and domain previews. Repair missing references and
   rejected capabilities rather than substituting unrelated vanilla content.
7. `worldsmith_write_pack` accepts `theme`, `blocks`, and `creatures` inline or
   uses their session module drafts. Publication binds all session assets to
   the frozen format-3 pack; assets must exist and their bytes must validate.
   Empty block/creature libraries are valid when intentional. A successful
   draft write is not a claim of native preparation or actual world activation.

Use `worldsmith:content/<blockId>` without properties in existing material or
structure fields that refer to custom blocks. Source never chooses native host
slots or raw `worldsmith:content/block/...` addresses. The world binding owns them.
