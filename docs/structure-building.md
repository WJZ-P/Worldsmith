# Structure authoring and world generation

Worldsmith's source format remains 1. AI authors portable JSON, not Java or NBT.
The executable contract is `core/src/main/resources/prompts/contract/structure.system.md`.
The terrain contract links to it. All features are opt-in: random biome-restricted
placement, no assembly, one variant and no interactions remain valid defaults.

## Pipeline

1. Core expands bounded primitive operations and local modules.
2. It resolves finite seeded palette/module variants and protected weathering.
3. Optional voxel circulation and port checks run without Minecraft.
4. Optional socket assembly produces bounded rigid multi-piece plans.
5. A catalog owns unique source blueprints, compiled variants and layout plans.
6. The MC adapter resolves live blocks/states, generates small native plan metadata,
   and exports one NBT per blueprint variant plus typed block entities/loot tables.
7. Native worldgen picks a plan, arbitrates reservations, probes bounded terrain,
   chooses a rigid pose, and persists individual pieces and cut/fill columns.
8. Each chunk writes only its clipped piece portions with private placement settings
   and deterministic content seeds. No AI call or recursive construction occurs here.

`CompiledPack` owns the immutable Core catalog. Bootstraps and asset export reuse it.
`WorldsmithPackExporter.write` receives both patched and full registries: registry
JSON uses the patch provider's serialization context; block entities and loot use
full registry context. Runtime and datagen use the same compiler and asset writer.

## Authoring surface

Operations: SET, FILL, SHELL, CLEAR, LINE, ELLIPSOID, CYLINDER (including taper/cone),
POLYGON extrusion, ARCH, Bezier CURVE, paired DOOR, STAIRCASE, profile ROOF,
REPEAT, INSTANCE, CHOOSE. Missing cells KEEP terrain; hollow interiors/CLEAR emit air.
Every geometric transform also applies to actual Minecraft block states.

Optional blueprint fields:
- `access`: entrances, destinations, required-clear boxes and headroom. This is a
  voxel approximation with one-block steps, not complete MC movement physics.
- `variation`: 1..8 precompiled variants, weighted palette choices, keyed module
  choices, regional exposed decay and protected areas. Datum floors, doors,
  interaction positions, ports and declared walking routes survive decay.
- `ports`: named, typed sockets with facing/pool/required. Walkable sockets have
  two-high passage and floor checks; solid sockets (`passage:false`) can use
  UP/DOWN for stacked towers, branches and other non-walkable assemblies.
- `interactions`: containers with items, loot references or inline weighted loot;
  literal front/back sign text; banner patterns. No arbitrary NBT or entity scripts.

A definition's optional `assembly` owns child blueprints and weighted pools.
Matching sockets become adjacent cells with opposite facing. Piece bounds do not
intersect. Required ports must close; terminal/cap modules are useful. Placement
is a rigid plan, not a whole-world village/road solver or per-building terracing.
Upper storeys do not generate foundation columns through rooms below them.

Source storage remains a `structures.json` index with separate
`structures/<blueprint-id>.json` files, including assembly children. All referenced
sources, variants, graph rules and inline loot affect the content hash. Previews
and compiled artifacts do not. Native assets use `structure/.../<id>/<variant>.nbt`;
inline loot is emitted under `loot_table/...` and referenced from container NBT.

## Placement

Without `anchor`, the native random-spread structure set remains biome-restricted.
Optional fixed/scattered/line anchors reference terrain geometry. Scattered sites
share the exact terrain jitter computation, including deterministic same-chunk
canonicalisation. Both modes honour biome and terrain gates; anchors are not
forced starts. Same-pack anchored candidates outrank random ones, then seeded
rank breaks ties. Reservations include all plans, rotations and search offsets.
Vanilla/other-pack/third-party structures remain outside this guarantee.

Surface modes: LAND_SURFACE, OCEAN_FLOOR, WATER_SURFACE, SKY_SURFACE, CAVE_FLOOR,
CAVE_CEILING. Sky/cave modes require an explicit height window and can select a
lower layer instead of the top heightmap. Full footprint vertical clearance is
checked. Sky detection is a local column with air below, not global island topology.
Water platforms may float or have bounded pillars; ceilings use no ground foundation.

Optional searchRadius tries a nearest-first coarse grid and rotations. Optional
LAND_SURFACE + FILL earthwork chooses a median datum with bounded cuts/fills;
otherwise fitting retains the existing highest-support behaviour. The root applies
persisted earthwork before child templates in each chunk. Nothing is flattened by
an anchor alone. Foundation materials are separate placement policy, not weathered
blueprint writes. Worldsmith vegetation avoids authored/reserved/earthwork volumes;
third-party vegetation and full environmental simulation are outside this protection.

Locate keeps the vanilla search and adds bounded anchor candidates. Default fixed
anchors retain their exact pivot; enabling nearby search allows a small displacement
inside the reserved envelope. A failed bounded locate is not a global absence proof.

## Bounds

| Area | Limit |
|---|---|
| One blueprint | 64 per axis, 131072 authored cells, 524288 voxel visits |
| Construction | 2048 declared/expanded operations, depth 8, repeats 64 |
| Variants | 8 per blueprint; assembly plan variants 8 |
| Assembly | 16 placed pieces, 16 child definitions, radius 96, vertical span 128 |
| One plan | 262144 placed cells, 8192 terrain columns |
| Entire pack | 64 unique blueprints, 1 million variant cells, 4 million visits |
| Terrain fitting | <=16 nearby pivots; <=12288 unique noise columns per start |
| Foundations | depth <=16, normal fill budget 4096 |
| Earthwork | cut <=8 per column; combined work <=8192 |
| Interactions | 128 per blueprint; literal signs; bounded inventories/loot/patterns |
| Preview | 16000 isometric faces; orthographic fallback retained |

## Examples and validation

`worldsmith_get_structure_example` exposes four executable examples:
- forest_shrine: base grammar;
- wayfarer_lodge: connected loft stairs, curved eaves, variants, sign and loot;
- arcane_observatory: dome/arch/curve, patterned banner;
- connected_courtyard: five-piece compound with multiple house choices.

`worldsmith_preview_structure` accepts variant, sliceY and cutaway.
`worldsmith_preview_assembly` accepts a complete definition and a plan variant.
Both remain Core-only. Export later exercises the real MC codecs and block entities.

Developer preview:
```powershell
./gradlew.bat :core:previewStructure '-PblueprintFile=core/src/main/resources/worldsmith/structures/wayfarer_lodge.json' '-PpreviewFile=build/structure-previews/lodge.svg'
./gradlew.bat :core:previewStructure '-PblueprintFile=core/src/main/resources/worldsmith/structures/wayfarer_lodge.json' -PsliceY=7 -Pcutaway=true '-PpreviewFile=build/structure-previews/lodge-cutaway.svg'
./gradlew.bat :core:previewStructure '-PblueprintFile=core/src/main/resources/worldsmith/structures/connected_courtyard.json' -Passembly=true -Pvariant=1 '-PpreviewFile=build/structure-previews/courtyard.svg'
```

Tests cover geometry, access, variants, assembly graphs, source/hash/MCP roundtrips,
native state/block-entity/loot codecs, selected air intervals, cut/fill limits,
actual multi-piece placement, save/load and reverse chunk order. Plain JVM MC tests
also run vanilla 26.2 item component initializers; fake empty components would hide
real stack limits. Final appearance, movement physics and fluid evolution remain
in-game acceptance checks. Fresh test worlds are appropriate for this unpublished
runtime schema change; no migration layer is retained.
