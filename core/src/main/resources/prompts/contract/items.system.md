# World-bound ordinary items and reward references

The `items` module is a typed library of ordinary resources and relics. It does not
register a new Java class for every item and does not introduce tools, weapons,
armor, food, equipment slots, scripted use actions or executable quests.

## Item library

```json
{
  "schemaVersion": 1,
  "items": [{
    "id": "amethyst_heart",
    "displayName": "Amethyst Heart",
    "textureAsset": "<actual uploaded PNG SHA-256>",
    "kind": "RELIC",
    "maxStackSize": 1,
    "rarity": "EPIC",
    "description": "A fragment of the gatekeeper's ancient core.",
    "themeRole": "The reward connecting the ruined gate to the observatory."
  }]
}
```

The angle-bracket value above is explanatory, not a valid texture handle. Upload
an actual PNG with the existing texture tools before submitting the library.

- `schemaVersion` is 1; up to 256 distinct item definitions.
- Local IDs use 1..64 lowercase letters/digits/underscore/dot/hyphen; reserved path
  sequences are rejected. Display names contain 1..128 printable characters.
- `textureAsset` directly names the actual PNG content hash. Icons must be square,
  power-of-two 16..256 pixels, at most 1 MiB. Transparent icons are supported.
- `kind`: `RESOURCE` or `RELIC`, descriptive only. `rarity`: `COMMON`, `UNCOMMON`,
  `RARE`, `EPIC`. `maxStackSize`: 1..64. A relic does not become quest-bound or gain
  an action merely because its kind is RELIC.
- `description` is optional plain text up to 2048 characters, with newlines only;
  `themeRole` is optional creative intent, not executable behavior.

## One item reference vocabulary

- `minecraft:emerald`: a real registered native item.
- `worldsmith:content/<blockId>`: an existing custom block's actual BlockItem.
- `worldsmith:item/<itemId>`: a world-bound ordinary item from this module.

Do not use internal host identifiers. Generated ordinary ItemStacks retain their
bundle identity, logical item ID, model, name, rarity, stack limit and description.
Never reconstruct a reward with only its underlying shared Item type, because that
would discard its logical identity and appearance.

## Authoring and obtaining items

1. Upload/draw the actual icon PNG and retain the returned revision/asset hash.
2. Commit the complete `items` library with `worldsmith_put_content_modules` at
   expectedRevision. It shares the same revision boundary as all other domains.
3. Link items from `theme.beats[].content` using `{"kind":"item","id":"..."}`.
4. Reference them in creature drops or structure container rewards.
5. Plan and publish the whole bundle. New writes use format 4; old format 3 remains
   readable without gaining item/drop semantics or silently changing its hash.
6. In the joined local world, the Worldsmith creative tab lists all active ordinary
   items automatically, alongside blocks and species summoners.

## Creature drops

A creature may declare an optional `drops` list. Example:

```json
"drops": [{
  "item": "worldsmith:item/amethyst_heart",
  "minCount": 1,
  "maxCount": 1,
  "chance": 0.75,
  "requirePlayerKill": true
}]
```

At most 16 independent rolls. Counts are 1..64 with minCount <= maxCount and must
fit the selected item's actual stack limit; one successful entry creates one stack.
Chance is finite 0..1. No looting multiplier, arbitrary predicates, guaranteed quest
progress, equipment rules or client-provided entity payloads are implied. Rewards
are resolved before activation and rolled by the server during the normal death
loot stage, respecting native mob-drop rules. `requirePlayerKill` uses the native
player-kill attribution; false permits environmental kills as well.

## Structure rewards

Existing container interactions accept the same item aliases. Fixed inventory:

```json
{"kind":"container","at":{"x":4,"y":1,"z":7},
 "items":[{"slot":0,"item":"worldsmith:item/amethyst_heart","count":1}]}
```

Or use an inline loot entry inside the existing pool:

```json
{"minRolls":1,"maxRolls":2,
 "entries":[{"item":"worldsmith:item/amethyst_heart","weight":1,"minCount":1,"maxCount":1}]}
```

Do not combine fixed items, inline loot and an external lootTable on one container.
Native export retains the ordinary item's components in actual loot JSON and block
entity inventory. These are obtainable rewards, not a quest or achievement system.
