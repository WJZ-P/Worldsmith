# Worldsmith world-generation agent: architecture policy 1

This is an ACTIVE DESIGN TASK. After terrain and biome planning, independently
develop architecture for THIS player's world. Infer plausible functions, inhabitants,
infrastructure, ritual places and ruins from the theme. Extend it thoughtfully;
do not import the same medieval village or a shared asset catalog into every world.

## New guided world requirements

- Design at least TWO distinct building groups, normally 2..4. Vary purpose,
  centerpiece, silhouette, spatial organisation and encounters, not just names/colours.
- Include at least ONE independently distributed structure outside the group ids.
  A compatible design may also occur as a group member; groups and independent
  encounters must coexist rather than making all architecture compounds.
- At least ONE group is a monumental LANDMARK: the strongest architectural expression
  of this world. Give it a focal composition, scale hierarchy, approach, secondary
  buildings and explorable spaces. A giant solid block is not a magnificent building.
- Every root and child blueprint declares lighting. Occupied interiors must remain
  readable at night; do not depend on skylight, shaders or the player's gamma setting.

No fixed style, stock catalog or twenty-building quota is prescribed. Barren, alien,
oceanic and floating worlds need their own interpretations of groups and landmarks.
The world theme governs architecture; do not ask the player to name every building.

## MCP workflow

1. `worldsmith_begin_world` supplies the procedure and contracts.
2. Read the pack template/style; design terrain, biomes and features; analyze distribution.
3. Read this document with `worldsmith_get_contract` id `architecture` when needed.
4. Submit `worldsmith_plan_architecture(sessionId, architecture)` before building.
5. Read contract/draw, build individual Java DrawPrograms with `worldsmith_build_drawing`,
   wait using `worldsmith_get_drawing_job`, inspect actual image content from
   `worldsmith_preview_drawing`, revise, then reference drawing ids in contract/structure
   metadata. Submit complete group/independent definitions with `worldsmith_put_structure`.
   Legacy JSON primitives remain a compatibility route.
6. Run `worldsmith_validate_architecture(sessionId)` against the COMPLETE draft set.
   Repair named diagnostics, replacing affected drafts or the plan rather than restarting.
7. `worldsmith_write_pack` rechecks policy before writing any pack files. Omitting
   structures uses the session drafts; an explicit library replaces them completely.
8. `worldsmith_finish_world` re-reads the pack and requires native NBT readback, full
   data-pack reload and preset activation. WAITING_NATIVE_CONTEXT needs Create World
   to be opened; report the action and pause, not repeated blind polling.

Replacing a plan preserves drafts but invalidates its previous publication. The plan
is saved under the structures index's architecture field and participates in hashing.
Sessions/plans/source revisions/drafts persist atomically; list/resume them after restart.
Interrupted jobs need explicit retry and renewed in-game source approval. Legacy packs remain loadable;
new guided publications must satisfy this policy.

## Architecture document

This is a field-shape example, not a prescribed setting or an executable building.
All structure and blueprint ids must match the definitions you subsequently submit.

```json
{
  "policyVersion":1,
  "worldTheme":"The player's setting, materials, atmosphere and architectural logic",
  "groups":[
    {
      "structure":"theme_monument", "title":"Theme-defining monumental complex",
      "role":"LANDMARK", "centerpiece":"monument_court",
      "themeFit":"Why this is the strongest expression of the world",
      "layoutIntent":"Approach axis, main mass, secondary courts and circulation",
      "distinction":"How it differs from the other groups",
      "discovery":"Suitable biomes, rarity, terrain requirements and discoverability",
      "required":[
        {"role":"central composition","blueprints":["monument_court"],"minCount":1,"maxCount":1},
        {"role":"supporting halls","blueprints":["east_hall","west_hall"],"minCount":2,"maxCount":2}
      ],
      "optional":[
        {"role":"outer pavilion","blueprints":["outer_pavilion"],"minCount":0,"maxCount":1}
      ]
    },
    {
      "structure":"theme_outpost", "title":"Smaller functional group",
      "role":"REGULAR", "centerpiece":"outpost_hub",
      "themeFit":"Express the setting through ordinary life or another plausible function",
      "layoutIntent":"An asymmetric cluster rather than a ceremonial axis",
      "distinction":"Different purpose, scale, organisation and silhouette",
      "discovery":"Suitable biomes and a more common candidate distribution",
      "required":[
        {"role":"hub","blueprints":["outpost_hub"],"minCount":1,"maxCount":1},
        {"role":"lodgings","blueprints":["lodging"],"minCount":1,"maxCount":3}
      ],
      "optional":[]
    }
  ],
  "standalone":[
    {"structure":"theme_waystation","purpose":"An independently encountered shelter or landmark","themeFit":"How its form belongs to this world"}
  ]
}
```

groups has 2..12 entries; standalone has 1..36. All top-level structure ids are
distinct and classified. The group's required centerpiece is its root blueprint,
which may represent a street or courtyard rather than a large building.

Each group has at most sixteen required+optional member rules. A rule's blueprints
list contains interchangeable member designs. Assign each blueprint to one role only.
Required minCount>=1, optional minCount=0, maxCount=1..16 and >=minCount. Required
minima together fit sixteen pieces. Counts INCLUDE the root. Every emitted member
in EVERY precompiled plan must have a declared role and satisfy its count range.

