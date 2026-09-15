# Worldsmith mechanics contract — module schema 1, bundle format 7

Compose actual gameplay using `worldsmith_put_content_modules(sessionId,
expectedRevision, modules: {mechanics: WorldMechanicLibrary})`. Read this contract
with `worldsmith_get_content_contract(module: "mechanics")`. `write_pack` also
accepts an inline `mechanics` document; it freezes the same typed module into
`mechanics.json`. All ten module documents are required in format 7, including
an explicit empty mechanics library when the world promises no interactions.
Older bundle formats are rejected rather than automatically migrated.

## Exact data shapes

```text
WorldMechanicLibrary { schemaVersion: 1, mechanics: [WorldMechanicDefinition] }
WorldMechanicDefinition {
  id, displayName, description: "", initialState: "idle",
  states: ["idle", "active"], rules: [WorldMechanicRule]
}
WorldMechanicRule {
  id, event: BLOCK_PLACED | USE_BLOCK,
  pattern: [{offset: {x: 0, y: 0, z: 0},
             block: {block: "minecraft:stone", properties: {}}, consume: false}],
  actions: [MechanicAction], fromState: "idle", toState: "active",
  rotateY: true, heldItem: null, cooldownTicks: 20, biomes: []
}
MechanicAction =
  {kind: "set_block", offset: {x, y, z}, block: {block, properties: {}}}
  | {kind: "spawn_creature", creature: localCreatureId, offset: {x, y, z}}
  | {kind: "give_item", item: itemReference, count: 1}
heldItem = null | {item: itemReference, count: 1}
```

The action discriminator is `kind`; event names are uppercase. Each definition,
state and rule has a stable local ID. Rule IDs are unique inside their mechanic;
mechanic IDs are unique in the library. The initial/from/to states must be
declared. Native execution sorts mechanic IDs and then rule IDs, followed by rotation;
JSON array reordering is not a priority mechanism. One player interaction is
not a broadcast to every match.

Every rule independently contains offset `(0,0,0)` with a non-air block predicate.
This is its anchor; all offsets, state and cooldown are relative to that anchor.
Cells have distinct offsets. Unlisted cells are unconstrained, not implicitly
air. Add explicit air cells for intended clearance; spawning also needs native
entity clearance. A `set_block` destination must already appear in the pattern
and must not have `consume:true`; an action never gets a blind write outside its
verified pattern. Consumed cells become air. Each write destination is unique.

Block IDs are exact native identifiers or `worldsmith:content/<blockId>`, with
no tags and no raw host-slot names. `properties` is a partial exact match for
native block-state properties; custom block aliases use an empty properties map.
Item references are native non-air items, `worldsmith:item/<itemId>` or
`worldsmith:content/<blockId>`. Creature and biome references are local IDs in
this pack. Empty `biomes` means unrestricted; otherwise the anchor must be in
one of the named pack biomes. Publication checks all references; a prose mention
does not register a block, item, creature or rule.

## Events and costs

`BLOCK_PLACED` runs only after an actual successful player BlockItem placement.
The **last placed block may be any non-air pattern cell**, including the base,
arm or top. Candidate anchors are derived from that changed cell and checked
against the full pattern; it is not a special-top-block trigger. Worldgen,
commands and mechanic `set_block` actions do not trigger placement rules.
This event never accepts `heldItem`.

`USE_BLOCK` means a main-hand right-click on the exact anchor. With `heldItem`,
the held stack must match and supply the declared count; those items are consumed
only on a successful transaction. It does not search the rest of the inventory.
With `heldItem:null`, use an empty main hand. Sneaking bypasses this right-click
event and preserves ordinary placement/use; an actual successful block placement
can still trigger a BLOCK_PLACED rule. Dropping an item entity onto the anchor is
not an offering event. For material offerings, tell the player to hold the
material and right-click. The key example uses a one-use key; no implicit
reusable-key flag exists.

`rotateY:true` checks four horizontal rotations around the anchor, including
directional native block-state properties and action offsets. It neither tilts
nor mirrors a pattern. `rotateY:false` uses authored offsets directly.
Unloaded cells never force-load terrain; incomplete, blocked or unloaded
patterns do not activate. There is no periodic scan of all placed devices.

## State, transaction and bounds

State and cooldown persist per bundle + dimension + anchor position + mechanic
ID, not per player or entity. Rebuilding a spent device at the same position
does not reset its state. Distinct positions are independent instances; a
one-shot summon is not globally unique. The supplied schema has no dimensions
field, reset command or block-entity ticking interpreter.

Execution preflights state, loaded bounds, pattern, biome, held costs, all action
targets, native block/entity protection, creature placement and inventory
capacity. Costs/actions and the next state commit together; failed application
rolls back changes before state commit. Held-item costs are consumed in creative mode too. Native success feedback is fixed by the runtime, not authored
chat commands or arbitrary sound/action scripts.

