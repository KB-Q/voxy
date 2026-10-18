# Voxy on macOS (OpenGL 4.1) for Minecraft 1.20.1

## Summary

- This branch adds a second render backend that needs only OpenGL 4.1 core, the highest version macOS provides. The backend is chosen at startup; machines with OpenGL 4.6 compute and indirect-count draws keep the original GPU-driven renderer.
- LoD traversal, node cleanup and draw-list generation run on the CPU against mirrors of the node tree. Terrain is drawn with one instanced draw per draw-entry bucket, reading quads, block models and draw entries from texture buffers.
- Iris shader packs work on the GL 4.1 backend. The tested pack is Complementary Unbound r5.9.3 with default settings.
- The release jar was tested in a production launch (remapped jar, original mod jars) on Java 17 and Java 25 on an Apple silicon Mac.

## Installation

| Component | Requirement | Tested version |
|---|---|---|
| Minecraft | 1.20.1 | 1.20.1 |
| Fabric Loader | 0.14.22 or newer | 0.19.3 |
| Fabric API | 0.91.1 or newer for 1.20.1 | 0.92.12+1.20.1 |
| Sodium | 0.5.11 up to, not including, 0.6.0 | 0.5.13 |
| Iris (optional) | 1.7.x for 1.20.1 | 1.7.6 |
| Java | 17 or newer | Corretto 17.0.19, Corretto 25.0.3 |

- The jar is produced by `./gradlew build` in `build/libs/voxy-<version>-<commit>.jar`. It replaces any other Voxy jar in the `mods` folder.
- Voxy only renders terrain it has ingested. Chunks are ingested when the client loads them, or when Chunky generates them while Voxy is installed. Chunks generated before either of these (for example the spawn area of a world pre-generated with Chunky, or areas explored before Voxy was installed) stay missing until `/voxy import current` is run once in that world. Missing areas show up as rectangular pits exposing underground cross sections.
- `-Dvoxy.backend=auto|gl46|gl41` overrides the backend choice. `auto` selects GL 4.6 when compute shaders and indirect-count draws exist, otherwise GL 4.1.

## Differences between the backends

| Stage | GL 4.6 backend | GL 4.1 backend |
|---|---|---|
| Node tree and section metadata | GPU buffers, scatter writes by compute | CPU mirrors, scatter writes applied in Java |
| Geometry upload | compute memcpy from a persistent mapped stream | `glBufferSubData` from a CPU staging arena |
| Traversal | compute, HiZ occlusion culling | Java breadth-first traversal, frustum and render-distance culling only |
| Node cleaner | compute sort of visibility | Java partial selection of the 256 least recently rendered nodes |
| Draw generation | compute command generation, `glMultiDrawElementsIndirectCount` | Java draw entries in buckets of 1024/128/16 quads, `glDrawElementsInstanced` |
| Translucent order | GPU prefix sum, far to near | CPU counting sort, far to near, one draw |
| SSAO | compute shader | same shader as a fullscreen fragment pass |
| Shaders | GLSL 4.60 | GLSL 4.10, `layout(binding)` and explicit uniform locations applied after linking |

## Test results

All results are from one machine: Mac16,7 (Apple silicon, 14 cores, 48 GB), macOS 26.7.1, window 1280x720 points (2560x1440 framebuffer), vanilla render distance 8, Voxy defaults (LoD render distance 16, subdivision size 64). The test world uses seed 8675309, pre-generated with Chunky to radius 1024 and imported with `/voxy import current`.

| Scenario | What it checks | Result |
|---|---|---|
| `smoke` | 4 fixed viewpoints, renderer present, LoD coverage, pixel diff against a baseline, GL errors | PASS |
| `smoke-shaders` | `smoke` with Complementary Unbound; Voxy must render through the pack (no patch fallback) | PASS |
| `stress` | 320 MB geometry capacity (continuous eviction), 12 viewpoints across the world without waiting for meshing, Nether and back | PASS |
| production `smoke`, `smoke-shaders` | the same scenarios with the remapped release jar, Java 17 | PASS |
| unit tests | node mirror, traversal, cleaner, draw entries, pack shader fixups, matrix layout (21 tests) | PASS |

Metric definitions:

- **Frame time**: wall-clock time between consecutive frames, 300 frames per viewpoint after 120 settle frames, vsync off, frame limit 260. "Voxy off" is the same viewpoint with Voxy's draw suppressed for the same number of frames.
- **LoD coverage**: fraction of pixels where any colour channel differs by more than 24 between the Voxy-on and Voxy-off captures, i.e. the share of the image drawn by Voxy.
- **Baseline diff fraction**: fraction of pixels where any channel differs by more than 24 from a stored capture of a known-good run. Failure thresholds are 0.008 without shaders and 0.04 with shaders (water animation).

