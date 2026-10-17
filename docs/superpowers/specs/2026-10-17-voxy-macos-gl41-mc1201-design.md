# Voxy on macOS: OpenGL 4.1 renderer and Minecraft 1.20.1 port

## Summary

- Goal: a Voxy jar for Minecraft 1.20.1 (Fabric, Sodium 0.5.13, optionally Iris 1.7.6) that renders LODs on macOS, where the driver exposes OpenGL 4.1 core only.
- Voxy's current renderer depends on GL 4.3–4.6 features at every stage (compute traversal, SSBOs, indirect-count draws, DSA, persistent mapping). None of these exist on Apple's driver, so the GL 4.1 path is a separate render backend, not a compatibility shim.
- Proposed backend: LOD traversal, culling and draw-list generation move to the CPU (Java); the GPU renders with a small number of instanced draws that fetch quads, models and per-draw data from texture buffer objects (TBOs).
- The 1.20.1 port starts from an existing community port (m3t4f1v3/voxy `mc_1201`, Voxy 0.2.14, Sodium 0.5.13, Iris 1.7.6, Mojang mappings), merged onto the current `dev` (Voxy 0.2.20).
- Iris shader-pack support is part of the same plan as a later phase, after the non-shader GL 4.1 path is verified.
- An automated harness launches the dev client, drives scripted camera positions in a fixed world, captures screenshots and metrics, and exits; each change is verified with it.
- The existing GL 4.6 backend is kept and auto-selected when the context supports it. It cannot be run on the development machine, so on 1.20.1 it is compile-checked only.

## Scope

| In scope | Out of scope |
|---|---|
| Fabric 1.20.1 build of the current `dev` code | Forge / NeoForge / Embeddium builds |
| GL 4.1 backend for vanilla (no shader pack) rendering | Keeping MC 26.2 support in the same tree |
| Iris 1.7.6 integration on GL 4.1 (phase 4) | Shader packs without Voxy patch data |
| macOS natives for storage (RocksDB, LMDB, zstd) | Publishing the jar (Voxy is "All Rights Reserved") |
| Automated in-game test harness | GL 4.6 runtime validation on 1.20.1 |

## Platform constraints

Measured on the development machine (Apple M4 Pro, macOS 26.7.1, `GL_VERSION` "4.1 Metal - 90.5", core forward-compatible context) with standalone CGL test programs. The extension list matches opengl.gpuinfo.org reports for M1, M2 and M4. All timings are from synthetic tests on one machine and are indicative only.

| Feature or limit | Result | Consequence |
|---|---|---|
| Compute shaders, SSBOs, image load/store, atomic counters | Absent | No GPU traversal, scatter, prefix sum or culling as written |
| `ARB_multi_draw_indirect`, `ARB_indirect_parameters`, `ARB_base_instance`, `ARB_shader_draw_parameters` | Absent; `glDrawElementsIndirect` exists but ignores `baseInstance` | No GPU-generated multi-draws; per-draw data must come from `gl_InstanceID`, `gl_VertexID` or uniforms |
| `ARB_buffer_storage`, `ARB_direct_state_access`, `ARB_clear_texture`, `ARB_texture_view`, `ARB_copy_image` | Absent | Bind-to-edit buffers and textures, mapped-range or `glBufferSubData` uploads |
| `KHR_debug`, `ARB_gpu_shader_int64` | Absent (only `EXT_debug_label`/`EXT_debug_marker`) | Errors via `glGetError` polling; quads handled as `uvec2` |
| GLSL 4.10 | No `layout(binding=)`, explicit uniform locations, `buffer` blocks, `textureQueryLevels`, `packHalf2x16` | Sampler units, UBO bindings and uniform locations assigned by name after linking |
| `GL_MAX_TEXTURE_BUFFER_SIZE` | 2^28 texels; 1 GiB R32UI TBO read back correctly | Up to 2 GiB of quads as an RG32UI TBO |
| `GL_MAX_UNIFORM_BLOCK_SIZE` | 64 KiB | Per-frame lists go in TBOs, not UBOs |
| Fragment / vertex / combined texture units | 16 / 16 / 80; 17 fragment samplers fails to link | Shader-pack sampler budget of at most 14 next to Voxy's own samplers |
| `glMultiDrawElementsBaseVertex` | 0.10–0.14 µs per sub-draw (50k sub-draws: 6–7 ms CPU), same as a draw loop | Not used for per-section draws |
| One instanced draw pulling from a TBO | 0.03 ms CPU for the same geometry | Chosen draw path |
| `glCopyBufferSubData` | Driver logs "NEEDS IMPLEMENTATION"; CPU copy; blocks while the source has pending GPU writes | Not used on hot paths |
| `glBufferSubData` (64 KiB, buffer in use) / `glMapBufferRange` with `UNSYNCHRONIZED` | 0.01 ms / 0.002 ms, no stall observed | Upload path |
| Fence poll / PBO `glReadPixels` call | 7 µs / 0.06 ms | Async readbacks are viable |
| Transform feedback, `glDrawTransformFeedback` | Works; 1M points captured in 0.5 ms | Option for GPU culling later |
| Indirect draws while a `PRIMITIVES_GENERATED` query or transform feedback is active | About 38 µs per call | Avoid that combination |

