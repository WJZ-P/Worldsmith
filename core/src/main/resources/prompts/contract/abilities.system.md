# Worldsmith abilities — portable programs, schema 1, bundle format 10

Abilities are AI-authored source code, not a list of built-in skill types. Write
the actual control flow and submit it in `worldsmith_put_content_modules` under
`abilities`. The same immutable program may be invoked by a creature, item or
mechanic. Functions, handlers, state, loops and capability calls compose gameplay;
do not request a new Java switch branch for every attack design.

## Document and host bindings

Read `worldsmith_get_content_draft(sessionId)` first, then submit the complete
library with `worldsmith_put_content_modules(sessionId, expectedRevision,
modules:{abilities:AbilityLibrary})`. Use the latest shared `expectedRevision`;
on a revision conflict reread and merge rather than overwrite another program.
Texture, creature, structure and module edits share this revision. Publication
also accepts inline `abilities`; save repaired inline drafts before retrying so
source review and the frozen candidate refer to the same actual code.

```json
{
  "schemaVersion": 1,
  "programs": [{
    "id": "echo_marks", "name": "Echo marks",
    "source": "on start { fx.message(\"The marks awaken.\"); wait 10; }",
    "maxTicks": 1200, "maxOperations": 32768, "requires": {}
  }]
}
```

`programs`: at most 64. IDs are normalized lowercase local IDs, 1..64 characters;
names 1..128, source at most 32768 characters. `maxTicks` 1..12000;
`maxOperations` 128..65536. `requires` pins capability names to supported integer
versions; otherwise version 1 is implicit. An `on start` handler is required.
Unknown capabilities, invalid calls/source and unsupported versions fail before
publication. Source is included and content-addressed in the bundle's required
`abilities/abilities.json` document, compiled once at native preparation, and
reopens with no external compiler or AI service. The manifest may name another
valid JSON path. Empty libraries are valid when no ability is promised.

Bindings:
- Creature library/recipe schema 4: `ability:{program:"echo_marks",range:8.0,
  cooldownTicks:20,cancelOnTargetLoss:true}`. Range is finite 0.5..24 and cooldown
  1..72000. A HOSTILE creature invokes it through the shared runner; present
  ability replaces automatic melee. Ordinary hostile species can use it, not
  just bosses. Boss presentation may use `boss:{phases:[],barTitle:"..."}`;
  script state controls arbitrary phases instead of a fixed health-stage count.
- Item library schema 3: `actions:[{trigger:"USE",cooldownTicks:40,
  effects:[{kind:"run_program",program:"echo_marks"}]}]`. It is the sole effect
  of a non-consumable USE action. Existing quantity/durability costs apply after
  an invocation reservation succeeds. Compilation or reservation failure costs
  no item; later script errors do not undo already committed use costs/effects.
- Mechanic action `{kind:"run_program",program:"echo_marks"}` (at most one per rule). Read-only
  inspection performs preflight only. Activation reserves before world writes;
  launch is committed after the mechanic ledger transaction. Rollback releases
  the reservation. Later delayed script effects are a separate lifecycle, not
  a global multi-tick transaction.

Programs receive `self`, `target` and `origin`. `self` is the creature or using
player; mechanic origin is the matched anchor. `target` is the initial entity
handle (or null), not an implicitly retargeted future victim. Use
`entity.target(self)` explicitly for live targeting. Branch on null before
passing optional entities/positions to typed calls.

## AbilityScript source grammar

```text
fn strike_at(mark) {
    let area = shape.sphere(mark, 2);
    for victim in world.entities(area) {
        if (world.visible(self, victim)) { combat.damage(victim, 6); }
    }
}
on start {
    if (target == null) { return; }
    let movement = 0;
    if (entity.kind(self) == "creature") {
        movement = control.claim(60, 100);
        if (movement == 0) { return; }
    }
    motion.stop(self);
    let marks = [];
    repeat 3 {
        let mark = entity.position(target);
        if (mark == null) { return; }
        marks = list.append(marks, mark);
        fx.telegraph(shape.sphere(mark, 2), 30, "amber");
        wait 10;
    }
    wait 20;
    if (movement != 0 && !control.held(movement)) { return; }
    for mark in marks { strike_at(mark); wait 6; }
    if (movement != 0) { control.release(movement); }
}
on hurt { state.interrupted = true; }
```

