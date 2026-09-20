# Worldsmith story — schema 2, bundle format 10

The required `story` module is stored at `story/story.json`. It connects persistent
facts, actual places and characters, conditional dialogue, knowledge, trades,
routines, soundscapes and durable actual-place projections. It is not a second
scripting VM: use ability programs for disposable local presentation, and a
declarative projection for permanent scene outcomes that must survive their caller.

## Exact document

`StoryLibrary` has `schemaVersion:2`, and arrays `facts`, `places`, `characters`,
`dialogues`, `knowledge`, `trades`, `soundscapes`, `projections` (empty by default). The limits are
512 facts, 128 places, 256 characters, 128 dialogues, 128 knowledge records,
256 trades, 128 soundscapes, 64 projections and 2048 total top-level definitions.
Names, titles, dialogue options and subtitles are single-line; prose bodies may contain newlines. IDs use lowercase letters/digits/underscore/dot/dash, 1..64 characters; no `..` or
trailing dot. Names/titles are readable player text (at most 160 characters).

- Fact: `{id,scope,type,initial,min:-1000000,max:1000000}`. Scope is WORLD,
  PLAYER, CHARACTER or PLACE; type BOOL, NUMBER or TEXT. Values use the bundle's
  `kind` discriminator, e.g. `{"kind":"bool","value":false}`,
  `{"kind":"number","value":0}`, `{"kind":"text","value":"unknown"}`.
  Only these primitives are accepted. Numeric facts stay in their declared finite
  bounds within +/-1000000. Text is at most 4096 characters.
- Fact reference: `{id,subject?}`. WORLD/PLAYER omit subject; CHARACTER/PLACE use
  a declared character/place ID or omit it to use the current speaker/place.
  A missing contextual subject is not redirected to another actor or place.
- Conditions: `{"kind":"always"}`, `{"kind":"all","conditions":[...]}`,
  `{"kind":"any","conditions":[...]}`, `{"kind":"not","condition":...}`,
  `{"kind":"compare","fact":{"id":"bridge_fixed"},"comparison":"EQ",
  "value":{"kind":"bool","value":true}}`. Comparisons are EQ/NE/LT/LE/GT/GE;
  ordered comparisons require numbers. At most 64 nodes, depth 8. Empty ALL is
  true, empty ANY false. An unresolved/wrong-type fact stays unknown under NOT/NE.
- Change: `{fact:{id,subject?},value,mode:"SET"}`; mode SET or numeric ADD.
  Transactions have at most 32 changes and no duplicate fact-subject writes;
  combined dialogue-option + trade changes must also be conflict-free.

## Places and residents

Place: `{id,name,description,structure,discoveryRadius:24,
discoverWhen:{kind:"always"},onDiscover:[],clue:"",soundscape?,required:false}`.
`structure` names a real structure definition; radius is 1..128. Description is
1..2048 characters and clue is 0..1024. Each place needs a pure typed marker declaration (omit `character`)
in its matching structure. This is not evidence that a generated instance exists.

Structure module schema 3 adds:

```json
{"kind":"story_anchor","at":{"x":3,"y":1,"z":3},"place":"village","character":"keeper"}
```

The marker's `at` is an authored feet position with two traversable cells and a
supporting floor. A place marker omits `character`; separate resident markers name
their character and the same place. A resident marker never substitutes for the
pure place marker. The character must belong to this place. Its marker associates
with the closest actually registered matching place within 256 blocks, only across
loaded space; equidistant ambiguous matches are deferred. Keep each settlement's
resident markers unambiguously closer to its own place anchor than another copy.
SDK: `a.storyAnchor(new Vec3i(3,1,3), "village"[, "keeper"])`. Component copies,
rotation, drawing normalization and storage tiling move coordinates but retain
the exact place/character IDs. Native export emits only typed minecraft:marker
entities; actual generated marker position/UUID identifies the world instance.
Never invent a visited place or coordinates from its declaration/terrain anchor.

Character: `{id,name,creature,place,dialogue?,spawnWhen:{kind:"always"},
onDeath:[],respawnTicks?,routines:[]}`. Uses a real existing custom creature host
and a character StoryAnchor. `respawnTicks` is null or 20..1728000. No new mob host,
arbitrary entity NBT or native registry IDs are accepted as a creature reference.

Routine: `{id,startTick,endTick,offset:{x:0,y:0,z:0},activity,
condition:{kind:"always"},speed:0.6}`. Up to 32 per character; distinct IDs and
non-overlapping time windows. Tick-of-day endpoints 0..23999, start differs from
end; ranges may wrap midnight. Destination offset has radius <=24, speed 0.1..2.
Routines and spawn conditions never depend on PLAYER facts. Native absolute home
comes from the marker, not a guessed world coordinate. On chunk re-entry derive
the activity from world time; no forced-loaded continent simulation is implied.
Ability programs retain priority over routine navigation.

## Dialogue, trade and knowledge

Dialogue: `{id,start,nodes:[...]}`; 1..128 uniquely named nodes, all structurally
reachable from start, each able to reach an exit. Node: `{id,text,options,
condition:{kind:"always"}}`; text <=4096, 0..8 choices. Option:
`{id,text,next?,condition:{kind:"always"},changes:[],trade?,program?}`; text <=512,
next null closes. Trade and program name actual definitions. Conditions determine
visible options server-side; client choice tokens are not fact mutations.

