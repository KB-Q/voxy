# Voxy on macOS (OpenGL 4.1) for Minecraft 1.21.1

## Summary

- This branch (`opus-branch-1-1.21.1`) is the 1.20.1 branch moved to Minecraft 1.21.1, with Sodium 0.8.13 and Iris 1.8.14. It has the same Voxy code level as the 1.20.1 build: upstream from September 2026, Voxy 0.2.20. The macOS work is the same as well: the OpenGL 4.1 backend, the shader-pack fixes, server-side ingest, the one-time import of saved chunks and the autotest harness.
- Upstream Voxy has no 1.21.1 branch. The community 1.20.1 port by m3t4f1v3 was made from a 1.21.1 backport, and its 24 commits from 1.21.1 down to 1.20.1 were undone here with a three-way merge. The general fixes among those commits were kept.
- On an Apple M4 Pro with macOS 26.7.1, the release jar renders LoDs with and without Complementary Unbound in a production launch on Java 21. LoD coverage and frame times match the 1.20.1 build within about 1 ms.

## Installation

| Component | Requirement | Tested version |
|---|---|---|
| Minecraft | 1.21.1 | 1.21.1 |
| Fabric Loader | 0.14.22 or newer | 0.19.3 |
| Fabric API | 0.102.0 or newer for 1.21.1 | 0.116.17+1.21.1 |
| Sodium | 0.8.12-alpha.3 up to, not including, 0.9.0 | 0.8.13 |
| Iris (optional) | 1.8.14-beta.1, the Iris build for Sodium 0.8 | 1.8.14-beta.1 |
| Java | 21 or newer | Corretto 21 (production), Corretto 25 (dev) |

- `./gradlew build` produces the jar `build/libs/voxy-0.2.20-beta-mc1211-gl41-<commit>.jar`. Earlier jars in that folder are kept.
- Sodium 0.6.x and Iris 1.8.8, the older 1.21.1 combination, are not supported. Sodium 0.8 changed the classes Voxy hooks into.
- Ingest and import behave as on 1.20.1: chunks that become full on the integrated server are ingested, and each dimension's saved chunks are imported once.

## Changes needed for 1.21.1

| Area | 1.20.1 build | This branch |
|---|---|---|
| Minecraft API | 1.20.1 | m3t4f1v3's 1.21.1 code restored: vertex consumer method names, `ResourceLocation.fromNamespaceAndPath`, NBT and DataResult changes, Java 21 `Integer.expand` and `compress` |
| Sodium package | `me.jellysquid.mods.sodium`, Sodium 0.5 | `net.caffeinemc.mods.sodium`, Sodium 0.8 |
| Visible sections for the LoD cut-out | `VisibleChunkCollector.visit` | `SectionCollector.visit(RenderSection, int)`; Sodium 0.8 has no `VisibleChunkCollector` |
| Sodium hook signatures | Sodium 0.5 | `RenderSectionManager` constructor with `SortBehavior`; `createTerrainRenderList` returns a boolean; `RenderSection.setInfo` returns a boolean; `DefaultChunkRenderer.render` has an extra boolean |
| Voxy settings page | Sodium 0.5 option pages | upstream's Sodium 0.8 config menu |
| Server-side ingest hook | `ChunkMap.protoChunkToFullChunk` | `ChunkStatusTasks.full`, as on 1.21.11 |
| World close hook | `Minecraft.clearLevel()` | `Minecraft.disconnect(Screen, boolean)`, which every disconnect path reaches |
| Java target | 17 | 21; the Java 17 fallbacks of the 1.20.1 port are removed |
| Autotest | run dir `run-autotest` | run dir `run-autotest-121`; the dev run has no Iris (see Known limitations), shader scenarios use the production launch |

## Test results

The machine, window size (1280x720 points, 2560x1440 framebuffer), seed and viewpoints are the same as in the 1.20.1 document. The test world was pre-generated with Chunky to radius 1024 on 1.21.1.

| Scenario | Result |
|---|---|
| `seed` (Chunky pre-generation, server-side ingest, 219 MB stored) | PASS |
| `smoke` | PASS |
| `stress` (eviction, flight, Nether and back) | PASS |
| production `smoke` (release jar, Java 21) | PASS |
| production `smoke-shaders` (Complementary Unbound r5.9.3, Voxy must render through the pack) | PASS |
| unit tests (21) | PASS |

Mean frame time in milliseconds, Voxy on / Voxy off, production jar on Java 21. Metric definitions are in the 1.20.1 document.

| Viewpoint | No shaders | Complementary Unbound | LoD coverage, no shaders / shaders |
|---|---|---|---|
| meadow-north | 12.5 / 1.0 | 24.2 / 10.8 | 0.36 / 0.34 |
| plateau-west | 12.1 / 0.8 | 24.4 / 10.9 | 0.44 / 0.41 |
| taiga-east | 11.0 / 0.9 | 23.1 / 12.4 | 0.26 / 0.19 |
| overview-high | 9.7 / 0.7 | 25.1 / 9.0 | 1.00 / 0.58 |

- **Coverage:** LoD coverage is the same as on 1.20.1: 0.37 / 0.44 / 0.27 / 1.00 without shaders and 0.34 / 0.41 / 0.20 / 0.58 with Complementary.
- **Frame times:** within 0.3 ms of 1.20.1 without shaders at every viewpoint. With Complementary they are 0.2 to 0.9 ms slower than on 1.20.1.
- **Background window:** with the test window opened behind the other windows, the dev `smoke` frame times were within 0.5 ms of a run with the window in front: 12.7 / 12.5 / 11.2 / 9.9 ms against 12.3 / 12.0 / 11.6 / 9.7 ms.

## Known limitations

- **Inherited from the 1.20.1 build:** no occlusion culling on GL 4.1; the GL 4.6 path was not re-verified; thin dark streaks over LoD water.
- **Settings page:** the cloud distance and sky fog distance options of the 1.20.1 settings page are not on the Sodium 0.8 menu. Their values in `voxy-config.json` still apply.
- **Dev run without Iris:** Loom's dev remap of Iris 1.8.14 drops the descriptor of one of Iris's mixin targets, `LevelRenderer.renderSnowAndRain`, and the game crashes at startup. `-Pvoxy.dev.iris=true` adds Iris back to the dev run. Production launches load Iris normally.

## Running the tests

The commands are the same as on 1.20.1 (`scripts/autotest.sh <scenario>`). Shader scenarios need the production launch: `AUTOTEST_PROD=1 AUTOTEST_JAVA=21 scripts/autotest.sh smoke-shaders`. Results, baselines and worlds are in `run-autotest-121/`. The test window opens behind the other windows without taking focus; `AUTOTEST_JVM_ARGS="-Dvoxy.autotest.foreground=true"` brings it to the front as before.
