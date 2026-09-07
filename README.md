# Worldsmith

Worldsmith is being built as a Fabric mod that turns a prompt into a
deterministic Minecraft world-generation blueprint.

## Development baseline

- Minecraft Java Edition `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.158.0+26.2`
- Java `25`
- Gradle `9.5.1`

## Packages

- `core`: Kotlin, Minecraft-version-independent prompt and blueprint pipeline
- `com.wjz.worldsmith`: Java common initialization
- `com.wjz.worldsmith.client`: Java client initialization and future create-world UI
- `com.wjz.worldsmith.datagen`: generated data entrypoint
- `com.wjz.worldsmith.worldgen`: Minecraft 26.2 world-generation integration

The current player-facing flow is:

```text
Player prompt
  -> MCP terrain and biome contracts
  -> portable Worldsmith Pack JSON
  -> deterministic validation
  -> Minecraft 26.2 target compiler
  -> selected Create World preset
```

The AI-facing source of truth lives under
[`core/src/main/resources/prompts`](core/src/main/resources/prompts), with the
validated built-in JSON shape under
[`core/src/main/resources/worldsmith/packs/ashlands`](core/src/main/resources/worldsmith/packs/ashlands).

See [the local MCP-to-Create-World workflow](docs/mcp-world-creation.md).

## Structures

For free-form Java authoring, see [the drawing SDK](docs/draw-sdk.md): a Core
voxel canvas with brushes, masks, curves, implicit geometry and native structure
NBT export. It remains separate from architecture composition and placement.
The [Structure Agent](docs/structure-agent.md) connects Java submission, hidden
worker compilation, model-image preview, composition and native publication through MCP.

Java drawings freeze into format 2 data and are referenced by structure metadata.
The bounded JSON building grammar (fills, shells, lines, roofs, repeats and local
modules) remains a format 1 compatibility entry. Both routes compile
into Minecraft templates with biome placement, rigid terrain fitting and
bounded foundations. Structure sources participate in the pack hash.

See [structure building and MCP previews](docs/structure-building.md).
New guided worlds also follow [the architecture-agent policy](docs/architecture-agent.md):
world-specific groups, independent structures, a monumental landmark and readable interiors.
The executable AI contract is
[`contract/structure`](core/src/main/resources/prompts/contract/structure.system.md).

## Build

```powershell
./gradlew.bat build
```

## Run the development client

```powershell
./gradlew.bat runClient
```
