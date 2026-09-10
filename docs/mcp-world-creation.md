# MCP to Create World

Worldsmith can let a logged-in GPT/Codex client author a world without putting
an AI API key in Minecraft. The model runs in the client; the mod exposes only
loopback MCP tools for designing, validating and saving a portable pack.

## Enable the bridge

Install the optional Mod Menu and Cloth Config mods, open Worldsmith's settings,
and enable **MCP Bridge**. It is off by default and binds only `127.0.0.1`.

While it is running, the mod writes its current URL and a ready-made MCP client
configuration to:

```text
<minecraft-instance>/config/worldsmith/mcp.json
```

The configured port is preferred. If it is occupied, Worldsmith takes the next
free loopback port, so clients should use the URL announced in this file rather
than assuming a fixed value. With the default port, Codex can be connected with:

```powershell
codex mcp add worldsmith --url http://127.0.0.1:47631/mcp
```

## Generate a world

Ask the connected client to use Worldsmith, for example:

```text
Use Worldsmith to create a silent black-ocean world with salt flats and ruined observatories.
```

The guided MCP contract requires this sequence:

1. `worldsmith_begin_world`
2. `worldsmith_get_content_framework`, then `worldsmith_get_content_contract` for theme, blocks, creatures and items. Establish the shared premise/rules/conflict and linked narrative beats; author actual PNGs and commit typed modules with `worldsmith_put_content_modules` at `expectedRevision`. Read `worldsmith_get_content_draft` after conflicts and retain each returned revision. Then read `worldsmith_get_pack_template` for field shapes.
3. Read the world style and analyze biome distribution
4. Read `worldsmith_get_contract` id `architecture`, then `worldsmith_plan_architecture`
5. Prefer geometry-linked StructureProgram source targets (DrawProgram remains compatible). Build with `worldsmith_build_drawing`, query `worldsmith_get_drawing_job`, inspect actual `worldsmith_preview_drawing` images and revise before expanding a family. Study clay massing, elevations, occupied-floor cutaways and the assembled layout. Source confirmation follows the configured host policy.
6. Reference frozen drawing ids and submit metadata with `worldsmith_put_structure`
7. `worldsmith_validate_architecture` checks real variants, required/optional members,
   landmark scale and occupied-space lighting
8. `worldsmith_write_pack` with `sessionId` and the current `expectedRevision`. It uses inline or committed module documents and freezes all attached PNG assets into format 4 (repair precise diagnostics on errors).
9. `worldsmith_finish_world`

The first tool returns terrain, biome, feature, structure, draw and architecture contracts. The terrain
contract tells the client to derive land/ocean balance, continent size,
coastline roughness, flat/highland/peak shares, vertical scale, cave density,
river routing, lake basins, ocean depth, additive/carving density bands and
landmark anchors from the player's prompt. The biome contract covers climate
placement, environment, surface grammar and features. The template supplies the
technical envelope; it is not a fixed terrain design.

`complete=true` requires Core checks, native structure export/readback, the full Minecraft
data-pack reload, verified client asset reload, stable world bindings and preset activation in the current Create World context.
Missing context returns `WAITING_NATIVE_CONTEXT`; open Create World, then check again.
Native failures never fall back to Core-only completion. World instance placement remains unverified. The final pack remains in:

```text
<minecraft-instance>/config/worldsmith/packs/<content-sha256>/
```

## Select it in Minecraft

If **Create New World** is already open when publication is requested, Worldsmith
exports the pack to Minecraft's temporary data-pack repository and starts the
normal reload immediately. Otherwise this happens the next time the screen is
opened.

After reload, the pack's display name appears under **More World Options** and
is selected automatically. A fixed pack seed is copied into the seed field;
when the pack seed is empty, Minecraft keeps random-seed behavior.

The generated registry ids are scoped by the pack's full content hash. This
keeps a generated biome named `abyss` distinct from the built-in
`worldsmith:abyss` and prevents lower-priority tag membership from leaking into
the generated world.

See [Structure building](structure-building.md) for the executable geometry language, per-structure MCP drafts, preview tool, placement and source layout.

## Recovery

`worldsmith_list_sessions` and `worldsmith_resume_session` restore plans, drafts and source/job
references without running source. Interrupted work needs a new requestId and renewed
in-game confirmation after restart. The AI needs MCP only; the player needs no separate
JDK, javac or Python. The hidden worker is not an OS filesystem/network sandbox.

[Complete Java and MCP example](structure-agent.md).


新版创作入口与示例：[Structure Authoring Workbench](structure-authoring-workbench.md)。

## Shared content framework

The [world content framework](world-content-framework.md) installs eight typed
modules. `worldsmith_get_content_framework` reports native adapter availability;
`worldsmith_plan_world_content` reports catalog links, not a game activation.
`worldsmith_inspect_world_content` revalidates a saved bundle.

Authoring tools share one persistent revision with architecture: `get_content_draft`,
`put_content_modules`, `put_texture_asset`, `create_pixel_texture`, and
`preview_texture_asset` (all with the `worldsmith_` prefix). Texture IDs are the
SHA-256 of real PNG bytes; pixel creation is explicit palette/rows authoring,
not a hosted image model. `write_pack` consumes a changed pack's revision too.

Native export embeds the complete bundle under `worldsmith-content/` and its slot
map under `worldsmith-runtime/block-bindings.json`. The datapack travels with the
save. Loading the local world restores and verifies these resources before server
startup, and cancellation/disconnect clears only the owning resource scope.
Reload rejects changes to a running world's immutable identity before replacing
its resource manager. Missing/corrupt assets and conflicting selected bundles
are errors; they do not silently remap old chunks.

New writes use format 4; existing format 3 is read-only. The current native lifecycle is for one local integrated
world; dedicated/remote content negotiation is not installed. The theme's beats
are durable narrative intent, not executable quests or achievement criteria.

See [ordinary items and reward sources](items-and-rewards.md) for the item icon contract, logical references, creature drops and world-bound container loot.
