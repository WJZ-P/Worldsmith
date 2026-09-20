# Discovered branching quests

The quests module is a bounded server-owned story DAG. It observes verified kills,
actual deliveries, committed mechanic activations and shared story conditions.
Dialogue belongs to `story`; quests do not install a second scripting language.

## Installed contract

`QuestLibrary` has schemaVersion=2 and quests (0..64). Multiple roots and successors
are supported. Use ALL/ANY prerequisite semantics, never array order as a dependency.
Older bundle formats are rejected; old quest schema 1 is rejected too.

Publication uses bundle format 10. Author this module through
`worldsmith_put_content_modules` with the shared `expectedRevision` CAS token;
read the current draft first and do not overwrite another author's revision.
Player-facing titles, descriptions, objective labels and rewards follow
`PlayerTextPolicy`; `PLAYER_TEXT_ENGINEERING_LEAK` rejects engine/version jargon.
Keep implementation notes in authoring diagnostics/receipts, not in the player's story.

Quest fields: id, title, description, prerequisites=[], objectives, rewards=[],
themeBeat=null, optional=false, manualAccept=false, exclusiveGroup=null,
discoverWhen={kind:"always"}, availableWhen={kind:"always"}, onAccept=[],
onClaim=[], destination=null, prerequisiteMode="ALL".

- IDs are normalized lowercase local IDs of 1..64 characters.
- At most 16 distinct prerequisites, 8 objectives, 16 reward stacks and 32 changes
  per transition. At least one objective is required; each count is 1..1024.
- Titles are 1..160 printable characters, descriptions 1..4096 plain-text characters.
- Prerequisites reference real quests; cycles are rejected. A prerequisite means
  **claimed**, not just objectives met. ANY is the merge for alternative outcomes.
- An ALL merge of exclusive outcomes is invalid; don't make the player complete
  both choices. Rejected branch descendants are excluded according to ALL/ANY.
- `exclusiveGroup` is a stable local label. Such quests require manualAccept=true.
  The player's explicit ACCEPT atomically selects one branch and applies onAccept.
  Merely discovering, reading or tracking an offer never makes that choice.
- `optional=true` marks a side journey outside campaign completion. Objective
  optional=true excludes only that objective from the necessary claim condition.
- `destination` references a declared story place. It is not a generated coordinate;
  guidance uses only actual discovered place instances, never invented positions.

## Discovery and acceptance

All prerequisites must satisfy prerequisiteMode before discovery. `discoverWhen`
then controls the first reveal. Once revealed, discovery is persistent: revisiting
or reloading does not erase knowledge. Undiscovered titles, descriptions, targets,
rewards and destinations are absent from the server's journal packet.

`availableWhen` is checked for acceptance, delivery and claiming, and gates active
kill credit. Keep it true for the full intended active phase, including after your
onAccept effects. `manualAccept=false` automatically accepts an available discovered
quest; its onAccept still runs exactly once. Manual offers support ACCEPT and
DECLINE; decline is a reversible deferral of an **unaccepted** offer, not an undo of
an accepted commitment. The journal explicitly confirms an exclusive choice.

## Objectives

- `{kind:"kill_creature",creature:"local_creature",count:1,optional:false}`:
  credits real server deaths attributed to the player while the quest is accepted
  and available. One kill credits every matching active quest, never hidden or
  unaccepted quests. Program-produced kills still require native kill attribution.
- `{kind:"deliver_item",item:"worldsmith:item/local_item",count:3,optional:false}`:
  explicit journal delivery consumes the actual matching inventory items. Merely
  carrying an item is not delivery. Multiple contributions and stack boundaries work.
  Regular DELIVER takes only required goals. DELIVER_OPTIONAL is a separate explicit
  intent; the journal offers it after required deliveries, so optional goods are not
  silently consumed by an ordinary contribution.
- `{kind:"activate_mechanic",mechanic:"local_mechanic",count:1,optional:false}`:
  observes a successfully committed interaction. Lifetime facts include activations
  before discovery/acceptance; reading the journal never runs the mechanic.
- `{kind:"fact",label:"Speak with the keeper",condition:{kind:"compare",
  fact:{id:"met_keeper"},comparison:"EQ",value:{kind:"bool",value:true}},optional:false}`:
  reads a declared StoryCondition through the shared story runtime. The count is
  always one; author no `count` field. Fact values are not duplicated in quest saves.
  Use the exact AbilityValue JSON returned by the story contract/schema when authoring.

Kill and delivery counters persist with player inventory. Mechanic activations have
one lifetime ledger. Fact predicates use the story ledger and are re-evaluated when
facts change. A committed claim remains historical completion even if onClaim changes
its former condition. Missing implicit character/place context is not a matching fact;
use explicit stable subjects where the quest is not tied to the player's current place.

## Rewards and consequences

`rewards` contains ordinary item stacks `{item,count}`. Native items and logical
`worldsmith:item/<id>` or `worldsmith:content/<blockId>` are supported. Each reward
entry fits the item's real stack limit. No arbitrary native slot IDs, NBT or commands.
`onAccept` and `onClaim` are StoryFactChange lists with SET/ADD and declared types.
Duplicate writes in one transition, unknown facts and invalid subjects are rejected.

Server preflights inventory and story changes, verifies the current revision, then
commits inventory, facts and quest attachment in one server-thread transaction.
Failure rolls back the prepared changes. Full inventory does not drop rewards or
commit a branch consequence. Transport failure after commit does not replay rewards.
This is server-thread atomicity; independent Minecraft save files are not advertised
as a crash-atomic database. Request IDs and expected revision prevent replay/stale intent.

## Player presentation and proof boundary

The journal lists only discovered quests, with ACCEPT/DECLINE, delivery/claim,
optional-objective labels, tracking and a link to the world knowledge/place journal.
The server supplies campaignComplete from the full non-excluded required DAG; an empty
or all-claimed **filtered** list is never treated as campaign completion by the client.
Generated native advancements are hidden completion-gallery leaves, not a lossy
single-parent rendering of the DAG. Only claimed chapters become visible there;
they remain a read-only projection with no second rewards or progress authority.

Static material reachability keeps exclusive route inventories separate, respects
prerequisites and finite costs, and can report a bounded proof-budget diagnostic.
It checks material routes, not arbitrary dialogue-condition satisfiability or a
particular generated site. Fact conditions, destination identities and changes use
shared story validation; test the actual authored choice/discovery/reload loop too.
Arrival presentation reuses installed theme text and current discovered quests;
players may disable it. No intro/toast/tip fields belong in QuestLibrary.
