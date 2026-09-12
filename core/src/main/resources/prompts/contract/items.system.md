# Worldsmith items — schema 2 equipment, consumables and a fixed action library

New publications use bundle format 6 with nine typed modules. `items` may retain
schemaVersion=1 for plain resources/relics, or explicitly select schemaVersion=2
for equipment, consumables and actions. Schema 2 is a typed capability library,
not arbitrary scripts. Choose capabilities that deepen this world's exploration,
combat and rewards; ordinary materials should remain ordinary when that is useful.

## Exact item library

The following is one `CustomItemLibrary`. Replace each texture placeholder with
the SHA-256 returned after uploading its actual PNG; placeholders are not handles.
Its names and lore illustrate world/playing experience, not reusable story text.

```json
{
  "schemaVersion": 2,
  "items": [
    {
      "id": "river_oath_blade",
      "displayName": "River Oath Blade",
      "textureAsset": "<ICON_SHA256>",
      "kind": "RELIC",
      "maxStackSize": 1,
      "rarity": "RARE",
      "description": "A blue edge sworn to guard the river road. Its strikes hinder a foe and steady the bearer.",
      "themeRole": "A reward for restoring the ferry beacons.",
      "equipment": {"type":"MELEE","durability":448,"attackDamage":6.0,"attackSpeed":-2.4},
      "actions": [{
        "trigger":"MELEE_HIT","cooldownTicks":40,"consumeCount":0,"durabilityCost":1,
        "effects":[
          {"kind":"heal","amount":1.0,"target":"SELF"},
          {"kind":"status","effect":"minecraft:slowness","durationTicks":40,"amplifier":0,"target":"TARGET"}
        ]
      }]
    },
    {
      "id":"wayfarer_hood",
      "displayName":"Wayfarer Hood",
      "textureAsset":"<ICON_SHA256>",
      "kind":"RELIC","maxStackSize":1,"rarity":"RARE",
      "description":"A hood threaded with the mist of old crossings. Crouch and use it from your hand to step toward firm ground ahead.",
      "equipment":{"type":"HELMET","durability":256,"armor":3.0,"toughness":1.0,"knockbackResistance":0.05,"textureAsset":"<ARMOR_SHA256>"},
      "actions":[{"trigger":"USE","cooldownTicks":80,"consumeCount":0,"durabilityCost":2,"effects":[{"kind":"blink","distance":6.0}]}]
    },
    {
      "id":"sunberry_ration",
      "displayName":"Sunberry Ration",
      "textureAsset":"<ICON_SHA256>",
      "kind":"RESOURCE","maxStackSize":16,"rarity":"UNCOMMON",
      "description":"A sweet, warming bite for the long road. Finish eating to recover health and nourishment.",
      "consumable":{"nutrition":2,"saturation":1.5,"consumeSeconds":1.2,"alwaysEdible":true,"effects":[{"effect":"minecraft:regeneration","durationTicks":100,"amplifier":0}]},
      "actions":[{"trigger":"USE","cooldownTicks":100,"consumeCount":0,"durabilityCost":0,"effects":[{"kind":"heal","amount":4.0},{"kind":"feed","nutrition":2,"saturation":1.0}]}]
    },
    {
      "id":"ember_focus",
      "displayName":"Ember Focus",
      "textureAsset":"<ICON_SHA256>",
      "kind":"RELIC","maxStackSize":1,"rarity":"EPIC",
      "description":"A coal that remembers the first watchfire. Use it to cast a weakening ember along your gaze.",
      "actions":[{"trigger":"USE","cooldownTicks":30,"consumeCount":0,"durabilityCost":0,"effects":[{"kind":"projectile","damage":4.0,"speed":1.5,"gravity":0.03,"lifetimeTicks":80,"hitEffects":[{"effect":"minecraft:weakness","durationTicks":100,"amplifier":0}]}]}]
    }
  ]
}
```

`CustomItemLibrary`: `schemaVersion` (default 1) and `items` (default [], at most
256 unique definitions). Write 2 explicitly when using any new capability.
`CustomItemDefinition` has exactly these fields:

| Field | Meaning and bounds |
| --- | --- |
| id | Local lowercase ID, 1..64 letters/digits/underscore/dot/hyphen; no `..` or trailing dot |
| displayName | 1..128 printable characters |
| textureAsset | Actual icon PNG SHA-256; square power-of-two 16..256 pixels, at most 1 MiB; transparency supported |
| kind | RESOURCE (default) or RELIC; descriptive category, not a capability |
| maxStackSize | 1..64, default 64; equipment requires 1 |
| rarity | COMMON (default), UNCOMMON, RARE or EPIC |
| description | Optional player-facing plain-text lore, at most 2048 characters/256 lines; only newline control characters |
| themeRole | Optional creative role, at most 2048 characters; not an executable condition |
| equipment | Optional `ItemEquipment`, otherwise null |
| consumable | Optional `ItemConsumable`, otherwise null; mutually exclusive with equipment |
| actions | Default []; at most two, with one entry per USE / MELEE_HIT trigger |

