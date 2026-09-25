# Terrain relief: offline verification

Verified on 2026-09-25 UTC (2026-09-24 local). These are JVM noise-field,
serialization and reference-math checks. No Minecraft client, server, game loop,
GameTest or datagen task was started. No local world data was modified.

## Why the terrain control changed

The former compiler selected flat, highland and peak height recipes with hard
interval boundaries. A 50% flat / 50% highland world therefore inherited sudden
height changes even when its theme called for rolling country. It also retained
zero-weight recipes beyond selector thresholds clamped to -1 and 1; real noise
tails could select those supposedly disabled recipes.

The reproducible audit uses seed `0x574F524C44534D49`, land ratio 1, continent
scale 1, coast roughness 0, vertical scale 1, and relief weights 0.5 / 0.5 / 0.
Caves, rivers, lakes, anchors and terrain bands are absent. It samples X -2048
through 2048 at Z -768, -384, 0, 384 and 768: 20,485 columns.

Each column's surface is found by bracketing the zero of the actual authored
density field and interpolating only the final adjacent vertical samples. This
includes the compiler's global floor/ceiling fades. It does **not** perform
Minecraft's horizontal noise-cell interpolation or create chunks.

| Implementation | Maximum adjacent-X field-height change | Disabled peak samples |
|---|---:|---:|
| Before the fix | 83.830480 blocks | 30 |
| Fixed, explicit sharp width 0 | 41.319305 blocks | 0 |
| Fixed, smooth width 0.12 | 6.132706 blocks | 0 |

The smooth setting reduced the largest sampled adjacent-column change by 85.2%
against the corrected sharp setting. Sharp and smooth runs had identical
erosion/biome-family signals at all 20,485 samples. The 146 flat/highland
boundaries in the sharp run averaged 30.451269 blocks per adjacent-X step.

The original largest discontinuity was at Z 384, X -1262 to -1261: surface
212.650787 to 128.820307. Its unintended high section had peak-family erosion
around -0.702 despite peak weight zero. This explains why a fixed-Y depth
inversion was not a valid measurement method; the corrected audit brackets the
zero surface instead.

## Contract and deliberate limits

- `relief.transitionWidth` defaults to 0.12 and accepts 0..0.5. It is a
  **half-width in ridge-noise units**, not blocks, an accessible walking grade,
  or a universal smoothing radius. Zero deliberately retains sharp escarpments.
- Only positive-weight families participate. One selected family returns its
  original field. Smooth transitions are convex combinations of selected
  recipes; a flat-to-peak blend does not invoke the highland recipe when its
  weight is zero.
- Adjacent blend windows are limited to 45% of their neighboring threshold gap,
  so they do not overlap and at least 10% of a rare middle interval stays
  unblended. Windows smaller than one millionth of a noise unit use a hard
  threshold, keeping density codec coefficients bounded.
- Ordinary biome erosion remains the dominant-family signal, not a blend that
  invents a disabled biome category. An explicit anchor erosion override may
  select another family locally. In smooth mode its height response uses the
  same warped influence and strength, reaching the authored category at full
  strength without an extra categorical height cliff. Other climate axes do
  not silently alter relief height.
- Mesa/caldera profiles still act later, after ordinary relief and hydrology.
  Their explicit absolute levels, bounded texture and ordered overlap semantics
  are unchanged. Later caves/bands can still reshape them.
- The builtin Ashlands fixture explicitly selects width 0 for its broken
  escarpment style. Its native exported noise fixture remains unchanged; this is
  a supported style choice, not an old-schema conversion path.

Even the smooth audit's maximum is 6.13 blocks per horizontal sample. This is
evidence that the artificial category wall was reduced, **not** proof of
walkability, building placement or final visual quality. Terrain scale, texture,
water, slope limits, structure footprints and later operations still matter.

## Landmark and export stability

The anchor API has explicit `offset`, `mesa` and `caldera` cross-sections. Core
tests cover true level mesa interiors, caldera floors/rims, continuous joins,
bounded texture, ordered composition and 8,000 valid extreme-profile samples.
Native density tests compare 7,128 cases with the offline reference.

Cubic smoothstep nodes publish their true 0..1 bound. Without the explicit
output clamp, independent interval arithmetic treats their factors as a possible
0..3 blend and inflates nested density bounds. Eight alternating mesa/caldera
graphs now encode in 20,163 compact JSON bytes, with relative-height bounds
-452..1546. Incremental encoding grows linearly: 1,974 bytes per mesa and 3,042
per caldera in this fixture. These are **graph bounds**, not actual terrain
surface limits or the size of a whole exported world pack.

## Reproducible evidence

`WorldsmithReliefTransitionTest` writes the corrected sharp and smooth audit to
`build/terrain-relief-audit/`. The original `hard-boundaries.*` files are a
preserved pre-fix capture, not regenerated by the corrected test.

- `hard-boundaries.csv`, `.metrics.json`, `.png`: original behavior including
  the zero-weight peak leak.
