# Creature authoring foundation

New publications use bundle format 7 with required mechanics. Older bundle formats are rejected; domain schema versions below are not bundle-format compatibility paths.

The creature authoring layer compiles data into runtime schema 1, 2 or 3. Ordinary
recipes default to schema 1; explicit schema 2 adds the installed bounded Boss
profile and 2..3 melee phases. Schema 3 adds vanilla sound selection and modulation
for both ordinary creatures and Bosses. A Boss-looking model alone remains ordinary behavior.
Recipes are data, not Java tick code or an arbitrary gameplay interpreter. World
placement is a separate natural habitat or typed structure encounter declaration.

## Workflow

1. Begin/resume a world session. Read this contract and the `creatures` content contract.
2. Call `worldsmith_build_creature` with `sessionId` and a `recipe`.
3. Omit `textureAsset` during geometry work: the build returns a real diagnostic UV
   guide and a runtime definition whose texture hash identifies that guide. The guide
   is NOT automatically attached to the world's assets. `textureGuideOnly=true` means
   this is not the painted skin and `readyForContentDraft=false`.
4. Use `worldsmith_preview_creature` with its `buildId`. Inspect opposite views,
   the UV debug view, and pose sheets. These are offline previews, not game screenshots.
5. Paint an atlas preserving every face rectangle and upload the actual PNG through
   `worldsmith_put_texture_asset` at the current shared draft revision.
6. Rebuild the same recipe with the returned `textureAsset`. Its PNG width/height must
   exactly match atlasWidth/atlasHeight. Changing the recipe rebuilds UVs, so recheck the
   skin when sizes, cube IDs, or packing change.
7. Merge the returned definition into the current `CreatureLibrary` using
   `worldsmith_put_content_modules` at expectedRevision. Preserve other species;
   use a CreatureLibrary.schemaVersion at least as high as every definition
   runtimeSchema (sounds=3, Boss without sounds=2, ordinary without sounds=1).
   Never downgrade an existing schema-3 envelope when merging an ordinary creature.
8. Publish with the normal whole-bundle write/finish flow. A build artifact is not
   an active world or an automatically committed creature draft.

Builds are immutable and scoped to the authoring session, including their verified
texture bytes. `worldsmith_get_creature_build` restores recipe, definition and UV map
without executing source. At most 128 builds are retained per session; no old build
or painted asset is silently overwritten or evicted.

## Vanilla-inspired skin direction

Reference Minecraft's vanilla mob pixel-art language: readable silhouette, clean
large color regions, a limited palette and deliberate facial/species landmarks.
Default fur, hide and cloth are mostly calm surfaces, not a full-body layer of
random speckles, checkerboards or dithering. Shade with coherent pixel clusters
and a few purposeful value steps instead of independent bright/dark dots on every
face. Use spots, stripes, scales and weathering only when the species or world
identity calls for them, confined to meaningful regions. Do not copy one noisy
material function across every species. This direction applies equally to image
providers and deterministic pixel recipes; no hosted image model is required.
Keep the compiled UV faces and orientation exact, then inspect front/back/side and
pose views for matching markings, a clear face and readability at game distance.

## Recipe fields

```json
{
  "schemaVersion": 1,
  "id": "stone_guardian",
  "displayName": "Stone Guardian",
  "category": "HOSTILE",
  "atlasWidth": 128,
  "atlasHeight": 128,
  "padding": 2,
  "themeRole": "Guards the ruined observatory",
  "bones": [
    {"id":"root","pivot":{"x":0,"y":24,"z":0},"cubes":[]},
    {"id":"torso","parent":"root","pivot":{"x":0,"y":-20,"z":0},
      "cubes":[{"id":"body","origin":{"x":-6,"y":-12,"z":-4},
        "size":{"x":12,"y":16,"z":8},"materialRole":"stone"}]},
    {"id":"leftArm","parent":"torso","pivot":{"x":8,"y":-10,"z":0},
      "role":"ARM_LEFT","cubes":[{"id":"arm","origin":{"x":-2,"y":0,"z":-2},
        "size":{"x":4,"y":14,"z":4},"materialRole":"stone"}]}
  ],
  "mirrors":[{"sourceRoot":"leftArm","targetRoot":"rightArm","shareUv":true}]
}
```

