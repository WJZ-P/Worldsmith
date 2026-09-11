# Worldsmith

Worldsmith is being built as a Fabric mod that turns a prompt into a
deterministic Minecraft world-generation blueprint.

The longer-term target is a complete world-content platform spanning terrain,
biomes, features, structures, custom blocks and creatures. The first shared
[content-framework layer](docs/world-content-framework.md) now provides typed
module adapters, logical references, shared asset storage and lifecycle planning.
Custom block and ground-creature runtimes are installed with bounded native hosts and a local-world lifecycle; bounded linear quests are installed; achievements remain a future module.

[Complete-world authoring](docs/complete-world-authoring.md) now adds an explicit linked design plan,
resumable repair progress, phased Bosses and typed landmark encounters. The
[replayable example kit](docs/examples/complete-world/README.md) uses provider-independent textures
and can be exported through the real native compiler without opening a player world.

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
  -> version-5 bundle (9 typed modules + immutable PNG/drawing assets)
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

The format-5 world bundle freezes Java drawing geometry, structure metadata and source provenance. Structure module schemas 1/2 select bounded JSON or frozen drawing data; they are not backward-compatible world-bundle formats. Both routes compile into native templates with biome placement and terrain fitting.

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

[World content framework](docs/world-content-framework.md) describes the nine
installed modules: theme, terrain, features, biomes, structures, blocks, creatures, items, quests.
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
branching NPC dialogue/achievements, free-form behavior scripts and arbitrary block
physical shapes are not installed. Formats 3/4 remain read-only; older unreleased world-bundle formats 1/2 must
be regenerated; existing files are not silently migrated.

## Creature authoring and creative inventory

[Creature authoring](docs/creature-authoring.md) adds a Java-friendly builder, JSON
recipes, mirrored limbs, automatic box UV layout and actual textured pose previews.
The offline and native renderers share the same pose evaluator. A UV guide is clearly
marked and never automatically published as a finished skin.

[World-scoped creative content](docs/creative-content.md) adds a dedicated creative
tab for the joined world's blocks, ordinary items and creature summoners.
Undefined/foreign content is not exposed.

[Darkstar Gatekeeper](docs/assets/creatures/darkstar-gatekeeper/README.md) is a reusable
appearance example with a real generated UV skin and rendered multi-view images;
it does not add a special Boss combat system.
The separate [Boss runtime](docs/creature-bosses.md) supplies explicit schema-2 phases,
native health bars and repeatable, world-bound landmark spawners.

## Ordinary items and reward sources

[Items and rewards](docs/items-and-rewards.md) adds world-bound resources/relics,
server-side creature death drops and native structure-container rewards. Canonical
ItemStacks retain their world identity, model, name, rarity and stack limits through
loot export, persistence and the creative catalog. New bundles use format 5; formats 3/4
restores its original content without silently acquiring new item/drop semantics.
This is an acquisition layer, not crafting, equipment or an executable quest system.

## Main-line journal and reusable textures

[Main-line quests](docs/mainline-quests.md) adds server-owned kill/delivery progress,
partial explicit item submission, once-only item rewards and a lightweight journal
(default J, rebindable in Controls). Client requests never supply progress values.
It is a linear foundation, not branching NPC dialogue or an achievement system.

[Texture production](docs/texture-authoring.md) explains the vendor-independent
pipeline for ordinary icons, shared-face block tiles and creature UV atlases.
Any MCP-capable client can use deterministic pixel recipes or import a PNG from its
own painting/image-generation provider. Worldsmith does not bundle a hosted image
model. A dedicated inbox avoids transcribing large image blobs through the model;
whole-image nearest resampling is explicit and preserves the original file.
