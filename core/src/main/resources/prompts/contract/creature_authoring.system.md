# Creature authoring foundation

The creature authoring layer compiles data into the existing runtime schema 1.
It is not a boss encounter designer, a Java execution tool, or a new gameplay schema.
A creature may look like a boss while still using the existing grounded behavior host.

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
   `worldsmith_put_content_modules` at expectedRevision. Preserve other species.
8. Publish with the normal whole-bundle write/finish flow. A build artifact is not
   an active world or an automatically committed creature draft.

Builds are immutable and scoped to the authoring session, including their verified
texture bytes. `worldsmith_get_creature_build` restores recipe, definition and UV map
without executing source. At most 128 builds are retained per session; no old build
or painted asset is silently overwritten or evicted.

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

Optional `attributes`, `behavior`, and `spawn` are exactly the fields from the
creature runtime contract; they do not become new behavior scripts. Defaults are
provided by the current runtime DTOs. The illustrated model is a field-shape example,
not a complete art-directed creature.

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

`worldsmith_preview_creature(sessionId,buildId,mode?,view?,pose?)`

- `mode=model` (default): one actual textured model image.
- `mode=sheet`: multiple views and procedural poses for side-by-side inspection.
- `mode=uv`: the real PNG with UV coverage/debug overlay.
- Views: `isometric`, `isometric_back`, `front`, `back`, `left`, `right`, `top`.
- Poses: `idle`, `walk`, `windup`, `strike`, `recovery`.

Native rendering and offline preview use the same pure pose evaluator. Preview is
bounded software rasterization with box UVs and visibility, not a native light engine
or proof that navigation, combat or encounter pacing is good.

## Java-friendly library

`CreatureBuilder.create(id,displayName,category)` creates a mutable authoring builder.
Use `.atlas(width,height,padding)`, `.bone(...).cube(...).end()`, `.mirrorSubtree(...)`,
then `.recipe()`, `.guide()` or `.build(textureSha256)`. The result remains ordinary
runtime data. The optional local CLI/sample is for developers; MCP requires no local
Java compilation by its caller and never executes a recipe's text as code.