Mean frame time in milliseconds, Voxy on / Voxy off, production jar on Java 17:

| Viewpoint | No shaders | Complementary Unbound | LoD coverage, no shaders / shaders |
|---|---|---|---|
| meadow-north (-128, 150, 220) | 12.2 / 1.0 | 24.0 / 10.9 | 0.37 / 0.34 |
| plateau-west (220, 140, -100) | 12.0 / 0.9 | 24.1 / 10.9 | 0.44 / 0.41 |
| taiga-east (0, 115, 0) | 10.7 / 0.9 | 22.7 / 11.6 | 0.27 / 0.20 |
| overview-high (0, 300, 0) | 9.8 / 0.8 | 24.2 / 8.3 | 1.00 / 0.58 |

- LoD coverage is lower with the shader pack because its atmospheric haze brings distant terrain close to the sky colour, so fewer pixels cross the difference threshold.

- CPU time of the LoD selection is 0.1-0.2 ms per frame (about 7,000 visible sections, 6 million opaque quads).
- p99 frame times are 15-31 ms without shaders. The frame sequence alternates between about 2 ms frames and long frames, which is the pattern of a GPU-bound frame without vsync rather than isolated stalls.
- Feature experiments at the same viewpoints attribute the cost as follows: skipping the terrain draws leaves 1.3-2.0 ms, a flat-colour fragment shader leaves 5.4-8.3 ms, removing fragment discards saves about 1.5 ms, removing SSAO saves about 0.3 ms. GPU timestamp queries return 0 on Apple's OpenGL, so per-pass GPU times were not measured.

## Known limitations

- **No occlusion culling on GL 4.1.** Everything inside the frustum is drawn. The GPU backend's HiZ culling has no GL 4.1 equivalent without SSBOs or image stores. Raising the subdivision size in the Voxy settings lowers the quad count.
- **GL 4.6 backend not re-verified.** Shared shaders and Java code changed (model accessors, qualifier order, matrix uploads, AsyncNodeManager structure); no GL 4.6 hardware was available.
- **One shader pack tested.** Packs that rely on other GLSL constructs the macOS compiler rejects may fall back to the non-shader LoD path. The fallback is logged as "Failed to compile shader patch".
- **Thin dark streaks.** Some captures show isolated dark lines over LoD water. The cause has not been investigated.
- **Iris warnings.** Complementary r5.9.3 references uniforms of newer Minecraft versions (`BIOME_PALE_GARDEN`, `endFlashIntensity`). Iris logs and ignores them.

## Running the tests

| Command | Purpose |
|---|---|
| `scripts/autotest.sh seed` | create and pre-generate the test world, then import it into Voxy |
| `scripts/autotest.sh smoke` | dev launch, fixed viewpoints, exits non-zero on failure |
| `scripts/autotest.sh smoke-shaders` | same with the shader pack in `run-autotest/shaderpacks/` |
| `scripts/autotest.sh stress` | eviction, movement and dimension change |
| `scripts/autotest.sh zoom` | the same far viewpoint at FOV 70 and through a spyglass (10x zoom); the F3 line "GL41 visible per lod" shows the detail levels drawn |
| `AUTOTEST_WORLD=<new name> scripts/autotest.sh amplified` | creates an amplified world, fills it by flying over it (client ingest only), compares LoD and vanilla views from below the mountains |
| `AUTOTEST_WORLD=<same name> scripts/autotest.sh amplified-shaders` | the same views with the shader pack |
| `AUTOTEST_PROD=1 AUTOTEST_JAVA=17 scripts/autotest.sh smoke` | production launch of the release jar on Java 17 |
| `./gradlew test` | unit tests |

- Each run writes `report.json`, screenshots and the game log to `run-autotest/autotest/<timestamp>-<scenario>/`. Baselines are read from `run-autotest/autotest/baselines/<scenario>/`.
- `AUTOTEST_JVM_ARGS` passes extra `-D` options, for example `-Dvoxy.debug.gl41.frag=flat`, `-Dvoxy.debug.noChunkBounds=true`, `-Dvoxy.debug.noPatch=true` (ignore shader pack LoD patches) or `-Dvoxy.debug.dumpShaders=<dir>` (write every compiled shader source).
