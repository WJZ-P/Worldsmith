# Worldsmith Pack format

The canonical output of AI generation is a directory containing declarative
JSON. Minecraft Java or Kotlin source code is never part of a pack.

```text
ashlands/
├─ worldsmith.json
├─ terrain.json
└─ biomes/
   ├─ layout.json
   └─ skins.json
```

- `worldsmith.json` identifies the pack and points to its content files.
- `terrain.json` describes dimension bounds, sea level, noise template, base
  material, fluid, aquifers, ore veins, and spawn climate targets.
- `biomes/layout.json` defines biome skeleton ids, six-dimensional climate
  parameter boxes, archetype roles, temperature, downfall, and precipitation.
- `biomes/skins.json` defines colors, surface layers, and constrained
  vegetation recipes for every layout skeleton.

The folder is the authoring and source-control form. A future export action will
ZIP the same directory as `ashlands.worldsmith`, providing a single convenient
file for sharing without changing its internal format.

On import, Worldsmith will validate the source JSON and compile it through the
active Minecraft target adapter. The compiled Minecraft data pack is an output
cache, not the canonical AI document. A compatible compiled snapshot may later
be included under a `targets/` directory for exact replay on a specific game
version.

Packs contain data only: no JARs, scripts, or executable code. Paths are
resolved inside the pack root and schema validation runs before compilation.