Schema 1 accepts the original fields only in behavior: equipment and consumable
must be null and actions empty. Schema 2 can mix ordinary and capable items.
A name, rarity or RELIC category alone adds no ability or quest lock.

## Equipment and wearable appearance

`ItemEquipment` fields and defaults:

| Field | Default | Bounds / values |
| --- | --- | --- |
| type | required | MELEE, AXE, PICKAXE, SHOVEL, HOE, HELMET, CHESTPLATE, LEGGINGS, BOOTS |
| durability | 256 | 1..100000 |
| attackDamage | 3.0 | 0..1024; additive main-hand modifier, checked again against native totals |
| attackSpeed | -2.4 | -3.9..20; additive main-hand modifier |
| armor | 0.0 | 0..30, on the armor's own slot |
| toughness | 0.0 | 0..20, on the armor's own slot |
| knockbackResistance | 0.0 | 0..1, on the armor's own slot |
| miningSpeed | 4.0 | 0.1..128, for the selected tool's effective block rules |
| miningTier | IRON | WOOD, STONE, IRON, DIAMOND, NETHERITE |
| textureAsset | null | Required separate wearable PNG for armor; absent on non-armor |

All numbers are finite. Weapon values are modifiers, not final player totals:
attackDamage=6 normally produces total 7; attackSpeed=-2.4 normally produces total
1.6. Native preparation checks those totals and real registered tier/tag rules.
MELEE uses the native iron-sword mining profile; miningTier/miningSpeed select
rules for AXE/PICKAXE/SHOVEL/HOE rather than adding a new sword-mining language.
Axes, shovels and hoes reuse native block-use transformations. Their block use
can take precedence over a USE ability; use in air to invoke the active ability.

Armor uses native equippable slots and an independent humanoid UV atlas. Keep its
inventory `textureAsset` separate from `equipment.textureAsset`: the wearable PNG
must be power-of-two **64x32, 128x64, 256x128 or 512x256**, at most 1 MiB.
HELMET/CHESTPLATE/BOOTS use `humanoid`; LEGGINGS uses `humanoid_leggings`.
Paint those native armor UV layouts, not a creature UV sheet or a flat icon.

Ordinary right-click equips armor. **Crouch-right-click while holding armor**
invokes its optional USE action. Actions are held-item triggers, not passive perks
while worn; there is no ON_EQUIP, periodic worn effect, shield, bow or crossbow type.
Equipment has native wear and optional extra action durabilityCost. A MELEE_HIT
ability runs after normal melee handling; it does not undo the original hit or
refund ordinary weapon wear if the extra ability is unavailable.

## Consumables

`ItemConsumable`: nutrition=0 (0..20), saturation=0.0 (0..20),
consumeSeconds=1.6 (0.1..10), alwaysEdible=false, effects=[] (at most eight).
Saturation is the actual native saturation amount, not a nutrition multiplier.
Each `ItemStatusEffect` is `{effect, durationTicks=200, amplifier=0}`:
`effect` is a real registered effect resource ID; durationTicks is 1..72000 and
amplifier is zero-based 0..9. Native preparation resolves every status ID.

Food uses native consumption completion. An optional USE action runs at
completion, not at the initial click; successful survival consumption already
consumes one item. Therefore **food actions require consumeCount=0 and
durabilityCost=0**, and MELEE_HIT is disallowed on food. Cancelling consumption
or a failed completion action leaves the food unfinished. Creative mode retains
its usual no-consumption behavior. Do not charge the same mouthful a second time.

## Fixed action vocabulary

`ItemAction`: trigger=USE, cooldownTicks=20 (1..72000), consumeCount=0
(0..maxStackSize), durabilityCost=0 (0..100000, at most equipment.durability;
zero without equipment), and required effects (1..8).
Triggers are exactly USE and MELEE_HIT. Each item has at most one of each.
MELEE_HIT requires non-armor equipment: MELEE, AXE, PICKAXE, SHOVEL or HOE.
Plain materials/relics and armor may have USE actions, not MELEE_HIT actions.

Effects use the `kind` discriminator; no script, command, function or arbitrary
entity payload field is accepted:

