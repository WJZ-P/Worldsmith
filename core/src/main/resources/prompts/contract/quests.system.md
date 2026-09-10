# Minimal main-line quests

The quests module is a bounded server-owned main line, not an NPC dialogue system,
branching quest tree, achievement definition or arbitrary gameplay script.

## Library and graph

`QuestLibrary` has schemaVersion=1 and quests (0..64). An empty library installs no
main line. A nonempty library has exactly one root, each quest has at most one
prerequisite and at most one successor, and all entries must be connected without
cycles. JSON array order is not the quest order; the validated prerequisite chain is.

Quest fields: id, title, description, prerequisites=[], objectives, rewards=[],
optional themeBeat. IDs are local lowercase 1..64; title is 1..160 printable
characters; description is 1..4096 plain-text characters with optional newlines.
`themeBeat` references an existing narrative beat in this world's theme. It links
creative intent to an executable quest but does not make all narrative text executable.

## Objectives (1..8 per quest)

- `{"kind":"kill_creature","creature":"local_species_id","count":1}`
- `{"kind":"deliver_item","item":"worldsmith:item/local_item_id","count":4}`

Counts are 1..1024. Kill references name real custom species, not generic native
hosts. Item references use real native items, `worldsmith:content/<blockId>` or
`worldsmith:item/<itemId>`, following the items contract. Unknown references fail.

A root is initially active. The next quest unlocks after its predecessor is CLAIMED,
not merely after its objectives become READY. Only the active/unclaimed quest gains
progress. There is no retroactive credit for kills before a quest unlocks.

## Two explicit player actions

**Deliver:** the player explicitly contributes matching items from the main
inventory. Partial delivery is allowed and records only the amount actually
consumed. Holding or picking up an item does not automatically satisfy delivery.
A player can deliver before finishing the same quest's kills; progress remains saved.
No materials means no inventory/progress mutation. Armor/offhand are not silently
consumed. Objectives with the same item are fulfilled deterministically in their order.

**Claim:** all objectives must be complete. The server simulates inserting every
reward into the main inventory. If it does not fit, claiming changes nothing and
creates no ground drops. Otherwise inventory and the once-only claimed state change
on the server together. Claiming does not consume previously delivered materials again.

Requests carry intent, world identity and expected revision, never client-reported
progress. Stale or repeated actions are rejected or return the current state; no
client UI animation is evidence of completion.

## Rewards (0..16 per quest)

`{"item":"worldsmith:item/local_reward","count":1}`

Each entry is one canonical stack, count 1..64 and within the target's actual stack
limit. Native reward references are resolved during publication. The complete item
identity/model/name/rarity/stack components are preserved. No experience, currency
DSL, commands or side-effect scripts are included.

## Persistence and presentation

Progress belongs to the actual server player and immutable world scope. Persistent
player attachment data is saved with the player's inventory, and normal death/respawn
keeps quest state. Valid saves preserve delivery counters and claimed flags. This
is not a promise of global transactions across external save rollback, manual NBT
editing, dropped entities or other mods' storage systems.

The client journal only displays server snapshots: LOCKED / ACTIVE / READY / CLAIMED.
Its delivery and claim buttons send requests; the server decides and reports missing
materials, full inventory, lock state and stale revisions. The journal is a simple
local-world UI, not a full quest-tree editor or a remote asset-negotiation feature.

## MCP authoring

Read this contract with `worldsmith_get_content_contract` module=quests. Submit a
complete quests library via `worldsmith_put_content_modules` at expectedRevision;
it shares the existing session revision. Link the world content, then use normal
write/finish publication. MCP edits definitions, not a player's earned progress.

New bundles use format 5 with the ninth quests module. Formats 3/4 remain read-only
with their original module sets/hash domains and empty quests. No old save is silently
upgraded by opening the journal.
