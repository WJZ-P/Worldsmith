---
name: General World Method
description: Fallback for any prompt no listed style matches - how to turn an arbitrary description into terrain, biome and feature values.
---

# General World Method

Use this when no listed style matches the player's prompt. It is a method, not
a recipe: it says how to get from a sentence to numbers, and the numbers are
still yours to choose.

## 1. Split the prompt into physical and aesthetic claims

Every description mixes the two. *A ruined post-apocalyptic world, grey and
lifeless* is almost entirely aesthetic - it says what the world looks like, not
what shape it is. *A shattered archipelago under an endless storm* carries one
strong physical claim and one aesthetic one.

Worlds fail most often by implementing only the aesthetic half. A "shattered"
world with a high land ratio and smooth coasts is an ordinary world painted
grey. Find the physical claims first; if the prompt has none, choose a shape
that gives the aesthetic somewhere to sit and say so.

## 2. Choose the terrain skeleton

Calibration anchors, not rules. Interpolate between them.

| the prompt reads as | landRatio | continentScale | coastRoughness | relief | verticalScale |
| --- | --- | --- | --- | --- | --- |
| endless plain, desert, wasteland | 0.75-0.9 | 2-4 | 0.1-0.3 | flats dominant | 0.7-1.0 |
| ordinary, earthlike, temperate | 0.5-0.6 | 1.0 | 0.4-0.5 | 0.6 / 0.3 / 0.1 | 1.0 |
| archipelago, island sea, scattered | 0.35-0.45 | 0.4-0.8 | 0.7-0.9 | flats with some highlands | 1.0-1.3 |
| drowned, flooded, ocean world | 0.1-0.25 | 0.6-1.2 | 0.5-0.7 | flats dominant | 0.8-1.0 |
| alpine, jagged, vertiginous | 0.6-0.7 | 0.8-1.2 | 0.5 | peaks 0.4-0.6 | 1.6-2.5 |
| canyon, mesa, eroded | 0.75-0.85 | 1.5-2.5 | 0.3 | flats dominant | 1.0-1.4 |

Hydrology follows the same reading: a living world wants `FLUID` rivers around
`0.06` coverage and lakes around `0.08`; a dead or frozen one wants sparse `DRY`
rivers and no lakes; a storm world wants wide, deep, strongly meandering ones.

## 3. Ask two yes-or-no questions

- **Does the prompt describe something a single ground surface cannot be?**
  Floating islands, a sky ceiling, a hollow interior, real overhangs. Only then
  add a `band`. A tall mountain is not one of these; a mountain is just relief.
- **Does the prompt use the word *the* about a place?** "The holy mountain",
  "the crater", "the tower at the centre". Only then add an `anchor`, and give
  it a `climateBias` so it reads as a place rather than as raised ground.

Most prompts need neither. Added for interest rather than because the prompt
asked, a band or an anchor makes the world noisier, not richer.

## 4. Derive the biome list from the terrain you just wrote

Not from the adjectives. Walk the regions your own terrain actually produces -
deep water, shallow water, shore, low inland, high inland, peaks, plus whatever
the bands and anchors added - and for each one ask what it looks like in this
world. That list is your biomes, typically six to twelve of them. A biome
invented per adjective produces overlapping climate boxes and one of them ends
up invisible.

## 5. Spend the aesthetic claims on the palette

Surface materials, `tint`, `fog`, `sky`, `light`, and the features scattered on
top are where the theme actually lands. Vary them: two biomes that differ in
height or temperature must not resolve to the same colours. Override only what
should differ from Minecraft's own values.

## 6. Read the prompt once more, backwards

For each claim in it, name the field that implements it. A claim with no field
behind it never reached the world - and that is the failure a player notices
first, because it is the thing they asked for.
