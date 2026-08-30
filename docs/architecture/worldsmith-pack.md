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
- `terrain.json` describes dimension bounds, sea level, semantic terrain shape,
  base material, fluid, aquifers, ore veins, and spawn climate targets.
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

## Shaping the terrain

Prompt-generated packs use a version-independent `procedural` shape rather than
embedding Minecraft density-function JSON:

```json
"shape": {
  "kind": "procedural",
  "landRatio": 0.55,
  "continentScale": 1.0,
  "coastRoughness": 0.45,
  "relief": { "flats": 0.65, "highlands": 0.25, "peaks": 0.10 },
  "verticalScale": 1.0,
  "caveDensity": 0.65,
  "hydrology": {
    "riverCoverage": 0.06,
    "riverWidth": 1.0,
    "riverDepth": 0.8,
    "riverMeander": 0.65,
    "riverFill": "FLUID",
    "lakeDensity": 0.08,
    "lakeScale": 1.0,
    "lakeDepth": 0.8,
    "oceanDepth": 1.0
  }
}
```

`landRatio` is the intended surface share above sea level. `continentScale`
controls the horizontal size of land and ocean regions, while
`coastRoughness` adds finer bays and peninsulas without changing that broad
scale. The three `relief` values are relative weights and are normalized; a
landform the prompt excludes may have weight zero. `verticalScale` changes the
height amplitude, and `caveDensity` blends from no cave carving to the complete
supported overworld cave system.

Hydrology is part of the same immutable terrain intent. River coverage and lake
density are statistical area targets rather than required counts. Width and
scale change the physical correlation length, depth changes the solid floor,
and meander changes river routing. `riverFill: FLUID` publishes matching coast
and shallow-water continentalness so biome selection follows the water;
`riverFill: DRY` cuts a valley above sea level while retaining its land biome.
`oceanDepth` scales only the ocean-floor side of the continental boundary.

Every hydrology field is required. A world with no inland water expresses that
decision with zero river coverage and zero lake density; it still chooses its
ocean depth explicitly. Consequently every water-system decision participates
in the pack content hash.

The Minecraft 26.2 target adapter compiles those outcomes into a `NoiseRouter`.
It retains vanilla aquifer, climate and ore-noise plumbing, but owns the
continentalness, relief, hydrology, vertical-density and cave-carving
functions. Sampling tests wire the real Minecraft noises with a fixed seed and
check each control's direction and magnitude. The resulting land and inland
water shares remain seeded statistical targets rather than exact per-map-area
quotas.

The `vanilla` shape variant is a deliberate passthrough mode for worlds that
explicitly request an unchanged `OVERWORLD`, `LARGE_BIOMES` or `AMPLIFIED`
router; prompt-guided generation uses `procedural`.

## Placing a biome

A biome says where it generates in one of two ways.

A **climate slot** names bands instead of writing numbers:

```json
"slot": { "relief": "FLATS", "temperature": ["COLD"], "humidity": ["ARID"] }
```

The semantic vocabulary has three axes:

- `relief`: `DEEP_WATER`, `SHALLOW_WATER`, `COAST`, `PEAKS`, `HIGHLAND`, `FLATS`
- `temperature`: `COLD`, `TEMPERATE`, `HOT`
- `humidity`: `ARID`, `HUMID`

An empty list spans the whole axis. Several bands may be listed as long as they
are adjacent: they collapse into one range, so a gap would quietly include the
band in between, and the validator rejects that mismatch.

The band edges mirror vanilla's overworld builder, so terrain height and biome
choice stay derived from the same continentalness and erosion values. Slots are
optional presets, not a world template: packs do not need to use every band and
several biomes may intentionally describe nearby regions.

A **raw climate box** is the precise form:

```json
"climate": {
  "temperature": { "min": -1.0, "max": 1.0 },
  "humidity": { "min": -1.0, "max": 0.55 },
  "continentalness": { "min": -0.11, "max": 1.0 },
  "erosion": { "min": 0.05, "max": 1.0 }
}
```

It is first-class and is normally the better choice when the prompt describes
dominance or rarity: broad ranges make a biome common, narrow ranges make it
rare. Unnamed parameter regions are valid and resolve to the nearest declared
biome. `depth` is not ocean depth: it is position relative to the surface, near
zero at ground level and approaching one deep underground.

Worldsmith imposes no required biome count, distribution symmetry or coverage
quota. Those decisions come from the player prompt.

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
