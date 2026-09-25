# Plateau observatory: a theme-to-space study

`PlateauObservatory.java` is an executable `StructureProgram`, not a stock style,
mandatory composition rule, or complete world pack. It uses the existing drawing
and authoring SDK only. The default `study=refined` is the authored result;
`study=massing` retains its earlier blank north facade for a reproducible repair
comparison. Both versions have the same rooms, access and main masses. The
refinement adds one low entry threshold and its own beam-supported lamp.

## What the theme changes

The brief is **a quiet observing outpost on a broad, wind-exposed high plateau**.
It changes the building's organization rather than merely its palette:

| Brief concern | Physical response | Evidence to inspect |
|---|---|---|
| Clear horizon and an identifiable observing datum | A northwest tower with occupied floors at Y=3 and Y=11, then a roof deck at Y=19 | All three destinations have authored floors and a connected two-flight stair |
| Low visual disturbance around the instrument | A low northeast service wing, roof at Y=8–9, versus the tower shelter at Y=25 | Fixed-frame clay elevations show a 16-block main/secondary height difference |
| Quiet rather than continuously decorated space | An almost empty southeast court, authored as air above a walkable platform, with explicit empty-volume `keepClear` declarations | `open_court` remains negative space; clearance excludes the floor, arrival fixture and threshold furniture |
| Sheltered, understandable arrival | A south approach offset east of the tower, then a turn west toward its actual door; a short branch serves the low wing | Walk the authored route points and inspect the southwest view |
| A human-scale transition, not just a path painted across a slab | A low service-door porch, one instrument-preparation table and a small bench along the building edge | The porch stays below the low wing, leaves three blocks of route headroom and keeps the central court empty |
| Shallow contact with a plateau | A three-course stepped retaining base with clipped corners and a supported arrival spur | Inspect the foundation silhouette; this is authored geometry, not proof of native terrain fit |
| Reduced glare, substantial walls | Two-block-thick reveals and recessed gray glass, a high service clerestory | Compare front/north clay and material views, not material count alone |
| Restrained material language | Stone masonry, dark roof tile and dark timber families; glass, fixtures and one storage barrel are functional exceptions | More than 95% of non-air cells belong to the three construction families |

The 49 × 39 × 29 drawing includes clearance and the offset access spur. That box
is a drawing budget, not a recommendation to flatten 49 × 39 blocks of terrain.
North is −Z. The entry faces south, so `front` measures the north elevation while
`back` shows the arrival side.

## Run and review without a game

From the repository root, with the configured JDK:

```powershell
.\gradlew.bat :core:test --tests "com.wjz.worldsmith.core.structure.PlateauObservatoryExampleTest"
.\gradlew.bat :core:test --tests "com.wjz.worldsmith.core.mcp.PlateauObservatoryWorkbenchTest"
```

The test compiles the adjacent Java source, executes its `StructureProgram`,
reads its real authored sidecar, and checks strict frozen-geometry semantics:
four occupied spaces, real floors, lamps with intact roof/beam anchors, clear
arrival and connected access to the tower's two rooms, roof deck and service room.
It also writes deterministic comparison artifacts under:

```text
build/structure-quality-verification/plateau-observatory/
  massing-{clay,material}-{isometric,isometric_back,front,back}.png
  refined-{clay,material}-{isometric,isometric_back,front,back}.png
  refined-floor-{5,13,21}.png
  massing-evidence.json
  refined-evidence.json
  review.json
  worker-refined-clay-isometric_back.png
  worker.json
```

The second test also exercises the real MCP source-project upload, isolated
worker and bundled compiler, authored-sidecar preflight, and preview handlers;
it does not substitute the in-process Java compiler for that workflow.

All revisions use the same frame: `(-24,0,-18)` through `(24,28,20)`. The first
review targets the largest unbroken north-wall plane, not a numeric beauty score.
The second revision recesses two levels of slit windows and adds two restrained
timber corner piers. The test requires an actual decrease in the largest
unbroken north-facing plane and an increase in surface depth transitions.

The subsequent isometric image review identifies a different problem: the bare
T-shaped path has no human-scale stopping place. The refined version therefore
adds a short, low porch at the service door, a preparation table and a small
bench. None blocks the arrival route, competes with the tower, or spills into the
remaining 22 × 12 court. This is a local repair, not a request for more ornament.
Depth along an elevation's viewing axis is subtle or invisible in a uniform clay
front view: use the isometric clay view, material view and geometric evidence
together rather than trusting the unchanged silhouette alone.

The wide retaining faces are intentional quiet surfaces. Do not add arbitrary
texture noise to reduce their apparent simplicity. Check the repaired result's
remaining largest plane in `refined-evidence.json`, then decide whether it has a
purpose before changing it.

## Use in the authoring workbench

1. Upload the complete adjacent Java file with `worldsmith_put_drawing_source`;
   target `observatory` has `entryClass: "PlateauObservatory"` and
   `files: ["PlateauObservatory.java"]`.
2. Run `worldsmith_build_drawing` against that exact project revision and target,
   with `seeds: [381]` and `parameters: {"study":"massing"}`. Wait for success,
   retain the returned frozen drawing id, and run `worldsmith_preflight_structure`.
3. Request the four named views in clay with the fixed frame above. Inspect
   `visualEvidence` and identify the north wall rather than asking for generic
   "more detail".
4. Repeat with `parameters: {"study":"refined"}` and a new request id. Compare
   the same views/mode/frame, then inspect material views and floor cutaways.
5. Reference only the selected successful frozen drawing in an authored
   blueprint. This example supplies no world-specific placement or biome ids.

## Boundaries and remaining site decisions

The current validation is Java authoring, frozen voxel geometry, declared
lighting and approximate two-block navigation. The offline renderer models
basic vanilla slabs/stairs but is not a game screenshot or collision engine.
Native placement, terrain support, actual night lighting and in-game appearance
have not been exercised.

`AuthoringContext.protect` records protection from variation/decay. The separate
`keepClear(Box)` calls record real vegetation/access-clearance intent in the
authored sidecar; they do not carve air or remove existing geometry. Here the two
air-only court subregions exclude the floor, arrival fixture and porch, and
strict preflight checks those declarations. Native vegetation exclusion and
world placement remain untested; inspect the chosen world's placement policy.

Before deploying, measure a representative plateau shoulder instead of choosing
terrain from its name. The low wing should follow the contour; the court should
face the calmer side; the tower should retain a usable horizon. Inspect the
retaining depth and arrival slope before choosing foundation/earthwork settings.
The external stair is a deliberate fair-weather choice: a theme with severe ice
or frequent storms needs a sheltered stair or a different circulation plan, not
simply snow-colored blocks. Do not apply this composition to unrelated themes.
