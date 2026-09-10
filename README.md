# Worldsmith

Worldsmith is being built as a Fabric mod that turns a prompt into a
deterministic Minecraft world-generation blueprint.

The longer-term target is a complete world-content platform spanning terrain,
biomes, features, structures, custom blocks and creatures. The first shared
[content-framework layer](docs/world-content-framework.md) now provides typed
module adapters, logical references, shared asset storage and lifecycle planning.
Custom block and ground-creature runtimes are installed with bounded native hosts and a local-world lifecycle; executable quests and achievements remain future modules.

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
  -> MCP theme, content and worldgen contracts
  -> version-4 bundle (8 typed modules + immutable PNG/drawing assets)
  -> deterministic validation
  -> Minecraft 26.2 target compiler
  -> verified native data + client resources + selected Create World preset
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

The format-4 world bundle freezes Java drawing geometry, structure metadata and source provenance. Structure module schemas 1/2 select bounded JSON or frozen drawing data; they are not backward-compatible world-bundle formats. Both routes compile into native templates with biome placement and terrain fitting.

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

## Unified world content

[World content framework](docs/world-content-framework.md) describes the eight
installed modules: theme, terrain, features, biomes, structures, blocks, creatures, items.
MCP has revision-checked drafts, genuine PNG upload/indexed-pixel authoring, linked
narrative beats, immutable bundle hashing and full publication gates.

Custom blocks use 128 startup hosts (32 each stone/wood/metal/glass), not arbitrary
live registry mutation. Ground creatures use typed cuboid rigs, native entity hosts
and bounded server-side behavior/animation. See [blocks](docs/custom-block-runtime.md)
and [creatures](docs/custom-creature-runtime.md).

Generated datapacks carry the complete bundle and stable block bindings with the
save; reopening never depends on an AI, authoring drafts or a config pack copy.
Client resources are prepared before local-server startup and publication.
Current scope is local integrated-server worlds. Remote content negotiation,
executable quests/achievements, free-form behavior scripts and arbitrary block
physical shapes are not installed. Format 3 remains read-only; older unreleased world-bundle formats 1/2 must
be regenerated; existing files are not silently migrated.

## Creature authoring and creative inventory

[Creature authoring](docs/creature-authoring.md) adds a Java-friendly builder, JSON
recipes, mirrored limbs, automatic box UV layout and actual textured pose previews.
The offline and native renderers share the same pose evaluator. A UV guide is clearly
marked and never automatically published as a finished skin.

[World-scoped creative content](docs/creative-content.md) adds a dedicated creative
tab for the joined world's blocks and creature summoners, with a provider interface
for a future ordinary-item domain. Undefined/foreign content is not exposed.

[Darkstar Gatekeeper](docs/assets/creatures/darkstar-gatekeeper/README.md) is a reusable
appearance example with a real generated UV skin and rendered multi-view images;
it does not add a special Boss combat system.

## Ordinary items and reward sources

[Items and rewards](docs/items-and-rewards.md) adds world-bound resources/relics,
server-side creature death drops and native structure-container rewards. Canonical
ItemStacks retain their world identity, model, name, rarity and stack limits through
loot export, persistence and the creative catalog. New bundles use format 4; format 3
restores its original content without silently acquiring new item/drop semantics.
This is an acquisition layer, not crafting, equipment or an executable quest system.
