# Mechanic native server smoke

This opt-in Fabric GameTest runs the real 26.2 server, the production placement
mixin, and `UseBlockCallback`. It is separate from JUnit and is excluded from the
production jar. Two distinct test-environment batches avoid competing world-content bindings.

Coverage:

- Arbitrary `setBlock` does not trigger a player-built device.
- Completing a non-anchor pattern cell through `BlockItem.place` summons exactly
  one authored creature; obstructed spawns preserve the assembled pattern without
  charging mechanic costs during the shared read-only preflight.
- Main-hand custom keys open a passage; wrong keys, foreign-world identities,
  off-hand input and spent anchors do
  not consume materials.
- A logical custom-block exchange device produces canonical custom item stacks,
  rejects full inventories and insufficient materials, checks
  cooldown, then succeeds again after the cooldown. An earlier unaffordable bulk
  recipe does not starve a later affordable recipe.
- A converted sand block receives native `onPlace` scheduling and falls normally.
- The ledger survives a native NBT codec round trip and actual runtime rebind,
  preserving one-shot state and activation counts.

The fixture is assembled from checked-in terrain ingredients and an in-memory
32x32 PNG. It never discovers, rewrites or loads user packs or saves.

The second test uses `MechanicDiscoveryExample` itself, not a copied fixture.
It compiles/places native structure NBT, reads the real sign and materials chest,
derives the missing cell from `MechanicGuides`, checks read-only inspection,
places the collected block via the vanilla placement mixin, kills the summoned
guardian through native player damage, picks up its actual typed key, opens the
gate and verifies both persistent player quest objectives. The test uses a distinct
environment and a temporary ledger in the isolated test dimension. This checks the
template/interaction route, not every seed's natural spawn or a human playthrough.

## Run from the checkout

With the project's Java 25 environment:

```powershell
.\gradlew.bat -PmechanicsGameTest gametestClasses --console=plain
.\gradlew.bat -PmechanicsGameTest runGameTest --console=plain
```

The opt-in property creates the separate test-mod source set; normal builds are
unchanged. The runner always uses a fresh directory under `build/`, including
when launched from the normal checkout.

## Existing isolated validation build

The existing `build/structure-efficiency-validation/build.gradle` applies
`src/gametest/validation.gradle` only when `-PmechanicsGameTest` is present:

```groovy
if (project.hasProperty('mechanicsGameTest')) {
    apply from: new File(checkout, 'src/gametest/validation.gradle')
}
```

Use the checkout's configured Java 25 environment (including its local JVM patch
when required), then run from the checkout:

```powershell
.\gradlew.bat -p build/structure-efficiency-validation -PmechanicsGameTest gametestClasses --console=plain
.\gradlew.bat -p build/structure-efficiency-validation -PmechanicsGameTest runGameTest --console=plain
```

Every invocation chooses a fresh directory below `build/interaction-kernel/`;
there is no recursive deletion or shared `run/` directory. The runner is headless
and emits `build/interaction-kernel/native-gametest.xml`. A successful result must
contain two executed tests with zero failures, not merely a successful Gradle exit.

Add `-PmechanicDiscovery` to put the fresh directory and native report under
`build/mechanic-discovery/` instead. Neither mode uses a player's save directory.

## Client guide UI regression

```powershell
.\gradlew.bat -PmechanicsGameTest -PmechanicDiscovery -PmechanicGuideClientTest runClientGameTest --console=plain
```

The client test creates its own flat singleplayer save under a fresh
`build/mechanic-discovery/client-gametest-*` directory. It activates the sample's
verified content for the UI, opens the actual journal/guide, sends a real
read-only inspection request, exercises layer/goal navigation, and checks
back/J-key/disconnect cleanup. It never opens existing player saves.

Screenshots cover English and Chinese at normal scale and a 320x180 GUI. Capture
waits for resource-reload overlays to disappear and rendered frames to settle.
`build/mechanic-discovery/client-guide-report.txt` lists the images after a
successful run. Review the images as well as the test outcome; a nonempty PNG
alone does not prove readable layout. This UI fixture is separate from the
natural-world placement and gameplay checks in the server tests.

## Programmable ability integration

```powershell
.\gradlew.bat -PmechanicsGameTest -PabilityRuntime runGameTest --console=plain
.\gradlew.bat -PmechanicsGameTest -PabilityRuntime -PabilityClientTest runClientGameTest --console=plain
```

`-PabilityRuntime` adds one aggregate test in its own `abilities` environment;
the two original mechanic tests remain. Its fresh report is
`build/ability-runtime/native-gametest.xml` and must contain **three** successful
testcases. Without that flag the expected count stays two. The additional
entrypoint is inserted only into the generated test-mod manifest, not the
production mod or the checked-in default manifest.

The aggregate uses ordinary survival `ServerPlayer` instances with embedded
connections. Minecraft's `makeMockServerPlayerInLevel()` overrides `gameMode()`
to creative, even after `setGameMode`, so that helper would silently bypass
targeting and damage tests. The embedded connection acknowledges the native
client-loaded packet so the initial 60-tick loading grace period cannot conceal
early damage. It exercises actual creature AI, native item USE,
the mechanic callback, real hurt/block-break events and projectile collisions.
It checks captured-position dodges, three explicitly programmed pulses with
invulnerability frames cleared, source-selected line of sight, NoAI/target/death
cancellation, save/reload recovery debt, bounded native arguments and operation
exhaustion. A test-mod bootstrap registers `test.floor_name` before world binding;
ordinary source calls that installed native provider and saves its actual block
result into program state. The test also checks that committing a cast enqueues
it without synchronously executing the provider. No test calls the ability's
damage provider directly.

