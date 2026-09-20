# Story runtime and authoring

The canonical authoring reference is the packaged
[`story` contract](../core/src/main/resources/prompts/contract/story.system.md),
readable through `worldsmith_get_content_contract(module:"story")`.

Format 10 requires a schema-2 `story/story.json` module, quest schema 2 and the existing
portable ability module. Structure schema 3 adds typed StoryAnchor markers.
No older-format package/save migration is performed.

The Core story domain provides shared typed fact conditions and transactions,
deep freezing, graph validation and stable cross-content references. Narrative
visibility is based on server facts, not client-provided progress. A named place
is a definition; a generated marker is an instance. The latter's UUID, dimension
and real coordinates are the source of discovery and resident ownership.

Story routines describe bounded activities around the resident's marker. On
reload the runtime derives the current activity from world time; it does not
resume a saved ability instruction stack or force-load distant chunks. Existing
ability programs provide local behavior/effects rather than a second story VM.

## Durable actual-place outcomes

Story schema 2 adds once-per-place `projections`: WORLD/own-PLACE fact conditions,
up to 32 expected/desired exact block states at real rotated marker offsets, and
`onApplied` fact changes. A persistent `(marker UUID, projection id)` receipt
owns completion independently of the player who made the decision. Only loaded
targets and collision neighbours are inspected. Every target, ledger change and
receipt is preflighted before a bounded staged write; third states, block entities,
fluids, indestructible states, world borders and occupied desired shapes defer the
batch. Desired blocks are air or static full-cell construction blocks (plain
blocks, ordinary pillars, amethyst/glass families and logical hosts); support,
placement and ticking state machines such as torches, doors, sponges and TNT
are rejected before world binding. Missing receipts permit mixed expected/desired
state recovery. Completed receipts record application history, not an eternal
constraint against later world changes; they never overwrite later player edits
or replay inventory rewards.

The scheduler considers at most 64 candidate pairs and two loaded pending batches
per level tick. Unloaded marker/target/halo chunks consume scan slots but no
application slots. An append-only place registration preserves the cursor. For
a stable N-pair ring, a sole loaded eligible batch is observed within ceil(N/64)
ticks; persistent loaded conflicts still permit a full ring visit within
ceil(N/2) ticks. The sample has two pairs; the maximum configured ring is 131072.
`StoryProjections.inspect(level, actualPlaceUUID, projectionId)` gives
current read-only WAITING/UNLOADED/CONFLICT/PROTECTED/OBSTRUCTED/READY/APPLIED-state
evidence without loading chunks. Its completed state is `ALREADY_APPLIED`.
On live rebind, existing loaded marker UUIDs and one loaded-marker enumeration
rebuild transient work; no new place identity or chunk ticket is created.

SavedData and native chunks are separate files. The protocol supports in-process
compensation, normal save/reload and absent-receipt partial application recovery;
it does not claim power-loss-atomic durability across those files.

The installed catalog does not prove that a settlement generated, that all
routes are walkable or that a story is balanced. Those require separate native
placement/interaction/persistence tests and actual player experience review.

## Bounded route evidence

`StoryRouteProbe.probe(player, savedPlace[, maxNodes, radius])` starts from the real
player feet and accepts only a place instance present in the current story ledger.
It reads loaded chunks only, never navigates, teleports, places blocks or requests
chunk loading. Node allocation is capped at 8192 and feet radius at 128 blocks;
collision checks additionally inspect the native large-shape neighbour padding.

`VERIFIED` reports an explicit supported, dry, collision-free geometric corridor
at inspection time. The model is walk-only cardinal motion. Ascents are limited
to the actual `player.maxUpStep()` attribute and conservatively capped at 0.6;
drops are at most one block. A one-block uphill jump is deliberately rejected,
not approximated by a vertical-then-horizontal jump trajectory. It does not certify
jumps, full movement physics, swimming, ladders, portals, mobs or scripts.
`UNLOADED` means required collision space lacked evidence. `NO_PATH_WITHIN_BUDGET`
means no path was certified under this model and limits, never global impossibility.

Minecraft 26.2's collision iterator uses an inclusive one-cell expanded cursor
with floating-point edge padding; the probe mirrors those exact bounds before
calling collision queries. The native iterator itself uses
`getChunk(..., ChunkStatus.FULL, false)`. This avoids treating an unobserved neighbour
at an integer collision face as empty space. Runtime gravel/sand still requires a
real supporting foundation: the probe checks the world as it exists, not its plan.

## Shared authority and durable identities

- Facts are declared `BOOL`, bounded `NUMBER`, or `TEXT`. Their owner is the
  world, current player, an actual character anchor, or an actual place marker.
  A definition name is not an invented coordinate or a unique global NPC.
  Ambiguous instance references do not select an arbitrary resident.
