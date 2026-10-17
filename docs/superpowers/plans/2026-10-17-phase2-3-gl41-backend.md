# Phase 2–3: OpenGL 4.1 backend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Voxy renders LoD terrain on macOS (GL 4.1 core) in Minecraft 1.20.1 without shader packs, verified by `scripts/autotest.sh smoke` with `requireRenderer=true`.

**Architecture:** A second render backend selected at runtime. LoD traversal, culling, node cleanup and draw-list generation run in Java on CPU mirrors of the node and section-metadata data; the GPU draws with a few `glDrawElementsInstanced` calls that pull quads, models and per-draw entries from texture buffer objects. Shared infrastructure (GL wrappers, upload stream, shader loader, post passes) gains GL 4.1 code paths; the GL 4.6 path is unchanged in behaviour.

**Tech Stack:** Java 17, LWJGL 3.3.1 (GL11–GL41 entry points only on this path), GLSL 4.10 core.

Design reference: `docs/superpowers/specs/2026-10-17-voxy-macos-gl41-mc1201-design.md` (sections "GL 4.1 backend" and "Platform constraints").

---

## Facts this plan relies on

- Node record: 16 bytes (`uvec4`): `x,y` = packed LoD position (`lod = x>>>28`), `z` low 24 bits = mesh (section) id with `NULL_MESH=(1<<24)-1`, `EMPTY_MESH=(1<<24)-2`, `w` low 24 bits = child pointer with `NULL_NODE=(1<<24)-1`, `EMPTY_QUEUE_ID=(1<<24)-2`; flags = `(z>>>24) | ((w>>>24)<<8)`: bit0 requested, bits 2–4 child count − 1.
- Section metadata: 32 bytes: `a = (posHi, posLo, aabb, quadStart)`, `b` = 16-bit quad counts: `x = translucent | doubleSided<<16`, `y = down | up<<16`, `z = north | south<<16`, `w = west | east<<16`. Quads are laid out from `quadStart` in that order.
- Quad: 8 bytes, little-endian `long`, read in GLSL as `uvec2` (`x` = low word), decoded by `quad_format.glsl`'s non-int64 path.
- `AsyncNodeManager.SyncResults` carries, per frame: geometry copies (16-byte headers `srcElem, dstElem, elemCount, _` + scratch data, element = 8 bytes), scatter writes (chunks of 5 `uvec4`: 4 locations then 4 values; location bit 31 set = section metadata buffer in `uvec4` units, clear = node buffer), cleaner visibility operations (bit 31 set = "set to current visibility id", clear = "set to −1"), top-level node add/remove.
- Traversal uniform semantics (`HierarchicalOcclusionTraverser.uploadUniform`): `minSSS = subDivisionSize² / (width·height)`, `frameId = NodeCleaner.visibilityId`, request soft limit `ceil((max(0, 4000 − meshTasks)/4000)² · 50)`, render distance `(sectionRenderDistance·16·32)²` on XZ.
- Cleaner selection (`sort_visibility.comp`): node allocated, has a non-empty mesh, `lod != 4`, not requested, visibility ≠ −1; 256 smallest visibility values; selected nodes get `visibility = visibilityId + 60`; their positions (`x,y`) go to `submitRemoveBatch`.
- Apple GL 4.1 measured costs (design doc): multi-draw sub-draws ~0.1 µs each, instanced TBO draw ~0.03 ms total, `glCopyBufferSubData` is a CPU path, `glBufferSubData` does not stall.

## File map