Declarations: `on event { ... }`, `fn name(args) { ... }`. Statements: `let x =
expr;`, `x = expr;`, `state.key = expr;`, `if (expr) {...} else {...}`, `while
(expr) {...}`, `repeat expr {...}`, `for value in list {...}`, `wait ticks;`,
`return expr;` or bare `return;`, and calls. Expressions: number, bool, text,
null, lists `[a,b]`, local/state variables, functions, arithmetic, comparison,
short-circuit boolean operators. Comments use `//` or `/* ... */`. General
object members are not syntax: use `vector.x(v)` / `list.get(xs,index)`.

Functions and event handlers share invocation `state` (undefined keys are null)
but have separate local frames. Waits preserve frames and resume cooperatively.
Events queued during a wait can update state before the continuation; test it
after the wait and choose interruption/weakness logic yourself. Events:
`hurt`, `block_break`, `projectile_hit`, `signal`; payload variables
`event_name`, `event_entity`, `event_position`, `event_amount`, `event_tag`,
`event_data` are null where absent. `signal.emit(tag,data)` queues `on signal`
for this invocation, not arbitrary cross-world event execution.

`projectile.emit(position,velocity,gravity,lifetime,tag)` creates a resource
owned by this invocation. It deals no automatic damage: `on projectile_hit`
uses tag/position/entity to decide damage, splitting or other behavior. Active
projectiles and cues keep an idle instance available for events. Cancellation,
unload, owner death or unbinding removes owned resources and future instructions.

## Capability SDK and extensions

The contract response `capabilities` is generated from the actual compiler
registry and lists **every installed function signature**, argument/result type,
effect flag, version and a per-function `description`. Each standard description
starts with its positional parameter names, then states results, bounds and
host-specific behavior. Read it rather than inventing calls. Pure functions
include vector/math/list operations and extensible region construction; native
functions provide entity queries, visibility, blocks, damage/heal, status,
movement, cues, projectiles and signals. `shape.sphere` and `shape.box` are
geometry, not whole attacks. Region union/translation use the same region values
for visible telegraphs and entity queries.

### Read result and lifetime semantics before composing calls

- `ANY` is a dynamic value, not a guarantee of an entity/vector. For example,
  `entity.position` returns VECTOR or null; `entity.target` returns ENTITY or
  null. Check before passing the result to an ENTITY/VECTOR parameter. Only
  parameters declared ANY accept null. `entity.alive(null)` is false;
  `entity.health_ratio` returns 0 for an unavailable living handle, while
  `entity.kind` returns `other` for unresolved handles.
- Lists are immutable values: assign `list.append`'s result. `list.get` uses
  zero-based integer indices; an index past the end returns null, but a negative
  or fractional index is an error. Trigonometry uses radians. Box dimensions
  are half extents, not full lengths.
- Core can construct sphere radii/box half extents up to 32. Native region
  use is stricter: half extent <=16, center within 24 of invocation origin,
  loaded chunks and world border. Merely constructing a REGION does not prove
  it is usable at the intended location. Scoped entity handles resolve within
  32 of origin; position inputs are checked within 24.
- A false native result commonly means no available target, a protected target
  or a rejected action; malformed arguments, unknown IDs and out-of-range
  values instead stop the invocation with a diagnostic. A true result means
  the documented operation was accepted, not that a player saw/heard it or
  navigation reached its destination. Damage/status/push have no implicit LOS;
  query `world.visible` when the intended rule requires it.
- Stop/face/navigation are self-only. A custom creature requires this invocation's
  explicit live control token; navigation supports custom creatures only. Self
  players need no NPC token for stop/face. Blink is player-only. Push **adds** an impulse rather than setting final
  velocity. Other-player PvP/team protections still apply; self status/push do
  not become disabled merely because PvP is off.