Minecraft 1.20.1 requests a 3.2 core forward-compatible context; on macOS this returns 4.1 core, and LWJGL 3.3.1 reports `OpenGL41`. This was confirmed with CGL directly, not yet inside the running game.

## Approaches considered

| Approach | Description | Assessment |
|---|---|---|
| A. CPU traversal + TBO vertex pulling | Port the traversal, cleaner and command generation to Java; render with instanced draws reading TBOs | Selected. Pure Java + GL 4.1, no native code. Loses GPU occlusion culling initially. |
| B. Emulated compute | Rewrite compute passes as transform-feedback or fragment GPGPU passes | Traversal needs scatter and atomics; every pass becomes a multi-pass emulation. High complexity, unclear benefit over A. |
| C. Metal traversal via JNI | Approach of the voxy-yaoxi-std fork: Metal compute traversal, GL 4.1 raster | Requires a native build with the Xcode Metal toolchain and GL/Metal synchronisation. The fork did not run on the target machine. |
| D. GL 4.6 translation layer | Zink on MoltenVK | Requires user-installed Mesa builds; MoltenVK feature gaps make GL 4.6 unlikely. Not a mod-level fix. |

## Minecraft 1.20.1 port

The port is a merge, not a rewrite. m3t4f1v3/voxy `mc_1201` (head `69b19fee`) diverged from upstream at `41dd201d` (2026-04-09) and changed 103 files to target 1.20.1. Upstream `dev` (`534d58ec`, the base of this work) has 123 commits since that point touching 129 files. The merge is done on the working branch, conflicts are resolved file by file, and code added upstream after the merge base is then adapted using the port's patterns.

| Area | Current (26.2) | 1.20.1 target |
|---|---|---|
| Loom | `net.fabricmc.fabric-loom` 1.16 (unobfuscated) | `net.fabricmc.fabric-loom-remap` 1.16.x with `loom.officialMojangMappings()`; Gradle 9.6 wrapper unchanged |
| Java bytecode | release 25 | release 17 (replace 4 `Math.clamp` and 8 `SequencedCollection` calls); mixin `compatibilityLevel` stays `JAVA_17` |
| Fabric API | 0.152.2+26.2 | 0.92.12+1.20.1 |
| Sodium | mc26.2-0.9.2 | `maven.modrinth:sodium:mc1.20.1-0.5.13-fabric` (`me.jellysquid.mods.sodium` packages, no config API) |
| Iris | 1.11.2 (compile only) | `maven.modrinth:iris:1.7.6+1.20.1` |
| ModMenu / Chunky | 20.0.0 / 26.2 build | 7.2.2 / 1.3.146 |
| LWJGL lmdb, zstd | 3.4.1, Windows and Linux natives only | 3.3.1 (matches the game), plus `natives-macos` and `natives-macos-arm64` |
| RocksDB | macOS `.jnilib` stripped from the jar | macOS natives kept; RocksDB + zstd is the default storage, so this is required on macOS |
| Config UI | Sodium 0.6+ config API | Page injected into Sodium 0.5's options screen (from the port) and ModMenu |
| Debug info | `DebugScreenEntries` (1.21.9+) | Injection into `DebugScreenOverlay` |
| Optional compat | Flashback, Vivecraft, Nvidium | Kept only where the port keeps them and a 1.20.1 build exists |