Optional `attributes`, `behavior`, `spawn`, `drops`, `boss`, and `sounds` are exactly the fields
from the creature runtime contract; they do not become new behavior scripts. A
non-null `boss` requires recipe.schemaVersion=2 or 3; non-null `sounds` requires
recipe.schemaVersion=3. Keep sounds, boss and drops when
rebuilding a textured model. Defaults are
provided by the current runtime DTOs. The illustrated model is a field-shape example,
not a complete art-directed creature.

Read `worldsmith_get_content_contract(module:"creatures")` for the exact
CreatureBossProfile grammar, health thresholds, native attribute bounds, habitat
rules and a two-phase example. Authoring validates the profile; it does not invent
phases from the name or increase the number of supported mechanics. Schema-2 Boss
publication requires bundle format 5. A landmark can use the drawing contract's
`boss-encounters` section after the actual Boss definition exists.

- Bone: `id`, optional `parent`, `pivot`, `rotation`, `role`, `gaitPhase`, `cubes`.
- Cube: `id`, `origin`, integer `size`, optional `materialRole` and `mirror`.
- Mirror: existing `sourceRoot`, new `targetRoot`, and `shareUv` (default true).
- Positions and rotations use the native creature convention: one unit is 1/16 block,
  X right, Y down, Z back, root Y=24 ground; rotations and gaitPhase are degrees.
- Reflection is across the source subtree parent's X=0 plane; left/right animation
  roles are swapped, descendants are cloned, and optional shared UVs use native
  mirrored-cube semantics. It is not an arbitrary world-space mesh reflection.
- Model bounds remain 64 bones / 256 cubes / depth 16. Automatic UV packing reserves
  padding between box islands and reports capacity errors rather than overlapping them.
- A box occupies `2*(size.x+size.z)` by `size.y+size.z` texels. The returned layout lists
  all six face rectangles and their material roles. UVs are not an arbitrary concept image.

## Preview

`worldsmith_preview_creature(sessionId,buildId,mode?,view?,pose?,bossPhase?)`

- `mode=model` (default): one actual textured model image.
- `mode=sheet`: multiple views and procedural poses for side-by-side inspection.
- `mode=uv`: the real PNG with UV coverage/debug overlay.
- Views: `isometric`, `isometric_back`, `front`, `back`, `left`, `right`, `top`.
- Poses: `idle`, `walk`, `windup`, `strike`, `recovery`.
- `bossPhase`: zero-based phase index, default 0. A two-phase Boss permits 0/1;
  a three-phase Boss permits 0/1/2; an ordinary creature permits only 0.
  Model/sheet views render that phase's poseIntensity through the shared native
  pose evaluator; UV mode still shows the atlas rather than a combat state.

Example: `worldsmith_preview_creature(sessionId,buildId,mode:"sheet",bossPhase:1)`.
The field is exactly `bossPhase`, not `previewBossPhase`. Compare every phase and
use matching views; the camera bounds include all authored phases for stable framing.

Native rendering and offline preview use the same pure pose evaluator. Preview is
bounded software rasterization with box UVs and visibility, not a native light engine
or proof that navigation, combat or encounter pacing is good.

## Java-friendly library

`CreatureBuilder.create(id,displayName,category)` creates a mutable authoring builder.
Use `.atlas(width,height,padding)`, `.bone(...).cube(...).end()`, `.mirrorSubtree(...)`,
optional `.boss(CreatureBossProfile)` (automatically selects recipe schema 2), then
`.recipe()`, `.guide()` or `.build(textureSha256)`. The result remains typed runtime
data. The optional local CLI/sample is for developers; MCP requires no local
Java compilation by its caller and never executes a recipe's text as code.


## Sound authoring

Every new species should include deliberate sounds and recipe schemaVersion=3.
Read the creatures contract for exact fields; this tool also returns soundVocabulary
(allowed aliases to real vanilla IDs) and soundVoices (default role mappings).
Select a plausible vanilla voice, restrained volume and body-appropriate pitch,
then optional per-role overrides. Keep sounds when rebuilding with painted textures;
it is independent of UVs and needs no audio asset. Offline model previews preserve
sound metadata but do not audition audio. Builder.sounds(CreatureSoundProfile)
automatically selects schema 3, including recipes that also contain a Boss profile.