- `control.claim(priority,ticks)` returns an owned numeric MOVE+LOOK token or 0
  when unavailable: priority integer 0..100, ticks 1..1200, self custom creature
  only. Equal priority keeps the first owner; higher priority preempts; the same
  invocation may replace its token. Tokens share 8/instance and 128/level resource
  limits. `control.held(handle)` tests the exact live token; `control.release(handle)`
  releases only this invocation's token. Queries, sound, state, poses, event
  observation and invocation commit do not implicitly claim movement or freeze
  resident routines. Claim before a creature's windup; shared player-host source
  should branch on entity.kind(self), not return merely because a player gets 0.
- A navigation call builds a candidate first, rejecting null/finished paths before
  moveTo; the accepted exact Path belongs to the current token. Retiring an older
  token leaves a newer owner's Path alone. Expired/preempted tokens never resume
  old routes. Losing control does not end the source or roll back independent
  effects, so delayed attacks should test control.held again after waiting.
  Native dialogue outranks ordinary priority<80 control; successful emergency
  priority>=80 control closes the session before movement and defers reopening.
- Telegraphs, projectiles and unexpired pose/caption leases keep an idle
  invocation alive. They do not implicitly suspend subsequent instructions;
  use `wait` for sequencing. Expiry, cancellation and the program's maxTicks
  remain lifetime boundaries. `fx.clear` clears a telegraph handle only.
  A pose is visible only on a custom creature; a caption is displayed on a
  custom Boss bar, not as a player overlay. Use `fx.message` for that overlay.
  Across invocations on one actor, each presentation channel shows its latest
  unexpired write. When that lease ends/expires, an older still-live pose/caption
  lease becomes visible again; ending an older lease never clears a newer one.
  Only ending the last pose lease resets idle. A repeated write within one
  invocation replaces its own lease rather than stacking its prior values.
  Messages and one-shot sounds do not create lifetime leases.

Core extension point: `AbilityCapabilities.standard().extend(spec)` returns an
immutable registry, rejecting duplicates. Compile/validate with that snapshot;
an `AbilityHost` implements native capability calls. A native provider registers
its signature plus implementation before preparation. New functions require a
provider, never arbitrary engine commands, reflective classes, files or network
access. New region providers implement validation, bounds, containment and
outline through the region registry. Provider version mismatch fails preparation.

Per-program budgets are enforced while executing, not by banning dynamic loops.
At most 32 functions/16 arguments, 32 call frames, 16 handlers and 8192 compiled
instructions; event queue/fibers at most 32. Native runtime budgets additionally
bound operations per tick, active instances and resources per world. Entity
handles are opaque IDs, never mutable game objects. Values are finite/bounded:
numbers absolute <=1e9, strings <=4096 characters, lists/maps <=64 entries,
depth <=8 and total nodes <=2048. State <=64 keys and <=64 KiB serialized.
Runtime type/range/size errors stop the invocation and produce a diagnostic;
retired effects are not replayed.

The host restricts effects to the invocation's world and loaded nearby context:
queries/regions stay near the origin, target effects are bounded, damage/heal
0..100, status <=1200 ticks/amplifier <=4, blink <=8, projectile velocity <=2.
Visibility is an explicit query, so source should guard attacks with its intended
LOS rules. Native PVP/team/creative/spectator protections still apply.

Only shared script state and host cooldown/recovery debt persist. Active PCs,
locals, target handles, cues and projectiles do not resume after a save/reload.
Already applied damage/status is not rolled back after interruption or failure.

## Authoring review and proof

Use `ability/<id>` targets in WorldBible/ModuleBrief/WorldDesignPlan and
`invokes_ability` links from item/creature/mechanic to ability. Review actual
`/modules/abilities/programs/<index>/source` and the real host binding, including
input nullability, movement ownership, telegraph/damage geometry, interrupt
conditions, cooldown, cleanup, budgets and persistence. Narrative descriptions
or a valid link do not prove combat behavior. Add tests with different branches,
events, waits and hosts; source compilation, pure VM tests and native gameplay
observations are distinct evidence.

