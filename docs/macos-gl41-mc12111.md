# Voxy on macOS (OpenGL 4.1) for Minecraft 1.21.11

## Summary

- This branch (`opus-branch-1-1.21.11`) is upstream Voxy's 1.21.11 port (branch `12111`, Voxy 0.2.16-beta, May 2026) with the macOS work from the 1.20.1 branch applied on top. That work comprises:
  - the OpenGL 4.1 render backend;
  - the shader-pack fixes;
  - server-side ingest of every chunk that becomes a full chunk;
  - the one-time import of saved chunks;
  - the autotest harness.
- The 1.20.1 build is based on newer upstream code (September 2026). Upstream features added after May 2026 are on the 1.20.1 build but not on this one.
- On an Apple M4 Pro with macOS 26.7.1, the release jar renders LoDs with and without Complementary Unbound in a production launch on Java 21.

## Installation

| Component | Requirement | Tested version |
|---|---|---|
| Minecraft | 1.21.11 | 1.21.11 |
| Fabric Loader | 0.14.22 or newer | 0.18.4 |
| Fabric API | for 1.21.11 | 0.140.2+1.21.11 |
| Sodium | exactly 0.8.4, 0.8.6, 0.8.7, 0.8.11 or 0.8.12 (declared by upstream) | 0.8.12 |
| Iris (optional) | 1.10.x for 1.21.11 | 1.10.7 |
| Java | 21 or newer | Corretto 21 (production), Corretto 25 (dev) |

- `./gradlew build` produces the jar `build/libs/voxy-0.2.16-beta-mc12111-gl41-<commit>.jar`. Earlier jars in that folder are kept.
- Ingest and import behave as on 1.20.1: chunks that become full on the integrated server are ingested, and each dimension's saved chunks are imported once.

## Changes needed for 1.21.11

| Area | 1.20.1 build | This branch |
|---|---|---|
| Vanilla depth input to the LoD pipelines | depth texture | 12111's framebuffer object, read through a GL 4.1-safe attachment query |
| LoD cut-out around loaded chunks | `BoundRenderer` | `ChunkBoundRenderer`, given the same GL 4.1 path: positions from a texture buffer, base instance as a uniform |
| Server-side ingest hook | `ChunkMap.protoChunkToFullChunk` | `ChunkStatusTasks.full` |
| Block atlas readback | bind + `glGetTexImage` | the same; upstream's `glGetTextureImage` needs GL 4.5 |
| JOML memory writes | `JomlMem` | `JomlMem` also in `ChunkBoundRenderer`; JOML's unsafe backend fails in production launches on arm64 |
| macOS native libraries | lmdb, zstd and rocksdb for macOS included | added; upstream ships Windows and Linux only |
| Autotest | run dir `run-autotest` | run dir `run-autotest-1211`, so 1.21.11 never opens (and upgrades) the 1.20.1 test worlds; screenshots read back with GL instead of the asynchronous vanilla screenshot |

## Test results

The machine, window size (1280x720 points, 2560x1440 framebuffer), seed and viewpoints are the same as in the 1.20.1 document. The test world was pre-generated with Chunky to radius 1024 on 1.21.11.

| Scenario | Result |
|---|---|
| `seed` (Chunky pre-generation, server-side ingest) | PASS |
| `smoke` | PASS |
| `stress` (eviction, flight, Nether and back) | PASS |
| `smoke-shaders` (Complementary Unbound r5.9.3, Voxy must render through the pack) | PASS |
| production `smoke`, `smoke-shaders` (release jar, Java 21) | PASS |
| unit tests (21) | PASS |

Mean frame time in milliseconds, Voxy on / Voxy off, production jar on Java 21. Metric definitions are in the 1.20.1 document.

| Viewpoint | No shaders | Complementary Unbound | LoD coverage, no shaders / shaders |
|---|---|---|---|
| meadow-north | 14.3 / 0.9 | 23.0 / 11.0 | 0.37 / 0.37 |
| plateau-west | 13.7 / 0.8 | 23.2 / 11.2 | 0.43 / 0.42 |
| taiga-east | 12.4 / 0.9 | 31.1 / 18.8 | 0.26 / 0.25 |
| overview-high | 13.4 / 0.8 | not comparable | 1.00 / not comparable |

- **Not comparable:** during the production shader run the window was resized before the overview-high viewpoint, so that capture is 3456x1980. In the same run, taiga-east's Voxy-off value (18.8 ms, p99 37 ms) was out of line with the dev run, which suggests other load on the machine.
- **Same coverage:** LoD coverage without shaders is the same as on 1.20.1 (0.37 / 0.44 / 0.27 / 1.00).
- **Frame times:** 1–2 ms higher than on 1.20.1 without shaders. With shaders, the first two viewpoints match 1.20.1 (23–24 ms).
- **Clouds:** at overview-high (y=300) the camera is inside Complementary's cloud layer on 1.21.11, which was not the case on 1.20.1.

## Known limitations

- **Inherited from the 1.20.1 build:** no occlusion culling on GL 4.1; the GL 4.6 path was not re-verified; thin dark streaks over LoD water.
- **Dev run only:** it adds Iris's nested libraries (glsl-transformer 3.0.0-pre3, jcpp, antlr) because Loom does not load them. Upstream's spark and fabric-permissions-api dev mods are left out, because the permissions API crashes on 1.21.11 once Chunky checks a permission.

## Running the tests

The commands are the same as on 1.20.1 (`scripts/autotest.sh <scenario>`, `AUTOTEST_PROD=1 AUTOTEST_JAVA=21` for the production launch). Results, baselines and worlds are in `run-autotest-1211/`.