| Path | Responsibility |
|---|---|
| `client/core/gl/GlCompat.java` (new) | Single `GL41` flag + non-DSA helpers: bind texture to unit, texture parameters, framebuffer attach/clear, buffer create/clear |
| `client/core/gl/GlBuffer.java`, `GlTexture.java`, `GlFramebuffer.java`, `GlVertexArray.java`, `GlRenderBuffer.java`, `GlDebug.java` | Branch on `GlCompat.GL41` |
| `client/core/gl/GlTextureBuffer.java` (new) | `GL_TEXTURE_BUFFER` view over a `GlBuffer` with a format |
| `client/core/gl/shader/Shader.java`, `ShaderLoader.java`, `Glsl410Transform.java` (new) | Version selection; strip `layout(binding=)` / `layout(location=) uniform`; post-link binding and logical→real uniform location table |
| `client/core/rendering/util/UploadStream.java` | GL 4.1 mode: CPU staging + `glBufferSubData` on commit |
| `client/core/rendering/util/{DepthFramebuffer,LightMapHelper,SharedIndexBuffer}.java`, `rendering/post/FullscreenBlit.java`, `core/AbstractRenderPipeline.java`, `core/NormalRenderPipeline.java`, `core/SSAO.java`, `core/util/GPUTiming.java`, `rendering/util/PrintfDebugUtil.java` | GL 4.1 paths for the shared pipeline |
| `client/core/rendering/backend/gl41/Gl41NodeMirror.java` (new) | CPU node + section metadata mirrors, applies scatter writes |
| `client/core/rendering/backend/gl41/Gl41Traversal.java` (new) | Java port of the hierarchical traversal |
| `client/core/rendering/backend/gl41/Gl41Cleaner.java` (new) | Java port of the node cleaner |
| `client/core/rendering/backend/gl41/DrawEntryBuilder.java` (new) | Visible sections → bucketed opaque entries + sorted translucent entries |
| `client/core/rendering/backend/gl41/Gl41SectionRenderer.java`, `Gl41Viewport.java` (new) | Draw submission |
| `client/core/rendering/hierachical/AsyncNodeManager.java` | GL 4.1 branch of `tick` (no compute) |
| `client/core/VoxyRenderSystem.java`, `client/VoxyClient.java`, `client/core/gl/Capabilities.java` | Backend selection |
| `shaders/lod/gl41/{quads.vert,bindings.glsl}` (new), `shaders/lod/quad_util.glsl`, `shaders/lod/gl46/{bindings.glsl,quads.frag}` | Model/colour accessors shared by both backends |
| `shaders/chunkoutline/outline.vsh`, `shaders/post/ssao.frag` (new) | GL 4.1 variants |

---

## Phase 2 — GL 4.1 foundation

### Task 2.1: Capability-based backend choice

- [ ] `Capabilities`: add `public final boolean gl41Backend` = `cap.OpenGL41 && !(compute && indirectParameters)` or forced by `-Dvoxy.backend=gl41`; add `backend` enum `GL46 | GL41 | NONE` (`-Dvoxy.backend=auto|gl46|gl41`).
- [ ] `VoxyClient.initVoxyClient`: `RENDER_SUPPORTED = instanceAllowed && backend != NONE`; only call `SharedIndexBuffer.INSTANCE.id()` after GL wrappers support the backend (Task 2.2).
- [ ] `GlCompat.GL41 = Capabilities.INSTANCE.backend == GL41`.
- [ ] Verify: `scripts/autotest.sh smoke` still passes with `-Dvoxy.backend=gl46` forced off (renderer creation still gated until Task 3.x lands, via a temporary `Gl41SectionRenderer` absence check).

### Task 2.2: Non-DSA GL wrappers

- [ ] `GlBuffer`: GL 4.1 = `glGenBuffers` + `glBindBuffer(GL_COPY_WRITE_BUFFER)` + `glBufferData(size, data or NULL, GL_STATIC_DRAW)`; `zero/zeroRange/fill` via a reusable zeroed/filled CPU block and `glBufferSubData` in ≤ 16 MiB chunks; sparse flags rejected.
- [ ] `GlTexture`: GL 4.1 = `glGenTextures` + bind + `glTexStorage2D` (ARB_texture_storage present); `createView` unsupported (throws) — callers checked; `zero` via `glTexSubImage2D` from a zeroed buffer per level.
- [ ] `GlFramebuffer`: bind-to-edit `glFramebufferTexture2D`/`glFramebufferRenderbuffer`/`glDrawBuffers`/`glCheckFramebufferStatus`, restoring the previous `GL_FRAMEBUFFER` binding.
- [ ] `GlVertexArray`: `glGenVertexArrays`; attribute setup via bind + `glVertexAttribPointer`/`glVertexAttribIPointer`.
- [ ] `GlRenderBuffer`, `GlDebug` (`EXT_debug_label` or no-op), `GlFence` unchanged (core 3.2).
- [ ] New `GlTextureBuffer(GlBuffer, int internalFormat)`: `glGenTextures`, `glBindTexture(GL_TEXTURE_BUFFER)`, `glTexBuffer`.
- [ ] Verify: a dev-only GL 4.1 self-test in the autotest harness (`gl41Selftest` scenario field) creates each wrapper, round-trips 1 MiB through `GlBuffer` + `GlTextureBuffer` (`glGetBufferSubData`) and reports pass/fail; zero GL errors.