Main API differences the merge has to resolve: `Identifier` → `ResourceLocation`, `ARGB` → `FastColor`, `DeltaTracker` → partial tick, `ChunkSectionLayer` → `RenderType`, the 26.x model API (`BlockStateModelPart`, `BlockTintSource`) → `BakedModel`/`BlockColor`, `LevelExtractor` → `LevelRenderer`, and the 1.20.1 `LevelRenderer.renderLevel` and `FogRenderer.setupFog` signatures.

## GL 4.1 backend

### Frame flow

| Step | GL 4.6 backend (current) | GL 4.1 backend |
|---|---|---|
| 1. Apply node manager results | Compute memcpy into the geometry buffer; compute scatter into node and section-metadata buffers | `glBufferSubData` into the geometry buffer; scatter writes applied to CPU mirrors of node and section metadata |
| 2. Chunk bounds | Instanced AABB raster of Sodium-loaded sections, positions from an SSBO, `baseInstance` offsets | Same raster; positions from a TBO, offset passed as a uniform per batch |
| 3. Depth/stencil setup | Fullscreen copy of vanilla depth into Voxy's depth-stencil target | Same, GLSL 4.10 |
| 4. Render last frame's visible set, build HiZ, traverse on GPU | Two-pass occlusion culling | Not used. CPU traversal runs on the current camera before drawing |
| 5. Visibility + command generation | Raster occlusion test writing an SSBO, then compute `cmdgen`, prefix sum and translucent sort | CPU builds draw entries for the visible sections: face-direction masks, translucent back-to-front bucket sort |
| 6. Opaque, temporal and translucent draws | `glMultiDrawElementsIndirectCount` | A few `glDrawElementsInstanced` calls; no temporal pass |
| 7. SSAO | Compute shader with `imageStore` | Fullscreen fragment pass writing the same texture through an FBO |
| 8. Final composite (fog, fade, depth) | Fragment pass | Same, GLSL 4.10 |
| 9. Node cleanup when geometry is nearly full | Compute partial sort of last-visible frames | CPU scan of the last-visible array for the 256 oldest leaf meshes |

### CPU-side data

- Node mirror: 16 bytes per node, `1<<21` nodes (32 MiB), updated from the same scatter-write batches the GPU path receives.
- Section metadata mirror: 32 bytes per section, `1<<20` sections (32 MiB): position, AABB, quad start and the eight per-face quad counts.
- Last-visible frame per node (8 MiB), replacing the GPU visibility buffer used by the cleaner.
- Top-level node list, maintained from the existing add/remove callbacks.

### CPU traversal

- A direct port of `traversal_dev.comp` and `screenspace.glsl`: breadth-first over top-level nodes, per node a frustum test, render-distance test, projected screen-space area, then descend, render, or request.
- Requests use the existing batch format (`count` + `uvec2` positions) and the same soft limit (scaled by mesh-queue fill, max 50 per frame), and set the "requested" flag in the mirror.
- No occlusion culling in the first version. HiZ culling depends on GPU data that would arrive one or more frames late on the CPU.
- Runs on the render thread first. Moving it to a worker thread, overlapping with vanilla rendering, is a phase 5 option.

### GPU data layout

| Resource | Format | Contents |
|---|---|---|
| Geometry | RG32UI TBO over one buffer (size capped by the TBO limit and free memory) | 8-byte quads, unchanged format |
| Models | RGBA32UI TBO, 4 texels per model | 64-byte `BlockModel` records |
| Model colours | R32UI TBO | Biome tint LUT |
| Draw entries | RGBA32UI TBO, ring of 3 buffers guarded by fences | Per entry: quad start, quad count and flags, section position (2 × u32) |
| Model atlas, lightmap, depth bound | 2D textures | As in the GL 4.6 path |