- One overworld `StorySavedData` ledger owns facts and instance identities across
  dimensions. It also records each player's discoveries, learned knowledge and
  trade usage. Immutable snapshots and checked replacement let story writes
  participate in inventory/quest transactions.
- Restore rejects malformed values, unknown owners, contradictory character
  states, mismatched fact scopes and missing content definitions. Existing bad
  files are retained rather than silently replaced with a fresh history.
- Dialogue packets contain intents and one-use server tokens, not client-owned
  facts. The server checks world, player, actor, distance, sight, node conditions,
  revision, token and costs again before committing. Fact observers run after
  the transaction; optional AbilityScript invocation is reserved first and
  starts only after the inventory and facts commit.

## Discovery, choices and recovery

Quest schema 2 supports discovered/available conditions, manual acceptance,
exclusive choice groups, optional quests/objectives, `ALL`/`ANY` prerequisites,
fact objectives and actual-place destinations. The server only sends discovered
quest content. Hidden vanilla advancement entries are a completion gallery,
not a second source of future-story spoilers. Accepting or claiming a quest is
an authoritative transaction, and claiming cannot bypass its current condition.

Trade inputs/outputs are inspectable data, not assumed consequences of a script.
They use the same inventory transaction as quest rewards; missing costs or a full
backpack change neither facts nor inventory. The dialogue shows the complete
terms before selection, including in keyboard narration. Program side effects
that execute later are not promised to be part of that inventory transaction.

Residents keep a stable home anchor independently of a body's current UUID.
An absent loaded entity is not evidence that it should be duplicated. Authors
may define death facts and a bounded respawn delay; respawn also needs a loaded,
supported, unobstructed home. Death history is retained. Daily routes are derived
from the current clock, yield to dialogue and active abilities, and do not
force-load remote chunks or simulate an entire absent population.

The knowledge journal distinguishes facts, legends and beliefs. Discovered-place
guidance uses saved actual coordinates and can be disabled. Story sound layers
have priority selection, repeat spacing and fading; comfort settings control
guidance, ambience and reduced effects without hiding the underlying objectives.

## Engineering sample: 归灯村

`ImmersiveVillageExample` exports a format-10 `.wspack`, an unpacked bundle and
the two portable ability sources. It contains three residents, a village and
an old beacon joined by an authored stone path:

1. Speak to 星禾, read the account, and claim the first journal reward for a key.
2. Speak to 苔枝 for a contrasting legend; follow the stone path to the old beacon.
3. Accept one of the mutually exclusive routes in the quest journal, then bring
   the key back to 星禾. The transaction records the choice and reserves a
   disposable cosmetic response. A durable actual-place projection
   changes the real lamp to a sea lantern or an amethyst block and acknowledges
   physical completion independently of that response's lifetime.
4. Return for the response and final reward. 砾石 can recast a lost key for three
   copper ingots; the supply chest contains copper. A stone inscription explains
   how the residents return after losing a body, and a subsequent conversation
   acknowledges that death rather than clearing it from history.

The settled outcome belongs to the world, while each visitor's promise remains
personal. A player with an opposing accepted promise or a late arrival can visit
the keeper, personally witness/respond to the applied result and complete their
own journey without surrendering another key or overwriting their promise. A
global result alone grants no such personal completion. Final-return dialogue
requires the preceding route's actual claim stage.

This is a compact replayable engineering journey, not a claim of a measured
30–60-minute human playthrough, finished character art, a continent-scale ecology,
or automatic generation of arbitrary inter-region roads.

## Evidence and test boundaries

`StoryWorldgenChecks` checks the regular native exporter and real origin terrain
fit for three sample seeds. This does not certify every seed. `StoryRouteProbe`
separately inspects a registered place from the player's actual feet using loaded
block collision/support geometry; its report includes the movement model, path,
radius and node budget. It reports `UNLOADED` or `NO_PATH_WITHIN_BUDGET` instead of
inventing a route or loading chunks. Results are inspection-time evidence, not
an everlasting reachability guarantee after players change the terrain.

Opt-in verification (production artifacts exclude these test entrypoints):

```powershell
./gradlew.bat -PmechanicsGameTest -PstoryTest runGameTest
./gradlew.bat -PmechanicsGameTest -PstoryClientTest runClientGameTest
```

The server fixture exercises both choices, a rotated real template, inventory
failures, stale choices, discovery authority, route evidence, routines, native
save/load and timed resident restoration. The separate client fixture uses real
interaction packets and screens, checks narration and comfort controls, captures
screenshots, and closes its isolated world normally. See the generated verification
report for the results of an actual run rather than inferring success from these
test definitions.
