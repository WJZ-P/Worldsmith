# Portable texture production: item / block / creature

Worldsmith provides PNG authoring/import, validation, content addressing and model
preview. It does NOT contain or promise access to a hosted text-to-image model.
An MCP client may use any image provider it has access to, or the model-independent
pixel recipe compiler. A different AI does not need Codex or its image tool.

## Target differences

- Ordinary item: square power-of-two PNG, 16..256 pixels, <=1 MiB. Usually a clear
  icon on transparent background. Bind the hash to CustomItemDefinition.textureAsset.
- Block: square power-of-two PNG, 16..256 pixels, <=1 MiB. Design for repetition.
  The current cube_all profile uses ONE tile for all six faces; per-face skins,
  PBR/normal maps and animated PNGs are not installed. Use GLASS for transparency.
- Creature: an actual UV atlas matching the model's exact declared width/height
  (powers of two 16..512). Generate the bones/cubes/UV guide first. A concept image
  is not a valid replacement for the unwrapped atlas. Paint the same face rectangles,
  bind the final PNG hash and inspect the actual model and poses.

## Route A: no image model needed

`worldsmith_build_texture(sessionId, expectedRevision, recipe)` compiles a small
reusable data recipe to a real PNG and attaches it to the session. It uses no AI
service, network call, executable source, shell command or vendor-specific SDK.

Recipe fields: schemaVersion=1, width/height (1..512), palette (1..256 exact
#RRGGBB or #RRGGBBAA colors), seed (integer), operations (1..256).
Canvas starts transparent. Operations paint in order, with a total bounded pixel
work budget. Reusing the same data/seed reproduces the same pixel design.

Supported operation kinds:

- `fill`: x/y/width/height rectangle, `color` palette index. Omitted width/height
  mean the whole canvas dimensions (so a shifted whole-canvas fill is an error).
- `noise`: rectangle, nonempty `colors` palette indices, finite probability 0..1.
  Unselected pixels preserve the underlying layer. Randomness uses the recipe seed.
- `checker`: rectangle, exactly two `colors`, positive `cellSize`.
- `line`: x/y through x2/y2 inclusive, `color`, one-pixel Bresenham line.
- `stamp`: x/y, same-width text `rows`, `glyphs` mapping one character to a palette
  index, optional integer scale=1..8. A dot `.` preserves the underlying pixel.

Unknown operations, out-of-bounds writes, invalid indices and excessive work are
errors, not silently clipped images. This is an explicit pixel-art tool, not a
claim of automatic artistic quality or sophisticated material synthesis.

## Route B: external painting or image generation

1. Call `worldsmith_get_texture_workflow` for the configured `textureInbox` path.
2. Have a same-host file-capable client/tool put a PNG inside that dedicated folder,
   e.g. `amethyst-heart.png`. Do not ask the language model to transcribe image bytes.
3. Call `worldsmith_import_texture_file(sessionId,expectedRevision,filename)`.
   It accepts one plain filename, not an arbitrary path, URL, directory or symlink.
   The source file stays untouched; only verified bytes enter immutable asset storage.
4. If the image provider returned a larger raster, explicitly supply fitWidth,
   fitHeight and resample="nearest" to resize the WHOLE atlas by pixel centers.
   Default resample="none" never silently resizes a mismatched image. No crop or UV
   re-layout is implied. The result reports source/output hashes and dimensions.

For a client on a different host, arrange file transfer or call the existing
`worldsmith_put_texture_asset(..., pngBase64)` with machine-supplied PNG bytes.
The local inbox is not magically accessible from a cloud client, and the mod never
fetches arbitrary remote image URLs. Base64 is transport, not something an AI should
manually calculate or copy into a long generated response.

## Binding and review

All import/build operations use the shared draft revision. Use the returned real
SHA-256 in items/blocks/creature models, then run the normal content linking and
publication flow. PNG validity does not imply correct UVs, attractive artwork,
tileable block edges or completed native resource activation.

`worldsmith_preview_texture_asset` returns the actual image. Creature builds use
`worldsmith_preview_creature` for geometry/UV/pose inspection. A vision-capable client
or a person can judge the visible result; text-only clients can still inspect the
metadata but should not claim they visually reviewed an image.

## The existing Darkstar Gatekeeper example

That skin was painted with the assistant session's external image-generation tool
using Worldsmith's exact UV guide, then imported from its original 1254x1254 PNG to
256x256 and bound by its actual hash. The displayed result was rendered from the
real model plus skin, not an image-model imitation of a game screenshot. The original
PNG, final atlas, recipe, UV layout and model remain reusable files in the repository.
This external image tool is not a required or bundled dependency of the mod.
