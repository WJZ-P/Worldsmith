# Worldsmith world-generation agent: architecture policy 1

This is an ACTIVE DESIGN TASK. After terrain and biome planning, independently
develop architecture for THIS player's world. Infer plausible functions, inhabitants,
infrastructure, ritual places and ruins from the theme. Extend it thoughtfully;
do not import the same medieval village or a shared asset catalog into every world.

## Creative brief

The goal is a memorable, coherent place to explore, not a collection of passing
blueprints. Before coding, turn the player's theme into visible decisions: a
recognisable silhouette, a spatial sequence, a structural/material language and
signs of the place's purpose. Adjectives and palette swaps alone do not implement a theme.

Use the existing plan fields: `worldTheme` states the shared design language;
`themeFit` names concrete forms and uses; `layoutIntent` describes approach, focal
mass, open spaces and circulation; `distinction` explains differences in plan,
section and silhouette from the other groups. No extra JSON fields are required.

Develop one representative main building before multiplying variants. Review its
clay massing, elevations, material views and occupied interior; repair the largest
visible weakness first. Shared components should preserve a design language, not
turn every function into the same resized hall. A validator proves constraints,
not beauty. Minimum size/count/light checks are floors, never design targets.

## Form, function and family

- **Choose a composition, not a decorated box.** Briefly consider two plausible
  massing arrangements, choose the one that serves the prompt and site, then build
  only that option. State what is dominant, what supports it, and where space is
  deliberately left empty. A courtyard, opening or quiet wall is designed space,
  not unfinished geometry. Monumentality comes from hierarchy and approach as
  well as size; do not enlarge foundations to manufacture it.
- **Work at three reading distances.** At a distance: roofline, skyline, height
  relationships and the main void. On approach: entrance, bays, columns, stairs,
  projections and recessed openings. Close up: joints, trim, fixtures, furniture
  and evidence of use. Fine decoration cannot rescue a weak silhouette. Quiet,
  minimal or alien themes may need fewer elements, not more ornament.
- **Give surfaces thickness and rhythm.** Choose bay spacing and proportions from
  this building's structure. Relate roof, wall and base; recess windows and doors,
  project framing where appropriate, and resolve corners, side elevations and
  rear service faces. Slabs/stairs/walls and their real states can express finer
  profiles than cubes. Avoid identical flat walls disguised by random materials.
  Do not require gables, towers, arches or symmetry when the theme calls for none.
- **Use a material hierarchy.** Give dominant surfaces, structure, trim and accents
  distinct jobs; repeat a small number of intentional motifs. Texture variation
  follows exposure, construction, repairs or use rather than independent per-block
  noise. Ruins and weathering are theme choices, not mandatory detail passes.
- **Make related buildings meaningfully different.** Share joints, palette roles,
  window/fixture vocabulary and craft logic. Change layout, section, roof/mass
  arrangement, entrance sequence and interior use when the function changes.
  A workshop, dwelling and ceremonial place should not be one Hall program with
  different names, widths and colours. Repetition within a facade is rhythm;
  duplication of the whole composition across every role is not variety.
- **Compose the group as a place.** Connect useful entrances with readable paths;
  design courts, edges, pauses and sightlines around the centerpiece. Support
  buildings should frame or serve it rather than all competing for dominance.
  Account for slope, water, foundations and terrain-facing edges in placement
  metadata. A top-down arrangement on flat ground does not prove terrain fit.
- **Design inhabited volume.** Give each occupied floor a purpose, reachable
  circulation, useful headroom and furniture/activity zones that leave passages
  clear. Let visitors understand what happened here through spatial evidence,
  not only signs or loot. Design night fixtures into the architecture; do not
  replace the floor plan with a uniform carpet of light blocks.

## Visual quality loop

Use real PNG image content, not a successful job status, a filename, a cell count
or the source code as evidence of appearance. The existing preview tools support
the same `views`, `renderMode`, `region`, `frame`, `cutaway` and `sliceY` vocabulary.

| Pass | Useful request | Look for and revise |
| --- | --- | --- |
| Main mass, before ornament | `views:["isometric","isometric_back","front","top"], renderMode:"clay"` | Focal hierarchy, proportion, skyline, useful voids; would the theme still read without colour? |
| Exterior | `views:["front","back","left","right"], renderMode:"material"` | Entrance legibility, bay rhythm, depth, roof/wall/base relationships and unfinished side/rear faces |
| Interior, each occupied storey | `views:["top","isometric"], cutaway:true, sliceY:<just above that floor's openings>` | Room use, circulation, support, stairs, headroom and fixtures; crop a storey or hide a named roof component where useful |
| Whole group | `worldsmith_preview_assembly` with `views:["top","isometric","isometric_back"], renderMode:"clay"` | Centerpiece versus support, approach, gaps, path connections and repetition; inspect material view separately |

Drawing/assembly sliceY is original Y; blueprint preview sliceY is normalized local
Y. `view:"slice"` is only a single layer. `cutaway:true` retains everything at or
below the selected height in the chosen views. Component hiding is a spatial box
filter, so do not assume it understands roofs or floors without named regions.

After each meaningful preview, record a short observation -> specific geometry
change -> expected visible improvement. Fix the most important one or two issues,
then compare using the returned `frame` and the SAME view/mode. Use a larger frame
deliberately if the composition grew; use a region/detail frame for a local repair.
Do not rebuild every member for a local flaw. Do not claim improvement just because
a rebuild succeeded; compare the images. If further changes have no clear benefit,
stop polishing rather than adding arbitrary noise.

Before expanding a building family, make the representative composition convincing
and preflight its access/lighting. Then inspect distinct members and materially
different variants; naming or recolouring duplicates is not a finished catalog.
Before publication, review the assembled place as well as individual buildings.
If the image is unavailable, state that visual review is pending instead of
inventing observations. Simplified cubes and approximate colours are enough for
form studies, not proof of textures, transparency, native stair shapes, physical
lighting or an eye-level game experience. Report those limits honestly.

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
5. Read contract/draw, prefer StructureProgram with geometry-linked semantics (or
   compatible DrawProgram), build with `worldsmith_build_drawing`,
   wait using `worldsmith_get_drawing_job`, inspect actual image content from
   `worldsmith_preview_drawing`, revise, then reference drawing ids in contract/structure
   metadata. Follow the visual quality loop above, not just a single flattering
   isometric image. Submit complete group/independent definitions with `worldsmith_put_structure`.
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
Interrupted jobs need explicit retry; in-game confirmation follows the configured host policy. Legacy packs remain loadable;
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


## Efficient creative passes

Do not start twenty unrelated full-detail buildings at once. Develop a representative
component set for THIS world, then one main mass, roof/facade rhythm, access, indoor
lighting, decoration and finally related secondary buildings/variants. Register common
source files as a source project so target dependencies are explicit and compiled once.
Use StructureProgram to bind geometry and semantic declarations to the same variables.
Run preflight before scaling out a new component or composition. Inspect dark/error
regions and repair only affected source targets, metadata or layout. Use atomic
worldsmith_put_architecture_draft when related plan/member changes belong together.
Engineering gates prove deployability and readability estimates, not aesthetic quality.

## World bundle version boundary

Published worlds use bundle format 3 with seven typed modules and verified assets.
Architecture policyVersion remains 1. Blueprint schema remains 1; structure
libraries use module schema 1 or module schema 2 when freezing SDK artifacts.
These domain versions do not select the world bundle format. Legacy world bundle
formats 1/2 are rejected; do not advertise their previous load path.
