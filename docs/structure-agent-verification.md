# Structure Agent targeted verification — 2026-09-06

## Completion
- Main/client compilation and Mod JAR: passed.
- 41 targeted JUnit cases: 28 Core/MCP + 13 native/legacy-compatibility; zero failures/skips.
- Worker source builds passed on Java 25 and Java 26 with jdk.compiler excluded and no PATH/system javac or Python in the child environment.
- Compile diagnostics, exit, OOM, timeout, cancellation, damaged worker output, interrupted/corrupt history, revisions and restart recovery were exercised.
- The complete documented Java/MCP sequence was replayed, including source revision, direct PNG content, architecture, format-2 persistence and native-receipt gating.
- Actual Fabric/Minecraft 26.2 dedicated-server worlds in build/structure-agent-smoke/server only. Data version 4903, seed 9412306.
- SDK group spans 24 chunks; standalone spans 15 chunks. Three logical group members expand to 8 storage fragments plus a road piece.
- Negative coordinates, mirrors/rotations, AIR/KEEP, native NBT load and piece save/load were covered.
- Real block checks verified complete bed halves across a fragment boundary, doors and double plants. A discovered per-fragment neighbor-update loss was fixed by preserving validated SDK states; legacy pieces keep their prior behavior.
- Full saved block-state hashes are identical before/after reload and in a second fresh world with the same seed.
- Loading the published native data pack created no drawing worker/source runtime directory. Source execution is absent from worldgen.

## Evidence (workspace-local, ignored build output)
- build/structure-agent-smoke/verification.json
- build/structure-agent-smoke/fixed-save.json
- build/structure-agent-smoke/fixed-reload.json
- build/structure-agent-smoke/repeat-save.json
- core/build/reports/tests/test/index.html
- build/reports/tests/test/index.html
- docs/examples/structure-agent/workflow.json

## Boundaries
No full test suite or game GUI automation was run. Client-side confirmation/preset UI code was compiled; native failure/context/revision behavior was exercised through injected host receipts plus real native export and world reload, not automated UI clicks.
Existing player saves were not edited. All verification servers were stopped. There is still no promise of a naturally placed landmark instance; landmarkInstancesVerified remains false.
Terrain/Biome design, world-generation envelopes and sampling limits were not expanded. The worker is fault/resource isolation, not an OS filesystem/network sandbox.

## Current Mod artifact
- W:\data\GithubProjects\Worldsmith\build\libs\worldsmith-0.1.0.jar
- SHA-256: `011be622e8a52e8fca34ce97076704aad00a0980acc0b2344cfc15d0d1c63bd8`
