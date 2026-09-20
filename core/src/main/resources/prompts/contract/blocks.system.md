# Worldsmith custom block contract — module schema 2

Create recognizable full-cube materials with canonical six-face appearances and
stable per-world native bindings. Bundle format 10 uses this current schema only.
The appearance is not an executable model, arbitrary collision shape or block entity.

## Exact document

```json
{
  "schemaVersion": 2,
  "blocks": [{
    "id": "hearth_waystone",
    "displayName": "归路刻石",
    "profile": "STONE",
    "appearance": {
      "up":    {"textureAsset": "<actual top PNG SHA-256>", "quarterTurns": 0},
      "down":  {"textureAsset": "<actual base PNG SHA-256>", "quarterTurns": 0},
      "north": {"textureAsset": "<actual marked-front PNG SHA-256>", "quarterTurns": 0},
      "south": {"textureAsset": "<actual back PNG SHA-256>", "quarterTurns": 0},
      "west":  {"textureAsset": "<actual side PNG SHA-256>", "quarterTurns": 0},
      "east":  {"textureAsset": "<actual side PNG SHA-256>", "quarterTurns": 0},
      "particle": "<actual particle PNG SHA-256>",
      "orientation": "HORIZONTAL"
    },
    "light": 0,
    "themeRole": "A front-facing route mark with the village's shared copper-frame motif"
  }]
}
```

Replace each marker with a returned real digest. Faces may reference the same PNG;
every face and particle reference is validated, included in bundle identity and
exported. There is no block-level `textureAsset` field. The code helper
`BlockAppearance.uniform(hash)` only constructs all six ordinary face entries and
particle from one hash; it does not select a second legacy representation.

## Fields and boundaries

- IDs are unique local lowercase names matching `[a-z0-9][a-z0-9_.-]{0,63}`.
  Names need 1..128 printable characters; themeRole supports at most 2048.
- Profile is STONE, WOOD, METAL or GLASS, with at most 32 definitions per profile.
  Physical strength, sounds and full-cube collision come from these native presets.
- Every PNG is content-addressed, square power-of-two, 16..256 pixels, at most 1 MiB.
  Use GLASS for translucency; other profiles retain opaque full-cube occlusion.
- Each face has `textureAsset` and optional `quarterTurns` 0..3. The turns rotate UVs
  clockwise within the native cube face plane, not the entire block in the world.
- `particle` supplies break/placement particles independently of the marked front.
- `orientation` is FIXED (default) or HORIZONTAL. Local north is the painted front.
  HORIZONTAL placement points north/front toward the player; structure mirror and
  quarter-turn rotation transform that facing. FIXED materials retain world axes.
- Light is an integer 0..15. Profile, light and orientation belong to the stable
  native binding. Do not mutate these under an already stored bound block.
  Use distinct logical definitions for actual physical states such as a closed dark
  lamp and an opened glowing lamp, then change them through a mechanic/projection.

## Authoring and actual review

1. Read `worldsmith_get_content_draft` for the current shared revision.
2. Use `worldsmith_build_texture`, `worldsmith_create_pixel_texture` or the explicit
   PNG import tools. Design material roles: wood end grain versus bark, a marked
   front versus plain backing, an opening versus closed shutters. Avoid six random
   noisy pictures that have no common material vocabulary.
3. Commit the entire block module with `worldsmith_put_content_modules` at the
   returned expectedRevision. Preserve unrelated draft blocks and modules.
4. Call `worldsmith_preview_content_appearance(sessionId, kind:"block", ids:[id])`.
   Inspect real-PNG front/back cubes, six local planes, 3x3 tiling and small-scale
   samples. The response is an offline authoring sheet, not a Minecraft screenshot;
   edge-delta and alpha metrics are not an automatic aesthetic-quality verdict.
5. In material selectors and geometry use `worldsmith:content/<id>`, never native
   slot ids. For a HORIZONTAL block, structured BuildMaterial/MechanicBlockPredicate
   properties may be exactly `{"facing":"north|east|south|west"}`. FIXED blocks
   accept no authored properties. Never append inline `[light=...]`, override
   `light`/`oriented`, or refer to `worldsmith:content/block/...` native slots.
6. Theme beats link `{"kind":"block","id":"..."}`; terrain, architecture and
   interaction effects should use the same coherent local definitions.
7. Publish only after all face/particle bytes, references and capacity validate.
   Native resource reload and gameplay screenshots remain separate verification.