Read live `authoringBudgets.mechanics`. Current bounds: 64 definitions, 16 rules
per definition, 16 states, 128 cells and 16 actions per rule; the whole library
also has at most 256 rules and 4096 cells. Every axis offset is -8..8; cooldown
is 1..72000 ticks; costs and grants are 1..64 items and must fit the referenced
item's native stack limit. Names are at most 128
characters and descriptions 2048. Predicate properties are at most 16 and biome
filters at most 64. Each mechanic/rule needs nonempty states/rules/pattern/actions.
There is at most one spawn action per rule; spawning must change state and no
reachable spawn transition cycle is accepted. Same-state reward/spawn loops
without a consumed cell or held-item cost are rejected. A paid exchange may
stay in one state with its declared cooldown; a one-shot summon ends in a spent
state with no route back. Cross-mechanic reward balance still needs author review.

Native preparation also rejects a trigger-block index exceeding 512 candidate
matches or 8192 worst-case cell checks, instead of silently starving later rules
at runtime. Use distinctive anchor/components, fewer overlapping rules or turn
off rotation where direction does not matter. A dimension stores at most 16384
anchor instances. Per tick, native execution accepts at most 16 player triggers
and 128 dimension triggers. A corrupt state ledger fails binding and preserves
the original file rather than resetting spent devices. Transaction compensation attempts every staged component independently. If a
native/third-party hook also fails during rollback, the ledger is persistently
quarantined and later interactions stop; loading that ledger fails closed for
inspection rather than replaying the device. This is not a promise that every
third-party native hook is reversible, nor crash-atomic saving across separate
world and player files or external rollback tools.

No arbitrary JavaScript, commands, thrown-item triggers, inventory scans,
periodic tick scripts, arbitrary event hooks or authored dimensions are installed.
Keep an unsupported promise visible as an authoring open decision instead of
substituting lore for the promised action.

## WorldBible, briefs and actual evidence

Record the world's interaction rule in a WorldBible RULE node. Plan each actual
device as `{kind:"mechanic",id:"..."}` with an owning module brief, source node
and acceptance criteria for event, pattern, cost, actions and terminal/repeat
behavior. The brief system routes mechanic edits back to `modules.mechanics`.
Review actual `/modules/mechanics/mechanics/<index>/rules/...` evidence. Typed
default normalization makes unchanged draft and frozen definitions share a
digest; changing a cost, pattern, action or state invalidates that brief's review.

Design links reflect actual fields: `uses_block` from mechanic to a local block;
`consumes_item` from mechanic to item/block_item; `grants_item` from mechanic to
item/block_item; `spawns_creature` from mechanic to creature. Required sources,
reachable states and player instructions belong in the design, not just those
links. The `mechanics` module may be empty when no mechanic was promised; an
explicitly named mechanic target needs its real definition. A narrative beat or
quest description alone never executes the machine. Quests observe committed
gameplay facts rather than implementing or driving the mechanics kernel.

See `docs/examples/mechanics/mechanics.json` for three machines using this exact
schema: a last-component summon, a main-hand key opening an iron gate and a paid
right-click material exchange. They are composable examples, not mandatory world
content, a complete `.wspack`, or evidence of a performed playtest.

## Player discovery and the actual rule-derived guide

An unlocked, ready or claimed quest's `activate_mechanic` objective opens a guide
by its stable mechanic ID. It works before the player completes the pattern;
locked quests have no guide deep-link. Multiple mechanic objectives have separate
entries. No new guide document, knowledge schema or separately maintained recipe
is accepted: `MechanicGuides.describe` projects the same frozen rule used by execution.

The guide lists events, building materials (including consumed counts), separate
held-item costs, bottom-to-top Y layers in a shared X/Z frame, rotations, state,
cooldown and action effects. Explicit air is not an unspecified/ignored cell.
Static plans can be read away from a device. Optional manual inspection examines
the aimed anchor only after server scope, readable-quest, player-state, loaded-area
and reach checks; it reports incomplete pattern, wrong/insufficient held item,
cooldown, spent state and obstruction separately. Inspection never triggers
actions, modifies blocks/inventory/state, allocates an instance or force-loads chunks.

Author the discovery route as real content: a reachable structure with typed sign
text, a quest description pointing to that place, and actual chest/drop/reward
sources for every needed material. Write short clues rather than JSON or commands
in player prose. Tell players which block is the anchor, when the last placement
triggers combat, and whether a key is consumed. Do not make a vital guide depend
on already solving its construction. `docs/examples/mechanic-discovery/README.md`
documents one reproducible small archive and its native event regression; it is
not a claim of a completed large world or human playthrough.