The aggregate contains 23 scenarios, including PvP-disabled self status/push
versus another protected player, and a no-wait program whose 30-tick pose/caption
leases remain visible after its source becomes idle and then expire cleanly.
Overlapping invocations check latest-live pose/caption selection and fallback,
unrelated completion and uncommitted-reservation expiry, native navigation
ownership, and the real creature adapter's target-loss/cooldown behavior.

The small physics arena uses test-only forced tickets for the chunks intersecting
`base ± 12` blocks, waits at most 200 normal server ticks for entity-ticking
promotion before starting gameplay, and restores every prior forced-chunk flag
in `finally`. These tickets are not a production ability capability. Owned mock
player positioning also updates native chunk tracking, just as a movement packet
would. No fixture calls projectile/entity ticks or VM ticks directly.

A separate loaded-but-**non-entity-ticking** probe stays outside the forced arena.
Its actual projectile keeps the same physics tick count yet expires after its
20-tick world-time lease. An 80-tick caption keeps the invocation alive while a
test-only helper delivers the retired projectile UUID's late callback; the hit
counter must remain zero. This isolates lease retirement from ordinary collision
tests, whose original two-impact requirement remains intact.

The two navigation fixtures wait for real gravity/ground settling before launch
and use `motion.navigate` multiplier `0.5`, not an absolute blocks-per-tick speed.
With their `MOVEMENT_SPEED = 0.16`, multiplier `0.1` falls below vanilla's movement
dead zone despite an accepted path. The fixtures still require an eight-block
path, observable entity movement, and correct ownership through 60/80-tick source
lifetimes; no native movement threshold is changed.

The client flag selects the source-authored ability demonstration instead of
the guide UI fixture. It uses an isolated flat save, the real NPC goal and
player item host, then records warning, interrupted-channel, projectile callback
and finished-scene screenshots. Its success marker and image paths are written
to `build/ability-runtime/client-ability-report.txt`; inspect the images before
calling their presentation verified. These tests are an engineering regression,
not a claim of multiplayer balance or a human playthrough.

The opt-in ability, visual and story client flags add the test-mod
`IntegratedServerGametestCloseMixin` phase adapter. Minecraft 26.2 waits for a
server task inside `IntegratedServer.halt` before Fabric 6.0's usual disconnect
yield point; this adapter performs the same task while cooperating with Fabric's
test-thread phases, with a 20-second future deadline. It is excluded from the
production JAR. The fixture still calls normal `world.close()` and requires both
the active client scope and server content bindings to be cleared before writing
its PASS report.

## Story and branching-journey regression

`-PmechanicsGameTest -PstoryTest runGameTest` adds `QuestJourneyGameTests` and
`StoryRuntimeGameTests`. These exercise hidden/discovered quest views, explicit
branch acceptance, real costs, both village outcomes, a rotated native template,
resident schedules, timed death restoration and native identity/history reload.
Combine `-PabilityRuntime -PabilityEventTest -PabilityGameplayTest -PabilityVisualTest`
with these flags to execute all eight server cases in separate environment batches.

`-PmechanicsGameTest -PstoryClientTest runClientGameTest` selects the immersive
village client fixture, including the early unbound-world handshake, native entity
interaction packets, transaction terms/narration, lost-key recasting, quest choices,
actual lamp change, compact Chinese screens and normal disconnect cleanup. Its
fresh success marker and screenshot paths are written to
`build/immersion-runtime/client-story-report.txt`. Read-only client-test accessors
remain in the test source set, not the production JAR.

Direct template fixtures place an actual stone foundation before the village;
otherwise its native gravity-affected gravel falls in the test server's empty sky.
This does not replace the separate regular-export/terrain-fit check across three
seeds. Route evidence comes from `StoryRouteProbe` inspecting actual loaded blocks,
with the current player's auto-step height (capped at 0.6) and no jump simulation.
It never certifies every seed or a continent-wide road network.

## Directed materials, source control and durable consequences

Use `-PimmersionIteration` to put this iteration's run directories/reports under
`build/immersion-continuation`, preserving previous verification artifacts.
`-PabilityControlTest` adds the native MOVE+LOOK ownership case, and
`-PabilityPerceptionTest` adds native environment/scope checks and crowded periodic
observation. With the eight-case flags above, the complete server suite has ten
cases. Short observers must continue past their individual invocation lifetime,
while 48 background slots leave 16 global interactive slots available.

The story case also checks same-world opposing/late players, immediately cancelled
cosmetic source, missing-receipt partial application, loaded-only projection
recovery, conflicts, player edits after final receipts and resident rebind recovery.
It does not claim power-loss atomicity between native chunk files and SavedData.

`-PmechanicsGameTest -PimmersionIteration -PmaterialClientTest runClientGameTest`
selects the material workshop's eight real captures: six-face wood, four directed
marks, glass, actual supplied icons, survival placement and a paid one-shot light
repair. Camera checks wait for real client teleport acknowledgement. Ordinary Esc
dismisses an obstructing arrival; production UI or rendering is not replaced.
The separate offline appearance sheets are labelled as previews, not screenshots.
