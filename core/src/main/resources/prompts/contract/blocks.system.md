# Worldsmith custom block contract — module schema 1

Design materials that express the world's theme in terrain, settlements and
landmarks. This module creates bounded full-cube block definitions with PNG
textures and stable per-world bindings, not arbitrary executable block classes.

## Exact document

```json
{
  "schemaVersion": 1,
  "blocks": [{
    "id": "moonstone",
    "displayName": "Moonstone",
    "profile": "STONE",
    "textureAsset": "<actual SHA-256 returned by the texture tool>",
    "light": 8,
    "themeRole": "A world-specific material and its geological or cultural role"
  }]
}
```

Replace the texture marker with a returned digest; a fabricated 64-digit string
does not create an asset. `blocks` may be empty. IDs are unique lowercase local
names matching `[a-z0-9][a-z0-9_.-]{0,63}`. Display names have 1..128 printable
characters. `themeRole` is optional and at most 2048 characters.

`profile` is exactly `STONE`, `WOOD`, `METAL`, or `GLASS` (default STONE), with at
most 32 definitions per profile. These are predeclared native physical presets;
do not add hardness, arbitrary collision shapes, machines, fluids, inventories,
procedural tick code or custom state-property fields. `light` is an integer
0..15, default 0. Profile and light are part of the stable save binding, not
values to change underneath already stored blocks.

`textureAsset` is the actual lowercase SHA-256 of a verified PNG. Its asset ID
must equal its byte digest. The PNG is square, power-of-two, 16..256 pixels on
each edge, at most 1 MiB encoded. Use GLASS for translucent designs; other profiles
retain full-cube occlusion/culling semantics and do not promise arbitrary alpha
visuals. A single texture currently covers all cube faces; this is not a model
asset or a six-face texture dictionary.

## Texture authoring and logical use

1. Read `worldsmith_get_content_contract(module: "blocks")` and
   `worldsmith_get_content_draft(sessionId)` to obtain the shared revision.
2. Create a texture using
   `worldsmith_create_pixel_texture(sessionId, expectedRevision, palette, rows)`.
   Palette: 1..256 exact `#RRGGBB` or `#RRGGBBAA` strings (last pair is alpha).
   Rows: a rectangular two-dimensional array of zero-based palette indices,
   1..256 pixels wide and high. For block use, choose 16/32/64/128/256 square
   dimensions. Programmatic pixel grids are not AI-generated concept images.
   Alternatively call
   `worldsmith_put_texture_asset(sessionId, expectedRevision, pngBase64)` with
   raw standard base64 of an actual PNG, without a data URL or whitespace.
3. Inspect the returned image, use its returned asset ID, and advance to the
   returned revision. Improve deliberate material motifs rather than random
   noise, and consider how all six adjacent faces will meet in terrain/buildings.
4. Save with `worldsmith_put_content_modules(sessionId, expectedRevision,
   modules: {"blocks": <CustomBlockLibrary>, ...})`. Related theme/terrain/biome/
   feature/creature changes can be committed in the same modules object.
   Structures continue through the existing architecture and structure tools.
5. In material `preferredIds`, explicit block fields and structure geometry use
   `worldsmith:content/moonstone`. Do not append `[light=...]` or other properties;
   the definition supplies immutable light. Do not use raw host-slot IDs such as
   `worldsmith:content/block/stone/00` in authored content.
6. Link the material in theme beats with `{"kind":"block","id":"moonstone"}`;
   use the same logical definition in landscape and construction. Read fresh
   draft state after revision conflicts instead of overwriting other modules.
7. `worldsmith_write_pack` accepts inline `blocks` or the session's block module,
   and binds the session's verified assets into the frozen pack. Publish only
   after content references, asset bytes and native capacity checks succeed.

Generic texture tools also support other dimensions and larger PNGs for other
uses; their success does not override this block module's stricter contract.
