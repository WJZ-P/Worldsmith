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
2. `worldsmith_get_pack_template`
3. Read the world style and analyze biome distribution
4. Read `worldsmith_get_contract` id `architecture`, then `worldsmith_plan_architecture`
5. Build individual Java DrawPrograms with `worldsmith_build_drawing`, query `worldsmith_get_drawing_job`, inspect `worldsmith_preview_drawing` image content and revise. The player confirms source execution inside MC once per session.
6. Reference frozen drawing ids and submit metadata with `worldsmith_put_structure`
7. `worldsmith_validate_architecture` checks real variants, required/optional members,
   landmark scale and occupied-space lighting
8. `worldsmith_write_pack` (repair and repeat on validation errors)
9. `worldsmith_finish_world`

The first tool returns terrain, biome, feature, structure, draw and architecture contracts. The terrain
contract tells the client to derive land/ocean balance, continent size,
coastline roughness, flat/highland/peak shares, vertical scale, cave density,
river routing, lake basins, ocean depth, additive/carving density bands and
landmark anchors from the player's prompt. The biome contract covers climate
placement, environment, surface grammar and features. The template supplies the
technical envelope; it is not a fixed terrain design.

`complete=true` requires Core checks, native structure export/readback, the full Minecraft
data-pack reload and preset activation in the current Create World context.
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