The plan does not generate pieces by itself. Provide real assembly.pieces, weighted
assembly.pools, ports and either frozen drawing references or legacy build operations. Required ports make compulsory connections;
optional ports/pools and bounded variants select optional members. An optional outgoing
port may add `"chance":0.55` to include it in only some plans. Required or non-spawning
ports keep chance=1. Pool weights choose WHICH member, chance chooses WHETHER to attach.
Both are deterministic per precompiled plan. Repair actual assemblies when counts
disagree with the plan; role ranges alone do not create randomness.

Counts apply to logical buildings, never their storage fragments. Every group variant
has >=2 buildings. Every LANDMARK variant has >=3 buildings, >=8192
non-air cells, and horizontal span >=64 OR height >=32. These are minimum scale
checks, not an aesthetic score. Do not inflate a solid plinth to pass them: spend the
detail budget on focal masses, secondary spaces, circulation, materials and silhouette.
Existing structure/compiler/worldgen budgets still apply.

The plan and the assemblies are two halves of one statement and drift apart
easily. Whenever a port, pool or piece changes while repairing an assembly,
resubmit the plan in the same pass: a rule naming a blueprint the group no longer
builds is `ARCHITECTURE_UNKNOWN_MEMBER`, and a required role that no port can fill
is `ARCHITECTURE_MEMBER_COUNT_MISMATCH` on the affected variants. Keep each role's
`maxCount` within what `assembly.maxPieces` and the ports can actually attach.
`maxRadius` must fit the root and its members side by side — measure it against
the real drawing sizes, since a required port that cannot place its piece fails
as `REQUIRED_PORT_UNCONNECTED`.

## Lighting policy

Every blueprint needs lighting. For occupied spaces, for example:

```json
"lighting": {
  "mode":"READABLE", "minimum":8,
  "spaces":[{"from":{"x":2,"y":1,"z":2},"to":{"x":8,"y":3,"z":8}}],
  "sources":[
    {"at":{"x":3,"y":4,"z":3},"level":15},
    {"at":{"x":7,"y":4,"z":3},"level":15},
    {"at":{"x":3,"y":4,"z":7},"level":15},
    {"at":{"x":7,"y":4,"z":7},"level":15}
  ]
}
```

ALSO DRAW actual light-emitting blocks at these positions, such as lit lanterns
attached to suitable ceiling geometry. Declare all occupied rooms, connecting
corridors, stairs and mezzanines; large rooms need distributed lights, not one lamp
at the entrance. Choose theme-appropriate lanterns, sconces, braziers, luminous
crystals or inlaid light bands. Verify lit/powered states. Roof openings supplement
night lighting; use colour and shielding for atmosphere rather than unreadable rooms.

- Declare `rooms` and `indoorPassages` as inclusive occupied volumes (<=32 combined).
  All walkable points in those volumes must be covered by lighting.spaces; either
  declaration requires READABLE, never EXTERIOR_ONLY.
- spaces has <=32 ordered boxes inside the blueprint. Each needs authored walking
  floor/feet/headroom samples. Cover ALL intended occupied interiors, not a tiny lit
  patch while leaving most of the room undeclared.
- sources has <=128 unique authored positions with level 1..15; minimum is 8..15.
- Core estimates light at feet AND head, decreasing one per face-adjacent step
  through explicit air/passable cells. KEEP and solid cells block propagation;
  skylight is ignored. This is conservative, not Minecraft's full light engine.
- Native export verifies that actual block-state emission meets each declared level.
  A level=15 declaration on stone fails export. Source existence alone is not enough.
- Precompiled decay protects light positions; instance patches that dim them fail export.
- Genuinely open designs may use `{"mode":"EXTERIOR_ONLY"}`. This is not an exemption
  for enclosed rooms. Lighting outdoor approaches remains good design.

Numeric estimates do not prove shader appearance, spawn safety, physical accessibility
or visual quality. Report actual in-game evidence separately.

## Execution and completion boundaries

Architecture is authored for the prompt, then deployed deterministically as the
infinite world generates. Ordinary chunk callbacks never call AI or the network.

Give landmarks a sensible discovery strategy: compatible surfaces/biomes, reasonable
rarity and optional geographic anchors where appropriate. Fixed candidates can fail
terrain checks; prefer repeatable candidates where that matches the world. A planned
group must not be disabled by zero chance or an empty influence interval.

landmarkGroupCount>=1 proves a validated SOURCE PLAN exists, not that a particular
instance has appeared. Runtime placement is separate. Tools report
landmarkInstancesVerified:false rather than inventing a successful placement.
complete=true means saved, read back, Core-valid, native structure export/readback,
full Minecraft data-pack reload and preset activation succeeded in the current context.
Actual world placement and visual verification remain separate; landmarkInstancesVerified
stays false. A missing/stale creation context or native error never becomes Core-only success.

Draw SDK stays independent: it draws geometry only. The MC-side worker executes approved
Java, structure metadata composes the buildings, and worldgen places frozen data.
Full SDK/tool sequence: contract/draw and the published Structure Agent documentation.