### Task 2.3: Upload stream

- [ ] `UploadStream` GL 4.1 mode: `nmemAlloc` staging arena of the same size; `upload()` returns CPU addresses; `commit()` issues one `glBufferSubData` per recorded range (bind `GL_COPY_WRITE_BUFFER`); `tick()` frees ranges immediately (data is copied by the driver at call time); `rawUploadAddress`/`getRawBufferId` throw in GL 4.1 mode.
- [ ] Verify via the self-test: 1000 random small uploads into a buffer match a CPU reference.

### Task 2.4: GLSL 4.10 shader path

- [ ] `ShaderLoader.parse(id)` emits `#version 410 core` when `GlCompat.GL41`, else `460 core`.
- [ ] `Glsl410Transform` (applied in `Shader.Builder.compileToProgram` when GL 4.1): regex-strip `layout(binding = N[, ...])` on `uniform sampler*/usampler*/isampler*` and on `uniform Name {` blocks (keeping `std140`), and `layout(location = N) uniform T name;`; record `samplerName→N`, `blockName→N`, `logicalLocation N→name`.
- [ ] After linking: `glUseProgram`, `glUniform1i(loc(sampler), N)`, `glUniformBlockBinding(prog, glGetUniformBlockIndex(prog, block), N)`; build `int[] logicalToReal`.
- [ ] `Shader.loc(int logical)` returns the real location (identity on GL 4.6); replace fixed-location `glUniform*` calls in shared code (`AbstractRenderPipeline`, `NormalRenderPipeline`, `SSAO`, `FullscreenBlit` users, bound renderer) with `shader.loc(n)`.
- [ ] Remove `gl_HelperInvocation` use under `#ifdef GL41` in fragment shaders that are shared.
- [ ] Verify: harness self-test compiles every shared post shader (`post/*`, `hiz` excluded) on GL 4.1.

### Task 2.5: Shared pipeline on GL 4.1

- [ ] `FullscreenBlit`, `DepthFramebuffer`, `LightMapHelper`, `SharedIndexBuffer`, `AbstractRenderPipeline.initDepthStencil/transformBlitDepth`, `NormalRenderPipeline.setup/finish`: replace DSA calls with `GlCompat` helpers.
- [ ] `Viewport`: create `HiZBuffer` lazily (GL 4.6 only).
- [ ] `SSAO`: GL 4.1 mode renders `post/ssao.frag` (port of `ssao.comp`: `gl_GlobalInvocationID.xy` → `ivec2(gl_FragCoord.xy)`, `imageStore` → `out vec4`) into an FBO with the output texture attached.
- [ ] `GPUTiming`: no `glMemoryBarrier` on GL 4.1; `PrintfDebugUtil`: no-op on GL 4.1.
- [ ] `ModelStore`/`ModelFactory`: model and colour buffers get `GlTextureBuffer` views (RGBA32UI, R32UI); atlas upload via bind + `glTexSubImage2D`, mips via `glGenerateMipmap`.

## Phase 3 — GL 4.1 renderer

### Task 3.1: CPU mirrors and node manager tick

