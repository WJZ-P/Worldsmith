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

With the default port, Codex can be connected with:

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
3. `worldsmith_write_pack` (repair and repeat if validation reports errors)
4. `worldsmith_finish_world`

The first tool returns both a biome contract and a terrain contract. The terrain
contract tells the client to derive land/ocean balance, continent size,
coastline roughness, flat/highland/peak shares, vertical scale, cave density,
river routing, lake basins and ocean depth from the player's prompt. The
template supplies the technical envelope; it is not a fixed terrain design.

`complete=true` means the pack was read back, validated and selected for the
Minecraft world-creation screen. The final pack remains in:

```text
<minecraft-instance>/config/worldsmith/packs/<content-sha256>/
```

## Select it in Minecraft

If **Create New World** is already open when the MCP run finishes, Worldsmith
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