- `sharp-boundaries.csv`, `.metrics.json`, `.png`: corrected explicit sharp mode.
- `transition-comparison.csv`, `.metrics.json`: point-matched sharp/smooth values.
- [`transition-comparison.png`](../build/terrain-relief-audit/transition-comparison.png):
  the recommended comparison, with both curves overlaid on the same X window
  and shared Y range; orange is sharp width 0 and teal is smooth width 0.12.
- `soft-boundaries.png`: smooth section at the sharp run's strongest boundary.

The individual sharp/soft PNGs retain their labeled, independently auto-ranged
vertical axes. Use the combined `transition-comparison.png` for direct visual
comparison without an axis-scale change. All images are scientific field
diagrams, not screenshots or shaded terrain previews. The original pre-fix
`hard-boundaries.png` is retained separately because it includes the ghost-peak
defect, rather than the intentionally sharp style alone.

Read-back JUnit snapshot from 2026-09-25 04:37–04:38 UTC (65 related tests in
this recorded pass, not a claim about the current whole-suite test count):

| Test class | Passed |
|---|---:|
| `TerrainShapeTest` | 8 |
| `TerrainPlanValidatorTest` | 7 |
| `AnchorReliefSamplerTest` | 7 |
| `ExampleTerrainFitTest` | 1 |
| `WorldsmithReliefFieldsTest` | 4 |
| `WorldsmithReliefTransitionTest` | 2 |
| `WorldsmithAnchorReliefTest` | 3 |
| `WorldsmithTerrainSamplingTest` | 27 |
| `WorldsmithPackExporterTest` | 6 |

All listed reports recorded zero failures and zero errors. The native exporter
regression includes codec read-back and comparison with the existing generated
fixture, not a game launch.

## Follow-up: several seeds and the slope-distribution tradeoff

A separate 2026-09-25 05:12 UTC pass sampled five fixed seeds across three
terrain configurations: rolling flat/highland country without peaks, compact
flat/peak terrain without highlands, and broad single-family highlands. Each
configuration was evaluated with widths 0 and 0.12 on two 1,025-column
transects, for **61,500 sampled columns including both modes**.

Every sampled density, surface and erosion value was finite; all sampled surfaces
stayed within the configured vertical envelope. All disabled-family counters were zero. Each
of the 15 matched sharp/smooth pairs retained identical erosion signals, and
the five single-family pairs had identical surfaces. These are finite-sample
results and robust invariants, not a promise that every seed improves by the
same percentage.

Smoothing redistributes a sharp step into a wider sloped band; it does not lower
every slope statistic. For seed 0, `rolling_no_peaks` demonstrates the tradeoff:

| Adjacent-column change | Sharp width 0 | Smooth width 0.12 |
|---|---:|---:|
| Median | 0.188677 | 0.218416 |
| 90th percentile | 0.655576 | 0.978183 |
| 99th percentile | 1.436409 | 2.124466 |
| Maximum | 33.368836 | 3.737379 |

The extreme cliff became much smaller, while more columns participated in its
replacement slope. Consequently the median and upper percentiles rose in this
example. The test does not impose an 85% improvement target on every seed.
Quantiles use nearest rank over adjacent X pairs within each transect, never
across the gap between transects.

Artifacts:

- [Five-seed metrics](../build/terrain-relief-audit/multiseed-relief.json)
- [Five-seed comparison CSV](../build/terrain-relief-audit/multiseed-relief.csv)

## Follow-up: three local two-dimensional theme examples

[View the common-color-scale heightfield comparison](../build/terrain-relief-audit/theme-heightfields.png).

Three 129 × 129 grids add **49,923 columns** at one shared fixed seed. Mesa and
caldera panels cover X/Z ±768 blocks at 12-block spacing; the archipelago panel
covers ±1,024 at 16-block spacing. All panels use the same absolute-Y color scale
from -40 to 240, with values outside that range clamped to its end colors.

| Local example | Sampled surface Y range |
|---|---:|
| Gentle observation mesa | 62.7584 .. 113.7107 |
| Broken storm archipelago | 12.6420 .. 225.1075 |
| Single caldera basin | 64.1930 .. 165.2878 |

Blue means only that the sampled solid-surface zero lies below the Y63 sea-level
reference. It is not measured fluid occupancy. Colors encode altitude, not
biome materials. “Storm” is the authored landscape label, not simulated weather.
The panels show spatial differences between three local parameter choices;
they do not establish every theme's aesthetic quality, traversability, spawning
or actual building placement. No caves, bands, structures or entities are shown.

- [Heightfield parameters and measurements](../build/terrain-relief-audit/theme-heightfields.json)
- [Heightfield sample CSV](../build/terrain-relief-audit/theme-heightfields.csv)

The separate `WorldsmithReliefMultiSeedTest` and
`WorldsmithThemeHeightfieldTest` reports each recorded one passing test with
zero failures and zero errors in this follow-up pass. These results are
additional to, rather than a rewrite of, the earlier 65-test snapshot.
