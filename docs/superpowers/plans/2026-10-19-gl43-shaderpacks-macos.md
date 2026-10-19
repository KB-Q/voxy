# Shader packs that need more than OpenGL 4.1 on macOS — Approach Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan phase by phase. Each phase gets its own task-level plan before execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run Iris shader packs that use OpenGL 4.2+ features (Bliss, Photon, Photonics and similar) on macOS, which offers only OpenGL 4.1 core, preferably together with this Voxy fork.

**Architecture:**
- An Iris compatibility layer that rewrites shaders, shipped as a mixin mod. It covers what can be lowered to GL 4.1 for every pack at once.
- Per-pack patches for the few cases a generic rewrite can't express.
- A separate experiment with a real GL 4.6 implementation underneath Minecraft (Mesa Zink on LunarG's KosmicKrisp Vulkan driver) for what GL 4.1 can't do at all.

**Tech Stack:** Iris 1.7.6 (1.20.1) with glsl-transformer 2.x, Fabric mixins, Apple OpenGL 4.1 core; for the experiment, Mesa (Zink, KosmicKrisp) and patched GLFW.

---

## Summary

- **One fix doesn't cover all packs.** What blocks a pack on macOS falls into four groups (table below):
  - **Group 1:** a generic edit at the Iris level fixes it cheaply.
  - **Group 2:** needs a pack option change or one small per-pack edit.
  - **Group 3:** voxel colored lighting. It can be emulated generically, but only with substantial new host code in Iris.
  - **Group 4:** built on atomics and shared memory. These packs can't run on Apple's GL 4.1, edited or not.
- **The three packs named:**
  - **Bliss:** its compute use is limited to colored lighting (LPV), which is off by default. Its author reports that the default settings work on macOS without Distant Horizons. However, Bliss has no Voxy integration (no `voxy.json`), so this fork draws no LoDs while Bliss is active.
  - **Photon:** the only compute pass on by default is spherical-harmonics skylight. A pack option turns it off, or a fragment-shader rewrite of that pass keeps it. Photon supports Voxy.
  - **Photonics:** this is a closed-source Iris extension mod, not a shader pack. It uses image atomics in fragment shaders, SSBOs and `glMemoryBarrier`. It can't run on GL 4.1; the only route is a GL 4.3+ driver.
- **Driver route.** A real GL 4.6 on macOS exists today only as a patched stack:
  - It needs a Mesa fork, a GLFW fork, a function-lookup interposer and `MESA_GL_VERSION_OVERRIDE=4.6`.
  - One user reports Bliss loading through it.
  - It is about 4× slower than Apple's GL in one user report; there is no controlled measurement.
  - Upstream Zink on KosmicKrisp reaches only GL 2.1, because KosmicKrisp lacks geometry shaders and transform feedback. Merge requests for both are open.
- **Vulkan in MC 26.2+.** The Vulkan backend (MoltenVK on macOS) with the Vitrail mod runs unmodified packs, compute included, on Apple Silicon. Iris and Voxy don't run on Vulkan, so this gives shader packs without LoDs, and only on 26.2+.

## Groups of blockers

| Group | What fails on Apple GL 4.1 | Packs | Where it can be fixed | Feasibility |
|---|---|---|---|---|
| 1. Version line and 4.2/4.3 syntax | `#version 430`/`450` in raster stages; `layout(depth_greater)`, arrays of arrays, `{}` initializers, `GL_ARB_gpu_shader5` under `#version 330` | Kappa, Nostalgia, SEUS PTGI, Solas shadow programs | Iris transformer, generic | Likely; not yet compiled on Apple's compiler |
| 2. Compute that is on by default | Photon SH skylight: a 256-thread shared-memory reduction writing 10 texels | Photon | Pack option, or a per-pack fragment rewrite | Yes |
| 3. Voxel colored lighting | Vertex-stage `imageStore` voxelization in the shadow pass, plus compute floodfill on ping-ponged 3D images | Photon, Bliss, BSL, Complementary, Solas, Mellow (all off by default or gated on macOS) | Iris host emulation, generic across the six; or a per-pack rewrite | Possible in principle; large; unverified |
| 4. Atomics and shared-memory designs | Integer image atomics, SSBO atomics, shared-memory light lists, fragment-stage atomics | Rethinking Voxels, Shrimple, Glimmer, Visual Vibrance, Photonics | Not fixable on GL 4.1 | No; needs a GL 4.3+ driver |

How Iris (1.7.6 to 1.11) behaves today; these facts determine the groups:

- **Compute programs.** An enabled compute program on a system without compute makes `ProgramBuilder` throw, and the pack fails to load. Programs disabled through `program.<dim>/<name>.enabled` are skipped.
- **Required features.** `iris.features.required` listing COMPUTE_SHADERS, SSBO or CUSTOM_IMAGES means the pack is refused with a feature-missing screen.
- **Version lines.** Iris raises old `#version` lines but never lowers them, so `#version 430` reaches Apple's compiler and fails there.
- **macOS macro.** `MC_OS_MAC` comes from the operating system, not the driver. Complementary therefore keeps its colored lighting disabled even on a GL 4.6 driver.
- **Voxy integration.** This fork draws LoDs with a pack active only if the pack ships `voxy.json` (`IrisShaderPatch.makePatch`; `RenderPipelineFactory` returns no pipeline otherwise). Voxy support per pack: Photon, BSL, Complementary, Solas, Mellow and Glimmer have it; Bliss, Kappa, Nostalgia, Super Duper Vanilla and Rethinking Voxels do not.

## Routes

| Route | Packs covered | Effort | Main risks |
|---|---|---|---|
| R1. Iris compatibility layer: version and syntax lowering | Group 1 | S–M | Apple compiler strictness; 16-sampler limit per stage |
| R2. Pack options and small per-pack patches | Group 2; Solas properties gate | S | Patches must be redone when packs update; license terms |
| R3. Colored-lighting emulation in the layer | Group 3 | L | Performance of transform feedback plus point scatter; correctness against the GL 4.3 result; Complementary needs a pack edit to lift its macOS gate |
| R4. Zink on KosmicKrisp, patched (experiment) | All groups in principle, including Photonics | M | About 4× slower (one report); no geometry shaders or transform feedback until the Mesa merge requests land; window-system code not upstream; third-party native code |
| R5. MC 26.2+ Vulkan with Vitrail | Unmodified packs | S (26.x only) | No Iris and no Voxy on Vulkan |

## Recommended approach

R1 and R2 first, after measuring which packs actually fail (Phase 0). R3 only if colored lighting is wanted after that. R4 runs as a separate experiment, because it is the only route for group 4 and Photonics. R5 is information for the multi-version plan, not work here.

### Phase 0: Measure on the 1.20.1 test instance

- [ ] Add a pack-matrix scenario to the autotest harness, generalising smoke-shaders.
  - Inputs: a pack zip and an option file (`shaderpacks/<pack>.txt`).
  - Outputs: whether the pack loads, Iris compile errors from the log, `glErrors`, screenshots at the standard viewpoints, frame time, and LoD coverage when the pack has Voxy support.
- [ ] Run these packs with default settings: Bliss, Photon v1.3b and v1.4, BSL, Mellow, Solas, Kappa, Nostalgia, Super Duper Vanilla, SEUS PTGI. Also run Photon with SH Skylight off.
- [ ] Record the results in a table in `docs/`. The static analysis behind the groups was regex-based and never compiled on Apple's driver; this run replaces it with measured results.

### Phase 1: Compatibility layer, version and syntax lowering (R1)

Location: a Gradle subproject of this repo that produces its own jar, so it works with or without Voxy and reuses the harness. Alternative: the Voxy jar itself, behind a config switch. This is decision 1 below.

- [ ] Mixin into Iris's `TransformPatcher` and add AST passes (glsl-transformer), applied only when the context is below GL 4.3:
  - lower `#version 420`–`460` in raster stages to 410;
  - drop `layout(depth_greater/less/unchanged)`;
  - flatten arrays of arrays;
  - convert `{}` initializers to constructors;
  - move `layout(binding = N)` to post-link `glUniform1i`, reusing `Glsl410Transform` from the Voxy fork;
  - emulate `packHalf2x16`/`unpackHalf2x16` and `textureQueryLevels`;
  - raise `#version 330` with `GL_ARB_gpu_shader5` to 400.
- [ ] Replace Iris's "This PC does not support compute shaders" exception with a message that names the compute program and the pack option that disables it.
- [ ] Check the 16-sampler limit per stage (reported for Bliss with DH). The fork's `MixinSamplerLimits` covers Voxy's own samplers; packs above 16 need a per-pack option change.
- [ ] Gate:
  - Group 1 packs load with zero compile errors and `glErrors 0`, and their screenshots are reviewed.
  - Complementary Unbound, BSL and Photon screenshots are unchanged against Phase 0, which shows the layer is inert for packs that already work.

### Phase 2: Per-pack patches (R2)

- [ ] Photon: rewrite `d4a_generate_sky_sh.csh` as a fragment pass over the 1×10 output region, each fragment looping over the 256 samples. Ship it as a patch plus a script that applies it to a local copy of the zip; nothing is redistributed. Photon's license allows personal modification.
- [ ] Solas: add a Complementary-style `MC_OS_MAC` gate in `shaders.properties`, so `shadowcomp` and the `image.` lines are disabled on macOS.
- [ ] Gate: the same pack-matrix checks. Photon with SH Skylight on matches Photon with it off except for sky lighting, and the user reviews the screenshots.

### Phase 3 (decision gated): Colored-lighting emulation (R3)

The six packs share one pattern:

- **Voxelization:** a vertex-stage voxel write, guarded by `gl_VertexID % 4 == 0`, with no atomics, so the last writer wins.
- **Floodfill:** each invocation writes only `ivec3(gl_GlobalInvocationID)`, ping-ponging two volumes.

The layer would emulate it with features GL 4.1 has:

- [ ] Custom 3D images become 3D textures attached as layered render targets; a geometry shader sets `gl_Layer`.
- [ ] Compute programs that write only their own texel become per-slice fullscreen draws:
  - `gl_GlobalInvocationID = ivec3(gl_FragCoord.xy, layer)`;
  - an `imageStore` at that coordinate becomes a fragment output;
  - `imageLoad` becomes `texelFetch` from the other ping-pong volume.
- [ ] Shared memory used only as a cache (Bliss `shadowcomp`, Complementary floodfill) becomes direct fetches.
- [ ] Vertex-stage `imageStore`:
  - transform feedback captures (voxel position, value) in the shadow pass;
  - a point draw then scatters those into the 3D target.
- [ ] Order: BSL (simplest), then Photon, Bliss, Mellow. Complementary only with a pack edit that lifts its `MC_OS_MAC` gate.
- [ ] Gate:
  - Colored light appears in the test scenes, and the user reviews it, because no GL 4.3 reference image can be rendered on this Mac.
  - The frame-time cost is recorded.

### Phase 4 (experiment, separate test instance): GL 4.6 through Zink on KosmicKrisp (R4)

- [ ] Reproduce the published setup on a copy of the 1.20.1 test instance: Mesa fork, GLFW fork via `-Dorg.lwjgl.glfw.libname`, the interposer via `DYLD_INSERT_LIBRARIES`, and `MESA_GL_VERSION_OVERRIDE=4.6`.
  - The user's M4 Pro on macOS 26 meets KosmicKrisp's requirements (Metal 4, M1 or later).
  - The setup builds third-party native code, so it runs only in the test instance and needs approval first (decision 3).
- [ ] Measure frame time with Complementary Unbound on Apple GL against the same on Zink.
- [ ] Test Photonics, Rethinking Voxels, Bliss with LPV on and Photon with colored lights.
- [ ] Test this Voxy fork's GL 4.1 backend on Zink (`-Dvoxy.backend=gl41`). Upstream's GL 4.6 path needs indirect-count draws, which this stack does not expose.
- [ ] Re-check when Mesa MRs 44786 (geometry shaders), 44928 (transform feedback) and 33060 (Metal window-system support) land. With them, an override may no longer be needed.
- [ ] 26.3 replaced GLFW with SDL3, so the GLFW fork applies only up to 1.21.11.

## Verification

- **References.** No GL 4.3+ reference images can be produced on this Mac, except through R4. The first passing run of each pack is reviewed by the user and becomes that pack's baseline. Later runs are compared with the harness's existing image metrics.
- **Regression set.** Every phase re-runs Complementary Unbound + Euphoria Patches (the user's daily pack), BSL and Photon, together with this Voxy fork.
- **Separation from the multi-version plan.** The layer targets Iris 1.7.6 first. Ports to Iris 1.8, 1.10 and 1.11 are needed only if the multi-version plan goes ahead.

## Licenses

| Pack or mod | Private modification | Effect on this plan |
|---|---|---|
| Photon | Allowed for personal use | Phase 2 patch allowed locally |
| Solas, Kappa, Nostalgia | Private modifications allowed (§2) | Patches allowed locally |
| Mellow, Glimmer | MIT | No restriction |
| Bliss | "All rights reserved", no grant | Only generic Iris-side changes; no pack edits |
| Complementary | No explicit grant for private edits | Prefer generic changes; a gate edit needs the user's judgement |
| Photonics | Closed source, all rights reserved | No modification; only R4 can help |
| Iris | LGPL-3.0 | Mixins and local builds allowed |

Nothing in this plan is published. Patches are applied to local copies only.

## Risks and open questions

- **Untested assumption:** Apple's GLSL compiler may reject pack code that NVIDIA accepts, independent of the version line. Phase 0 will show how often this happens.
- **R3 performance is unknown.** Transform-feedback capture plus point scatter adds a pass per frame over the shadow geometry.
- **R4 depends on unmerged Mesa work.** It also depends on a fork whose author calls it "not upstreamable", and it may stay slower than Apple's GL.
- **Bliss has no LoDs with this fork** until Bliss ships a `voxy.json`, or one is written for it. That would be per-pack work and is not covered here.
- **Decisions needed:**
  1. Where the layer lives: a subproject jar in this repo, or inside the Voxy jar.
  2. Whether Phase 3 is wanted, decided after the Phase 0–2 results.
  3. Whether to run the Phase 4 experiment, which builds third-party native code.
  4. Which packs have priority.
