# Creature authoring builder example

`DarkstarGatekeeper.java` is a reusable Java authoring source for **黯星守门者**:
an approximately five-block-tall dark-stone guardian, with a visible amethyst
chest core, bronze bindings, paired horns and shoulder armor. Its definition uses
the current humanoid ground runtime and ordinary melee attributes; this is an
appearance example, not a new Boss encounter or combat-phase system.

## Compile the model and fixed UV layout

From the repository, with the configured JDK:

```powershell
.\gradlew.bat -p build/structure-efficiency-validation :core:creatureAuthoring --console=plain
```

This compiles source and emits `recipe.json`, `creature.json`, `creatures.json`,
`uv-layout.json`, `uv-guide.png`, `guide-asset.json`, and `authoring-status.json`
under `build/creature-authoring/darkstar-gatekeeper`. It runs no regression tests.
Override `-PrecipeFile=<absolute source.java or recipe.json>` and
`-PoutputDir=<absolute output directory>` as needed.

The guide is a real content-addressed PNG used only to explain UV placement.
`guideOnly: true` and `CREATURE_TEXTURE_GUIDE_ONLY` distinguish it from a finished
painted skin. A guide is not automatically attached to a world's asset collection.

After producing a real painted 256x256 PNG matching this layout:

```powershell
.\gradlew.bat -p build/structure-efficiency-validation :core:creatureAuthoring `
  -PrecipeFile=<absolute recipe.json> -PtextureFile=<absolute painted.png> `
  -PoutputDir=<absolute output directory> --console=plain
```

The CLI verifies PNG bytes and dimensions, emits `texture.png` and its portable
descriptor, and binds the current `CreatureDefinition` to its actual digest. It
does not resize or repaint an imported image. A larger generated image must be
explicitly imported at the target atlas size before this step.

## Builder and data interface

Use `CreatureBuilder.create(id, displayName, category)`, then `atlas`, `attributes`,
`behavior`, `spawn`, and `themeRole` as desired. `bone(id,parent,x,y,z)` returns a
fluent bone builder with `rotation`, `role`, `gaitPhase`, and named `cube` calls;
`end()` returns the creature builder. `bone(id)` selects an existing authored bone.

`mirrorSubtree(sourceRoot,targetRoot,shareUv=true)` reflects a limb through X=0
in its parent's local frame. It mirrors the entire descendant hierarchy, swaps
left/right limb animation roles, and uses the native cube mirror flag. Child IDs
beginning with the source root keep their suffix under the new root; other child
IDs become `targetRoot_originalChildId`. Mirrored islands can share the exact
source UV rectangles or receive independent texture space.

`recipe()` returns serializable `CreatureRecipe` data. The version-independent
`CreatureAuthoring.compile(recipe, actualTextureSha256)` produces
`AuthoredCreature(definition, uvLayout)`. `CreatureAuthoring.guide(recipe)` returns
the definition plus real guide PNG, descriptor and explicit guide-only diagnostic.
MCP consumes recipe data; it does not execute this local Java source interface.

Runtime schema stays unchanged. Material roles and named cube IDs are authoring
metadata, not extra runtime fields. The compiler derives deterministic, padded
box-UV placement; it fails clearly if the chosen atlas or current rig budget is
too small. Each metadata island records its bone, cube index, semantic material,
origin/size, shared source, mirror flag and all six native face rectangles.

Coordinates are model units (1/16 block): X right, Y down, Z back, with root Y=24
at ground level. Atlas dimensions remain the current runtime's power-of-two
16..512 range; the example intentionally uses compact 256x256. Native box UV has
one texel per model unit. It has no hidden UV scale or per-face image generation.
