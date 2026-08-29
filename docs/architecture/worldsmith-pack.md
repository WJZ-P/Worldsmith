# Worldsmith Pack format

The canonical output of AI generation is a directory containing declarative
JSON. Minecraft Java or Kotlin source code is never part of a pack.

```text
ashlands/
├─ worldsmith.json
├─ terrain.json
├─ biomes.json
└─ features.json
```

- `worldsmith.json` identifies the pack and points to its content files.
- `terrain.json` describes dimension bounds, sea level, noise template, base
  material, fluid, aquifers, ore veins, and spawn climate targets.
- `biomes.json` defines every biome: where it generates, what it is made of,
  how it looks, which tags it joins, and which features grow on it.
- `features.json` declares the reusable generated features biomes refer to by
  name.

A biome and its appearance live in one entry because splitting them only bought
a class of cross-file errors: unknown ids, missing halves, and two lists that had
to stay in the same order. Features stay separate because the same dead tree is
usually wanted by several biomes, and it should be declared and compiled once.

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

## Placing a biome

A biome says where it generates in one of two ways.

A **climate slot** names bands instead of writing numbers:

```json
"slot": { "relief": "FLATS", "temperature": ["COLD"], "humidity": ["ARID"] }
```

The grid has three axes:

- `relief`: `DEEP_WATER`, `SHALLOW_WATER`, `COAST`, `PEAKS`, `HIGHLAND`, `FLATS`
- `temperature`: `COLD`, `TEMPERATE`, `HOT`
- `humidity`: `ARID`, `HUMID`

An empty list on an axis claims that whole axis, so a slot naming only a relief
takes all six of its cells. Several bands may be listed as long as they are
adjacent: they collapse into one span, so a gap would make the box quietly
swallow the band in between, and the validator rejects that.

The band edges mirror vanilla's overworld builder, so terrain height and biome
choice stay derived from the same continentalness and erosion values. Because
the grid is finite (6 × 3 × 2 = 36 cells), "every cell is claimed exactly once"
is a proof that every biome in the pack can actually generate, and the validator
checks it.

A **raw climate box** is the escape hatch:

```json
"climate": { "continentalness": { "min": -1.2, "max": -0.455 } }
```

It is legal but forfeits the proof, so the validator downgrades coverage to a
warning for the whole pack. Reach for it only when a band cannot express the
intent. Note that `depth` is not ocean depth: it is position relative to the
surface, near zero at ground level and approaching one deep underground.
Constraining it is almost never what an author means.

## Tags

An archetype supplies a default tag set, and `tags.add` / `tags.remove` adjust
it. Removal only subtracts from those defaults; a Worldsmith biome is never in a
vanilla tag unless the compiler put it there. Tags are load-bearing rather than
cosmetic: structure placement intersects a structure's biome set with the
world's possible biomes, so a biome in no tags generates no structures at all.

## Feature budget

Each recipe maps to a per-chunk cost: ground cover becomes an attempt count,
sparse props become a rarity filter. A biome's costs are summed and capped, so a
pack cannot quietly make world generation crawl. The mapping lives in
`core`, shared by the validator and the compiler, so the number the validator
checks is the number the compiler emits.

## Identity and seed

The manifest `id` is the lowercase SHA-256 of the canonical generation files,
not a user-chosen slug. The manifest itself, display name, description, previews,
logs, and caches do not participate. Object keys are sorted before hashing, so
formatting and key order do not change the id.

`terrain.seed` is nullable. A fixed number participates in the content hash and
reproduces the same seed when imported. A missing or null seed describes a
random-seed recipe; the chosen seed is persisted in the created Minecraft world.

```text
./gradlew.bat :core:hashPack -PpackDir=C:\path\to\pack
```

prints the id that should be written into `worldsmith.json`.
