# Minimal main-line quests

The quests module is a bounded server-owned main line, not an NPC dialogue system,
branching quest tree, independent achievement-definition language or arbitrary gameplay script.
Native exports project these existing quests into a vanilla advancement tab named
after the world bundle. No additional authoring module or fields are required.
Current new publications use bundle format 7; the quests module remains schema 1.

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

## Player-facing mission writing

Titles and descriptions are player-facing in the journal and native advancement
tree. Write about the place, stakes, people and real objective: where a required
material comes from, what to defeat or deliver, and why this matters to the world.
Keep implementation/terrain-validation notes, unsupported-feature disclaimers,
publication receipts and save-progress remarks in authoring diagnostics/receipts.
Do not weaken an impossible story promise with a technical footnote; write a
truthful playable objective instead. `themeBeat` links story context, not prose
about schemas, snapshots or native bindings.

New authoring publication uses `PlayerTextPolicy` and field-specific
`PLAYER_TEXT_ENGINEERING_LEAK` diagnostics. Repair new authored text rather than
silently filtering displays. Existing immutable packs and saved quest/advancement
prose are not rewritten or rejected by this new authoring gate.

## Objectives (1..8 per quest)

- `{"kind":"kill_creature","creature":"local_species_id","count":1}`
- `{"kind":"deliver_item","item":"worldsmith:item/local_item_id","count":4}`
- `{"kind":"activate_mechanic","mechanic":"local_mechanic_id","count":1}`

Counts are 1..1024. Kill references name real custom species, not generic native
hosts. Item references use real native items, `worldsmith:content/<blockId>` or
`worldsmith:item/<itemId>`, following the items contract. Unknown references fail.

A root is initially active. The next quest unlocks after its predecessor is CLAIMED,
not merely after its objectives become READY. Only the active/unclaimed quest gains
kill/delivery progress. There is no retroactive credit for kills before a quest unlocks.

Activation names an actual mechanics definition and observes only successful
committed activations attributed to this player. Matching a pattern, an attempted
use or quest prose does not count. The mechanic executes independently of quest
unlocks; the quest never drives or replays its actions. Previously committed
activations are retained and credited when a later objective unlocks, so a
one-shot device used earlier cannot make its later quest permanently impossible.
Use `activation_objective` from quest to mechanic in the design plan and review
the real mechanic event/cost/state/action fields plus material acquisition.
Independent anchor positions may activate the same mechanic ID; count names
committed activations, not unique world-wide bosses.

Unlocked/ready/claimed activation objectives also expose rule-derived mechanic
guides, including multiple targets in one quest. The link uses the stable mechanic
ID, never its display name; missing or incomplete construction does not hide it.
Locked quests offer no deep-link. Material lists, per-Y construction layers,
costs and effects come from the frozen rule, not authored duplicate instructions.
Optional aimed-anchor inspection is read-only and has separate server permission,
reach and loaded-area checks. Quest descriptions should still explain where to
find the actual device and sources of its materials; the guide creates neither.

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

Items schema 2 rewards can be weapons, mining tools, armor, consumables or items
with fixed actions. Use real `worldsmith:item/<id>` references and the actual
stack limit (equipment is one per entry); the full capability components survive
reward creation. An item's USE action is not a new quest objective or a quest
script. Read contract/items for right-click, held-armor crouch-use, food completion
and the shared per-logical-item cooldown rules. Describe those real controls in
natural player language when a new reward first enters the main line.

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
The journal has no manual refresh button or idle polling: opening it gets a
snapshot, delivery/claim replies carry the resulting state, and kill/activation changes are
coalesced into server pushes. Its list fits at least four entries in normal GUI
heights while remaining responsive on small windows.

The default-enabled arrival HUD presents the current chapter and world background
for about eight seconds on each real connection. It is non-pausing, leaves game
controls active and dismisses on Esc. Respawning, changing dimensions or reloading
resources does not show it again. This consumes existing theme/quest data; add no
toast, intro or tip fields to QuestLibrary. Players may turn it off in settings.

## Native advancement presentation

Newly exported native datapacks derive one world-named root and one node per quest.
The root uses a map; quests use books, with the challenge frame for real Boss kill
objectives. Vanilla visibility gradually reveals the chain. Empty quest libraries
produce no root/tab. IDs are isolated by the immutable bundle hash.

Each task has server-granted `objectives_met` and `claimed` conditions, both required.
Meeting kill/delivery/activation objectives alone does not complete the advancement: the player
claims in the journal, and only a successful inventory/state commit lights the node.
Advancement definitions contain no XP, loot, recipes or function rewards. The
existing quest attachment remains authoritative; manually granting an advancement
does not complete the quest or award its rewards.

Synchronization follows quest changes, joining, respawning and successful resource
reloads. Display failures never replay a committed reward. Vanilla handles the tree,
toasts and per-save advancement persistence; custom tasks still own delivery,
prerequisite checks and once-only claims. This adds neither independent exploration
achievements nor old-save migration. The separate `achievements` module is not installed.

## MCP authoring

Read this contract with `worldsmith_get_content_contract` module=quests. Submit a
complete quests library via `worldsmith_put_content_modules` at expectedRevision;
it shares the existing session revision. Link the world content, then use normal
write/finish publication. MCP edits definitions, not a player's earned progress.

New bundles use format 7 with ten typed modules including quests and mechanics.
Older bundle formats are rejected. No old save is silently upgraded or rewritten.
