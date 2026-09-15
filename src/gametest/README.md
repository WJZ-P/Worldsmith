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