| kind | Exact fields and defaults |
| --- | --- |
| heal | amount required, 0.1..100; target=SELF or TARGET |
| feed | nutrition required, 0..20; saturation=0.0, 0..20; feeds SELF |
| status | effect required; durationTicks=200, 1..72000; amplifier=0, 0..9; target=SELF or TARGET |
| projectile | damage=4.0, 0..100; speed=1.5, 0.1..4; gravity=0.03, 0..0.2; lifetimeTicks=80, 1..200; hitEffects=[], at most four ItemStatusEffect entries |
| blink | distance=6.0, 0.1..8; USE only |

TARGET is available only for heal/status on MELEE_HIT and refers to the struck
living target. SELF means the bearer. An action may contain at most one projectile
and one blink. The projectile uses a single registered host and the source item's
appearance; it applies direct hit damage/status effects, expires or disappears on
impact, and has no terrain explosion or block-destruction mode.

Blink probes a loaded, in-border, collision-free standing position with solid
support and no liquid, along the clipped look direction, at most eight blocks
away. It does not load chunks, pass through walls, cross dimensions, or grant a
fallback teleport when no valid destination exists; mounted/sleeping use fails.

All capabilities/destinations are resolved before action effects and costs. If a
required projectile insertion or blink destination fails, the action does not
spend its own quantities/durability or start a cooldown. This is an action-local
boundary, not a rollback of a preceding native melee hit.

Cooldowns belong to **each player + world scope + logical item ID**. All stacks
and both triggers of that logical item share its cooldown. Different items do
not lock each other merely because they use one native item host. Survival costs
apply once after a successful action; creative use skips quantity/durability
costs, while cooldown and destination rules still apply. ItemActions alone starts
ability cooldown on success; native automatic host cooldown is bypassed for these
world-bound abilities. A failed food completion action neither consumes the food
nor starts a cooldown.

## Player writing and authoring diagnostics

Player-facing displayName/description must speak about this world's story or
actual controls/effects. Explain a hood's held crouch-use, a ration's completion,
or a focus's brief rest in natural gameplay language. Do not append implementation,
publication, save-progress, terrain-validation or unsupported-feature disclaimers
to lore. Put technical limitations, validation results and compatibility notes in
authoring diagnostics/receipts, never inside player-facing fields.

`PlayerTextPolicy` checks known engineering leaks at new authoring publication and
returns `PLAYER_TEXT_ENGINEERING_LEAK` with the field path. Revise the authored
field; do not silently filter it at display time. Existing immutable bundles and
saved lore are not rewritten or rejected by this new prose gate.

## References, acquisition and a representative pack icon

Use real native items (`minecraft:emerald`), custom block items
(`worldsmith:content/<blockId>`) or this library (`worldsmith:item/<itemId>`).
Never use the internal shared host ID. Rewards preserve complete item identity,
model, name, rarity, stack limit, equipment, consumption and ability components.
Native wear, repair, enchantment and renaming stay legitimate mutable stack state;
a foreign world's item does not acquire the current world's abilities.

1. Upload the icon and any separate armor atlas; retain returned asset IDs/revision.
2. Commit the complete items document with `worldsmith_put_content_modules` at the
   shared expectedRevision; ordinary resources and active gear can coexist.
3. Link real `item` ContentKeys in theme beats and the design plan. Give intended
   survival items an actual creature drop, structure-container or quest-reward source.
4. Use existing quests' explicit deliver_item/reward fields; wearing or using a
   reward does not independently complete its quest or create a crafting tree.
5. On `worldsmith_write_pack`, choose the world's most recognisable existing item
   or block through `representativeContent: {"kind":"item","id":"ember_focus"}`
   (kind may instead be block). This becomes manifest.representativeContent,
   not an items/theme field. The browser reuses its PNG without activating a world.

Creature drops keep `{item,minCount,maxCount,chance,requirePlayerKill}`: at most
16 independent rolls, counts 1..64 fitting the actual item's stack limit, finite
chance 0..1. Equipment therefore drops/rewards one per entry. Existing container
fixed items and inline loot use the same references and keep all components;
fixed items, inline loot and an external lootTable are mutually exclusive on one
container. The separate quest system owns explicit delivery and once-only claims.

## Compatibility

New writes use format 6 and the same nine typed modules; abilities need items
schema 2. Formats 3/4/5 remain read-only for restoration and unchanged re-embedding:
format 3 has seven modules and no items/quests; format 4 has eight and items/drops;
format 5 has nine, quests and supported Boss profiles, but schema-1 ordinary items.
`LegacyItemsV1` preserves the old item projection/hash domain instead of injecting
new default capability fields into old content identity. Existing packs, embedded
worlds and player progress are not automatically migrated or rewritten.
