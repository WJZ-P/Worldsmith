---
name: Eastern Classical
description: Chinese classical landscape - steep peaks in mist over rivers and lakes, terraced fields, water towns, courtyard houses, pavilions and temples under heavy upturned eaves. Use for 古风, 山水, 江南, xianxia or ink-painting prompts.
---

# Eastern Classical

This is the world of a shan-shui painting: mountains rising out of mist, water
in almost every view, and buildings that sit low and wide in the land rather
than standing on top of it. It is not red and gold everywhere, and it is not
Japan - no torii gates, no cherry monoculture - unless the prompt asks.

## 1. Terrain: mountain and water, always together

The signature is steep, separated peaks standing out of low wet ground, not a
continuous range. Start between the general method's *ordinary* and *alpine*
rows: `landRatio` 0.55-0.65, `verticalScale` 1.6-2.2, relief with peaks around
0.35 but flats still the largest share, so the peaks read as individuals. Water
is half the picture: `FLUID` rivers with strong meander around 0.07 coverage and
lakes around 0.1, so most summits look down on water.

A named sacred mountain, a great lake or a river gorge the prompt calls *the*
is an `anchor` with a cool, humid `climateBias`. Terraced hillsides are built by
structures and features on the slope, not by relief alone.

## 2. Palette: ink, jade and paper

Mist carries the style. Give valley, lake and mountain biomes a pale grey-blue
`fog.color` near `#C8D2D4` and pull `fog.endDistance` in to 96-140 so far peaks
dissolve into layers; keep open plains near the default. Water is jade, not
blue: `tint.water` around `#3E8C7E` for lakes, clearer `#4F9FA8` for mountain
streams. Grass and foliage are muted, slightly blue-green (`#86A873`,
`#6E9A5E`); saturated lime reads as a cartoon. A pale sky around `#A9C3CD` with
a warm apricot `sky.sunriseSunsetColor` finishes it.

Surfaces: grey stone, `andesite`, `tuff` and streaks of `calcite` on peaks;
`moss_block` and `coarse_dirt` under pines; `mud` and `packed_mud` in paddies;
`gravel` and sand only at river bends.

## 3. Vegetation with a shape

- **Mountain pine** on ridges and cliff tops: `CROOKED` trunk with `bend`
  0.3-0.5, `UMBRELLA` or `LAYERED` crown of radius 3-4, sparse. One leaning pine
  on a crag says more than a forest.
- **Willow** at every water edge: `BENT` trunk, `WEEPING` crown with
  `hangingLeaves` 0.5-0.7.
- **Bamboo** in dense groves on lower slopes and around houses.
- **Plum and cherry blossom** as accents near temples, gardens and villages -
  `cherry_leaves`, `pink_petals` on the ground - never whole biomes of pink.
- **Lotus and reed**: `lily_pad` on still water, `sugar_cane` at banks.

## 4. Architecture

The roof is the building. Make it wide and heavy, overhanging the walls by 1-2
blocks, with the corners lifted by upside-down stairs at the eaves and a slab
ridge along the top; walls stay low under it. A flat or pyramid roof loses the
style at once.

Materials by role:

| role | materials |
| --- | --- |
| roof | `deepslate_tile_stairs` / `deepslate_tile_slab` - grey tile |
| frame | `dark_oak_log`, `spruce_log` and their stripped forms as exposed columns and beams |
| walls | `calcite` or `white_concrete` plaster (`white_terracotta` reads warm beige, not white); `mud_bricks` for rural houses |
| plinth and steps | `stone_bricks`, `polished_andesite`, `tuff_bricks` |
| windows | `spruce_trapdoor` or `birch_trapdoor` lattice, `white_stained_glass_pane` paper |
| rank accent | `red_terracotta` columns, only on halls, temples and gates |
| light | `lantern` hung on `iron_chain` (26.2 renamed `chain`) |

Red and gold are rank, not decoration: a village is grey tile, white plaster
and dark wood, and red columns mark the hall a player should walk to.

Types, and where they sit:

- **Courtyard compounds** - buildings facing inward round a yard, the gate on
  the south axis, the main hall at the back, symmetric. Houses, manors, palaces.
- **Pavilions** where there is a view: a lake edge, a bend in a mountain path.
- **Water towns** - white walls and grey roofs along canals, with landings,
  arched stone bridges and covered bridges.
- **Temples** on ridges, reached by long stairs; a bell tower beside them.
- **Paifang gates** on the approach to something important.
- **Pagodas** are landmarks: at most one per region, on a hill or an island.

Classical sites follow water and slope. A village on a grid on flat ground is
the easiest way to lose the style.

## 5. Sound

Calm, with a sense of distance. Blossoming valleys and bamboo take
`music.overworld.cherry_grove` or `bamboo_jungle`; open terraces `meadow`;
pine mountains `old_growth_taiga` or `grove`; marsh and reed lakes `swamp`. A
temple mountain may carry a distant `block.bell.resonate` addition at about
0.0005, a water town `block.water.ambient` at about 0.002. No loops: nothing
here should feel wrong.

## 6. Life

Give creatures the relationships of the landscape: cranes that `FLY`, keep to
lakes and `hunt` `minecraft:cod`; deer that `herd`, `eat` short grass by `DAY` and
`fear` wolves; a fox spirit that walks only at `NIGHT` and is `temptedBy` an
incense or offering item. One predator and one grazer already make the valley
feel inhabited.

## 7. Things to make

Tea leaves smoked in a `SMOKER` into tea; raw jade from a creature or ore melted
in a `BLAST_FURNACE` into cut jade; cut jade and gold forged into a talisman.
A short gather-process-make chain turns found things into something to carry.

## 8. Common failures

- Everything red and gold, so nothing is important.
- A pagoda or temple as every building.
- Roofs with no overhang, no lifted corners, or a pyramid.
- Japanese vocabulary the prompt did not ask for.
- Glowing `end_rod` "spirit lights" in the air; a few `cherry_leaves` particles
  near blossom are enough.
- Bright saturated greens and blue water.