### Draw submission

- Each visible section contributes one entry per visible face range (up to 6 axis faces, 1 double-sided range, 1 translucent range). Ranges longer than a bucket are split.
- Entries are grouped by bucket size (initially 16, 128 and 1024 quads). Each bucket is one `glDrawElementsInstanced` call over a shared quad index buffer: `gl_InstanceID` selects the entry, `gl_VertexID >> 2` selects the quad within it, and quads beyond the entry's count are emitted outside the clip volume.
- Translucent entries use a single bucket size so that instance order equals the back-to-front sort order.
- Expected cost: 3–4 draw calls per pass, plus the entry upload (16 bytes per entry, order of 100k entries and 1.6 MB per frame; to be measured).

### Uploads and shaders

- `UploadStream` keeps its API (reserve memory, write, commit). On GL 4.1 the staging memory is CPU-side and `commit` issues `glBufferSubData` per destination range; no `glCopyBufferSubData`.
- `DownloadStream` is only used by GL 4.6 code paths; GPU timing uses timer queries (core since 3.3).
- GL wrapper classes (`GlBuffer`, `GlTexture`, `GlFramebuffer`, `GlVertexArray`, `GlPersistentMappedBuffer`) gain non-DSA implementations, selected once from the capability check.
- Shared shaders (post, bounds, composite) are run through a GLSL 4.10 preprocessing step: rewrite `#version`, strip `layout(binding=N)` and `layout(location=N) uniform` and record them, then assign sampler units, UBO bindings and a logical-to-real uniform location table after linking. Java call sites that use fixed uniform locations go through that table.
- Terrain shaders are new files under `shaders/lod/gl41/`, keeping the fragment structure of `quads.frag` (including the `PATCHED_SHADER` entry point) so the Iris phase can reuse it.
- The printf debug facility (SSBO-based) is disabled on GL 4.1.

### Backend selection

`Capabilities` decides once per context: GL 4.6 backend if compute and indirect-count draws are present; GL 4.1 backend if the context is 4.1+ core with texture buffers and `ARB_texture_storage`; otherwise Voxy disables rendering with a logged reason (current behaviour). A `-Dvoxy.backend=gl41|gl46|auto` override is provided for testing.

## Iris integration (phase 4)

- Start from the Iris 1.7.6 mixins in the merged port (pipeline creation, program set, samplers, uniforms, standard macros, render-target directives).
- The pack's `voxy_opaque.glsl` / `voxy_translucent.glsl` patches are compiled against the GL 4.1 terrain shader's `PATCHED_SHADER` interface, as GLSL 4.10.
- Packs whose Voxy patch data declares SSBOs are rejected with a logged reason; GL 4.1 cannot bind them.
- Sampler budget: Voxy's fragment stage uses the model atlas and the depth bound; pack samplers are capped so that at least one fragment unit stays unused (at most 14 pack samplers).
- Reported Apple GLSL linker crash on fragment-scope global initializers in pack patches (from the voxy-yaoxi-std fork): handled by hoisting those initializers into `main()` when present. Not reproduced yet.
- Test packs: to be chosen by the user; each must ship Voxy patch data matching the patch `VERSION` in `IrisShaderPatch`.

## Automated test harness

- A separate dev-only Fabric mod (Loom `testmod` source set), loaded only by a dedicated run configuration and never included in the release jar.
- `scripts/autotest.sh <scenario>` runs `./gradlew runAutotest` with a time limit, launches straight into a fixed-seed singleplayer world (`--quickPlaySingleplayer`), and exits non-zero on timeout, crash, failed checks or missing report.
- World: created by the harness on first run with a fixed seed. A one-time `seed` scenario pre-generates terrain with Chunky; Voxy ingests the generated chunks into its storage, which later runs reuse.
- Fixed environment: time 6000 with daylight cycle off, clear weather, vanilla render distance 8, fixed FOV and window size, GUI hidden, `pauseOnLostFocus` off.
- Each scenario visits a list of camera positions; at each it waits until Voxy's mesh and model queues are empty (or a timeout), renders a settle period, captures a screenshot, and samples metrics over a fixed number of frames.
- Output per run: `run/autotest/<timestamp>/` with `report.json`, screenshots, a copy of `latest.log`, and a GL capability dump.
- Screenshots are also inspected visually after each iteration.

