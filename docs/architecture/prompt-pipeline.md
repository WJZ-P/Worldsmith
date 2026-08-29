# Prompt generation pipeline

Worldsmith treats a player prompt as the source for a version-independent,
structured world blueprint. Language-model output never writes directly into
Minecraft registries or chunk generation.

## Pipeline

```text
Player prompt
  -> WorldBibleAgent
  -> WorldBible
  -> StructureCatalogAgent
  -> List<StructureBrief>
  -> N concurrent StructureDetailAgent calls
  -> List<StructureDefinition>
  -> ConsistencyReviewAgent
  -> WorldBlueprint JSON
  -> Minecraft target compiler (next layer)
```

For a request such as `我想要一个废土风世界`, the first agent produces the
shared world bible: terrain, surface palette, biome themes, atmosphere,
architecture, decay, and global rules. The catalog agent then allocates the
requested structure budget. Structure-detail agents receive immutable shared
context plus exactly one brief and may run concurrently.

The "large prompt" is therefore stored as typed shared context rather than one
ever-growing string. A shortened first-stage result looks like:

```json
{
  "id": "ashlands",
  "title": "灰烬荒原",
  "summary": "被风沙和工业废墟覆盖的干涸世界",
  "themeTags": ["wasteland", "industrial", "decayed"],
  "biomeThemes": ["ash_desert", "toxic_basin"],
  "terrain": {
    "profile": "WASTELAND",
    "description": "断裂台地与干涸河床",
    "relief": "eroded",
    "caveStyle": "collapsed",
    "waterLevelHint": 24
  },
  "architecture": {
    "styleTags": ["brutalist", "salvaged"],
    "shapeLanguage": ["low silhouettes", "exposed supports"],
    "decayLevel": 0.8
  }
}
```

The catalog stage turns that shared context into briefs such as:

```json
{
  "id": "scrap_shelter",
  "name": "拾荒者庇护所",
  "category": "SHELTER",
  "worldRole": "Early-game shelter and environmental storytelling",
  "descriptionPrompt": "A low improvised shelter built around a collapsed pipeline",
  "styleTags": ["salvaged", "wind-worn"],
  "allowedBiomeThemes": ["ash_desert"],
  "rarityWeight": 1.0,
  "footprint": { "width": 16, "depth": 16, "minHeight": 4, "maxHeight": 10 }
}
```

## Invariants

- System prompts live at stable paths under `core/src/main/resources/prompts`.
- Git history and generated-content hashes provide prompt traceability without versioned folders.
- Every stage exchanges typed JSON contracts.
- Structure workers never mutate shared state.
- Concurrent output is merged in catalog order, not completion order.
- Stable identifiers connect briefs, definitions, generated files, and logs.
- Deterministic validation runs after catalog planning and final review.
- Minecraft classes and registry identifiers stay outside `core`.

## Current boundary

This template stops at `WorldBlueprint`. A target-specific compiler will later
resolve semantic material selectors against the active Minecraft registries and
emit a validated generated data pack.