- [ ] `Gl41NodeMirror`: `int[] nodes` (4 per node), `int[] sections` (8 per section), `apply(scatterBuffer, count)`.
- [ ] `AsyncNodeManager.tick`: when GL 4.1, geometry copies → `glBufferSubData(geometryBuffer, dst*8, …)` per header; scatter writes → `mirror.apply`; cleaner ops → `Gl41Cleaner.updateIds`.
- [ ] Verify: unit test (`src/test`, JUnit 5) for `Gl41NodeMirror.apply` with a hand-built scatter buffer (two node writes, one metadata split write).

### Task 3.2: CPU traversal

- [ ] `Gl41Traversal.traverse(viewport, mirror, cleaner, out IntArrayList visibleSections, out requests)`: breadth-first over top-level nodes; frustum test (`frustum.glsl` semantics, 5 planes), XZ render-distance test, 8-corner projection, screen-space area, descend/render/request decisions exactly as `traversal_dev.comp`; writes `lastRenderFrame`; marks requested in the mirror; request batch submitted with `AsyncNodeManager.submitRequestBatch`.
- [ ] Verify: unit tests with a synthetic 2-level tree (root lod 1 with 8 lod-0 children): far camera → renders root; near camera → renders children; child without mesh → request emitted once.

### Task 3.3: Cleaner

- [ ] `Gl41Cleaner`: `int[] visibility` (−1 initialised), `updateIds`, `tick()` (when geometry nearly full) → partial selection of 256 lowest by the criteria above → `submitRemoveBatch`.
- [ ] Verify: unit test on a synthetic mirror.

### Task 3.4: Draw entries

- [ ] `DrawEntryBuilder.build(visibleSections, mirror, cameraSection)`: per section the `cmdgen.comp` face mask, opaque ranges split into buckets {16, 128, 1024} (largest first, remainder to smaller), translucent entries bucket-sorted far→near over the same 1024-bucket distance as `cmdgen.comp`; output into a reusable direct `ByteBuffer`.
- [ ] Verify: unit test for one section with known counts and camera positions (face mask and ordering).

### Task 3.5: Terrain shaders

- [ ] `quad_util.glsl` and `quads.frag`: replace `modelData[i]` / `colourData[i]` with `getModel(i)` / `getModelColour(i)`; define them in `gl46/bindings.glsl` (SSBO) and `gl41/bindings.glsl` (TBO: 4 `texelFetch` of RGBA32UI per model).
- [ ] `gl41/quads.vert`: entry = `texelFetch(drawEntries, entryBase + gl_InstanceID)`; `local = gl_VertexID >> 2`; quads beyond the count write `gl_Position = vec4(-2, -2, -2, 1)`; otherwise the `quads3.vert` body with `positionBuffer[gl_BaseInstance]` → entry `.zw`.
- [ ] Verify: shaders compile and link on GL 4.1 in the self-test.

### Task 3.6: Section renderer and bounds

- [ ] `Gl41SectionRenderer` (+ `Gl41Viewport`): per frame `Gl41Traversal` → `DrawEntryBuilder` → upload entries to the ring buffer → opaque: one instanced draw per bucket; translucent: one instanced draw; `buildDrawCalls`/`renderTemporal` no-ops. Factory registered in `VoxyRenderSystem.getRenderBackendFactory()` when GL 4.1.
- [ ] `BoundRenderer`: GL 4.1 path with positions from an RG32I `GlTextureBuffer` and `uniform int baseInstance`.
- [ ] `VoxyRenderSystem` / `AbstractRenderPipeline.innerPrimaryWork`: GL 4.1 skips HiZ, GPU traversal and download stream; calls the GL 4.1 node manager tick and traversal.
- [ ] Verify: `smoke.json` → `requireRenderer: true`; `scripts/autotest.sh smoke` → PASS, `lodCoverage ≥ 0.05` at all viewpoints, `glErrors 0`; screenshots inspected; frame-time baseline recorded in the commit message.

### Task 3.7: Debug info and statistics

- [ ] F3 lines on GL 4.1: traversal time, visited nodes, visible sections, entries per bucket, quads, upload bytes per frame.
- [ ] Harness: include these in `report.json` via `voxyDebug`.