| Metric | Definition | Measurement |
|---|---|---|
| Frame time (mean, p99) | Wall time between consecutive frames | Sampled over 300 frames at each camera position |
| Voxy CPU time | Time spent in Voxy's per-frame work, split into traversal, draw-list build, upload, draw submission | Existing `TimingStatistics` plus new GL 4.1 timers |
| Visible sections, entries, quads | Sections selected by traversal, draw entries emitted, quads submitted | Counters in the GL 4.1 backend |
| GL errors | Count of non-zero `glGetError` results | Polled after each Voxy pass when `-Dvoxy.glCheck=true` |
| Exceptions | Voxy-related exceptions or mixin failures | Log scan |
| LOD coverage | Fraction of pixels that differ between a Voxy-on and a Voxy-off screenshot from the same position | Computed by the harness; near 0 means no LODs were drawn |
| Baseline difference | Mean absolute per-pixel difference against the last accepted screenshot for the same position | Computed by the harness; flags regressions, not correctness |

## Phases

| Phase | Work | Exit check |
|---|---|---|
| 0. Port | Merge `mc_1201`, resolve conflicts, adapt post-merge-base upstream code, Java 17, macOS natives | `./gradlew build` succeeds against 1.20.1 |
| 1. Harness and boot | Harness mod and script, GL capability dump, Chunky world seeding | Script boots the game with Voxy on macOS; chunks are ingested and stored; backend check logs "GL 4.6 unavailable" without crashing; screenshot and report written |
| 2. GL 4.1 foundation | Non-DSA wrappers, upload stream, shader preprocessing, backend selection, CPU mirrors | All GL 4.1 shaders compile and link in-game; no GL errors with the backend initialised |
| 3. GL 4.1 renderer | CPU traversal and cleaner, draw entries, terrain shaders (opaque, translucent), chunk bounds, depth/stencil, SSAO, composite | LOD coverage above threshold at all positions; zero GL errors; frame-time baseline recorded |
| 4. Iris | Iris 1.7.6 bridge on GL 4.1, sampler budget, patch compilation | Chosen pack(s) render LODs with no GL errors; packs without patch data fall back cleanly |
| 5. Performance and release | Profiling; candidates: traversal on a worker thread, bucket tuning, CPU occlusion culling from an async HiZ readback, transform-feedback culling; release jar | Frame-time targets (to be agreed) met; release jar built and loaded in a clean 1.20.1 instance |

## Risks and open questions

| Risk or question | Impact | Mitigation or next step |
|---|---|---|
| CPU traversal cost at large render distances | Render-thread time grows with visited nodes | Measure in phase 3; worker thread and incremental traversal in phase 5 |
| No occlusion culling | More hidden geometry submitted than in the GL 4.6 path | Measure overdraw cost; CPU HiZ or transform-feedback culling in phase 5 |
| Draw-entry upload volume | Per-frame upload proportional to visible ranges | Measure; persistent entries per section with per-frame index lists if needed |
| Merge correctness | Upstream changes after the merge base may interact badly with the port | Compile errors resolved in phase 1; rendering differences caught by phase 3 screenshots |
| Apple driver behaviour inside the game differs from standalone tests | Unexpected stalls or crashes | Capability dump and per-pass timers in every harness run |
| GL 4.6 path on 1.20.1 is not run | Regressions for non-macOS users go unnoticed | Kept compiling; flagged as unverified |
| Shader-pack availability | Only packs with Voxy patch data work; patches using SSBOs cannot | User to name target packs before phase 4 |
| Licensing | Voxy is "All Rights Reserved" | Personal use only; no publishing |
| Performance target | No agreed definition of "acceptable" | User to state target render distance and frame rate before phase 5 |