Trade: `{id,name,inputs:[{item,count:1}],outputs:[{item,count:1}],
condition:{kind:"always"},changes:[],cooldownTicks:20,maxUsesPerPlayer:0}`.
Use 1..16 entries on each side, canonical native IDs or `worldsmith:item/<id>` /
`worldsmith:content/<blockId>`, counts 1..64 and no duplicate item entries per side.
Cooldown 1..1728000 ticks; use cap 0..1000000 (0 means unlimited). Runtime preflights
the whole inventory and facts, writes use/cooldown debt once, and never drops an
overflow remainder. A chosen program is reserved before payment and queued only
after transaction acceptance. Conditions or choices are not executed by the UI.

Knowledge: `{id,title,text,truth:"FACT",discoverWhen:<condition>}`. Truth is FACT,
LEGEND or BELIEF, text <=8192. `discoverWhen` is required. Only discovered records
are sent to the player; a narrator's legend is not silently asserted as fact.

Soundscape: `{id,layers:[...]}` (up to 16 distinct layer IDs). Layer:
`{id,sound,condition:{kind:"always"},volume:0.4,pitch:1,periodTicks:200,
fadeTicks:40,priority:0,music:false,subtitle:""}`. Sound must be a registered native
sound event, volume 0..1, pitch 0.5..2, period 20..24000, fade 0..1200 and <=period,
priority -100..100, subtitle <=256. Selection uses actual current place and facts;
the client owns stable repetition/crossfade and user comfort controls.

## Authoring and verification

### Durable scene projections

Projection: `{id,place,condition,blocks:[{offset:{x:0,y:3,z:2},
expected:{block:"minecraft:polished_deepslate",properties:{}},
desired:{block:"minecraft:sea_lantern",properties:{}}}],onApplied:[]}`.
`place` is a declared place; application binds to each actual marker UUID, not a
guessed coordinate. `condition` is required. Conditions and `onApplied` may only
use WORLD facts or this projection's PLACE facts (subject omitted or its own
place ID); no arbitrary player, character or other place instance is selected.
The projection id plus actual marker UUID owns a permanent receipt.

Use 1..32 distinct block offsets, each within a 32-block sphere. `expected` and
`desired` are **exact full native states**, not partial predicates: omitted native
properties use defaults, and both offsets and states rotate with the real marker.
Native blocks or `worldsmith:content/<blockId>` aliases are accepted. Logical
blocks preserve bound material/light properties; only declared horizontal facing
may be selected. At most 16 bounded native properties per state. Blocks must be
dry, data-free and non-falling. Desired states are air or static full-cell
construction: plain native blocks, ordinary pillars/logs, amethyst/glass families
and logical full-cell block hosts; no random-ticking/dynamic-shape states. Native
support/placement state machines (such as torches, doors, sponges, TNT, carved
pumpkins and powered devices) are rejected before binding, not acknowledged and
then allowed to disappear in the batch's own neighbour notifications. Runtime also
protects block entity NBT, negative hardness, world border/bounds and occupied
desired collision volumes.

All targets, loaded collision neighbours, facts and receipt capacity are checked
before writes. A third state defers the complete batch and preserves the edit.
An expected/desired mixture without a receipt is recoverable: already desired
cells are not paid for or replayed, and `onApplied` commits once after the whole
batch succeeds. A completed receipt records successful application history, not
an eternal constraint against later player/native-world changes. It is final even
if a player later edits the scene. Projections never grant inventory or restart
a reward-bearing program.
At most 64 candidate pairs and two loaded pending batches are considered per
level tick. An unloaded marker or target/halo chunk spends only a scan slot, never
an application slot; no chunk is force-loaded. The cursor survives append-only
place registrations. For a stable ring of N pairs, a sole loaded eligible batch
is observed within ceil(N/64) ticks; with persistent loaded conflicts, every pair
is still visited within ceil(N/2) ticks. N is bounded by 2048 places times 64
projections (the extreme bounds are 2048 and 65536 ticks respectively, not wall
time guarantees). The actual sample ring has two pairs. Native state stores at
most 32768 projection receipts.

This guarantees in-process compensation and retry after an interrupted batch
whose receipt is absent. Native chunk files and SavedData are separate storage
units: this is not a power-loss-atomic cross-file transaction. Normal native
save/reopen, expected/desired recovery and already-receipted player edits require
separate evidence. Expose permanent ending dialogue/knowledge only from an
`onApplied` acknowledgement, never solely from payment or a queued cosmetic call.

For shared outcomes, keep WORLD result separate from PLAYER promise and personal
witness/response. Already accepted opposing promises and late arrivals need an
authored completion path; do not rewrite their promise or charge the deciding
transaction again. Use a declared `onClaim` stage fact when a later conversation
must actually happen after claiming the prior journey.

Commit via `worldsmith_put_content_modules(sessionId, expectedRevision, modules)`
with `modules.story`. Use the latest shared draft `expectedRevision`; stale writes
are rejected without replacing newer work. Story content
kinds are `story_fact`, `place`, `character`, `dialogue`, `knowledge`, `trade`,
`soundscape`, `story_projection`; use real typed references, linked theme beats and named design
targets. `story_reference` links mirror actual typed references, and dialogue program links also use `invokes_ability`. Existing abilities still own local effects; persistent story records own
world consequences. Static catalog links and generated placement are separate
proofs. Verify real marker generation, walking routes, dialogue secrecy and
replay handling, full-inventory transactions, death/re-entry, save/reload,
world-time routine changes and player-facing presentation.

Older bundle formats are rejected without rewriting existing packages or saves.
This format installs a regional story runtime, not continent-wide ecology,
faction simulation or a universal multiplayer voting system.

Player-visible writing follows `PlayerTextPolicy`; a `PLAYER_TEXT_ENGINEERING_LEAK`
finding requires revising new lore, dialogue, names or subtitles. Put implementation
and verification limitations in authoring diagnostics/receipts, never NPC speech.