## Generic native event bindings

Items schema 4 and creatures schema 5 append `abilityBindings`, each entry:
`{id,program,startOn:[names],listenTo:[],cancelOn:[],cooldownTicks:20,range:8.0,intervalTicks:1}`.
Items additionally accept `maxUseTicks` (0 immediate, 1..12000 held). At most 16
bindings per host, distinct local IDs; startOn is nonempty, each event list has
at most 8 distinct names, all three sets disjoint. Range is finite 0.5..24 and
cooldown 1..72000. The framework reports installed item/creature hook names.

Creature schema 6 allows a `tick` subscription to declare `intervalTicks` 1..1200.
The default 1 preserves physical per-tick inputs; authored observation normally
uses 20..40. Actor UUID and binding ID deterministically stagger pulses. Other
event types and item bindings must use 1. Periodic starts leave sixteen global
invocation slots and one per-actor slot for interactive/reaction work; an already
running subscriber still receives its due tick. Use short source invocations and
durable `state`/shared values, not a spawn-only infinite observer that consumes a
slot until `maxTicks` expires. Observation does not claim MOVE+LOOK.

Read-only `entity.identity`, `entity.environment`, `entity.home`, `motion.status`
and `world.sample` expose bounded actual identity/environment/path/block metadata.
Read their installed signatures for exact map keys and null semantics. Home and
path metadata do not grant permission to load a distant chunk or act outside the
invocation radius. Perception policies (selection, hysteresis, memory) remain
source code, not new native flee/forage/herd enums.

startOn starts when this binding owns no active instance; otherwise it emits the
same event. listenTo emits only to its already started instance. cancelOn hard
cancels that exact owned UUID and does not execute the cancelled event's handler.
Ownership is not inferred from a program name: another binding or use nonce must
not adopt a replacement invocation. Runtime state/cooldown and the one-active-
instance-per-actor/program limit remain in force across different bindings.
on start is queued before the initial host event; server END executes both using
normal cooperative ordering. Native input callbacks themselves execute no source.

Item hooks: use_start/use_tick/use_release/use_cancel/melee_hit/equip/unequip/
interact_entity. Creature hooks: spawn/tick/enter/exit/interact_entity/hurt.
Passive NPCs use creature event bindings without an attack target. Existing
CreatureDefinition.ability remains a combat/chase policy. General runtime hurt
already reaches active programs; a hurt binding adds startup/cancellation without
double delivery, after damage rather than before health loss.

Held input uses real native use state. Its nonce binds scope, logical item, hand,
selected hotbar slot and exact invocation UUID; changing to another logical item
with the same shared native Item host is cancellation, not continued holding.
Held tick/release/cancel require maxUseTicks>0. Normal release gets a short handler
window before its observation lease expires. Equip-started observers may renew
after maxTicks/cooldown while still equipped; wear/name/count mutation is not a
new equip event. Removal runs its declared exit policy and clears owned observers.
All observation leases retain normal lifetime/resource/concurrency budgets.

Bindings have no implicit quantity/durability costs. Each input chooses one model:
use_* conflicts with consumable, held use conflicts with old USE actions,
use_start conflicts with any USE action, and melee_hit conflicts with an old
MELEE_HIT action. Other entry points can coexist; source can compose effects and
resource/inventory costs explicitly. melee_hit requires actual native weapon/tool
equipment and runs from postHurtEnemy, not an attack-intent callback.

Host payload reuses event_entity/position/amount/tag/data. event_tag is the binding
ID, event_amount is elapsed held ticks or the native hurt callback amount where
applicable. event_data map contains binding_id/source_kind/source_id/slot/nonce/
reason; slot uses native lowercase names such as mainhand/offhand. Non-use events
have an empty nonce. Current general runtime hurt may lack this binding metadata.
Use map.get and null checks; do not invent new globals for these data fields.
