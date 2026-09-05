# Combined Pack Generation Transport

This prompt belongs to the combined in-process `PackGenerationAgent` transport.
The biome, feature and structure contracts above describe independent documents,
while this caller transports those documents in one model response.

Return one JSON object with exactly three fields and no surrounding prose:

```json
{
  "biomes":   { "schemaVersion": 1, "spatial": { ... }, "biomes": [ ... ] },
  "features": { "schemaVersion": 1, "features": [ ... ] },
  "structures": { "schemaVersion": 1, "structures": [ ... ] }
}
```

`biomes` is a complete `BiomePlan`; `features` is a complete `FeatureLibrary`.
Declare every feature referenced by the biome plan.

`structures` is a StructureLibrary. Use an empty list when the player asks for no buildings. Read contract/structure for executable geometry, not textual summaries.
