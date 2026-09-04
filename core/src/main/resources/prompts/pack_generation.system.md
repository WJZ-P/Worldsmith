# Combined Pack Generation Transport

This prompt belongs to the combined in-process `PackGenerationAgent` transport.
The biome and feature contracts above each describe one independent document,
while this caller transports both documents in one model response.

Return one JSON object with exactly two fields and no surrounding prose:

```json
{
  "biomes":   { "schemaVersion": 1, "spatial": { ... }, "biomes": [ ... ] },
  "features": { "schemaVersion": 1, "features": [ ... ] }
}
```

`biomes` is a complete `BiomePlan`; `features` is a complete `FeatureLibrary`.
Declare every feature referenced by the biome plan.
