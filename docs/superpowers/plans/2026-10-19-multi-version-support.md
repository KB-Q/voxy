# Voxy fork on Minecraft 1.21.x and 26.x — Approach Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan phase by phase. Each phase gets its own task-level plan before execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Builds of this fork (GL 4.1 backend, shader-pack fixes, server-side ingest, automatic import) for Minecraft versions newer than 1.20.1, up to 26.3, while the 1.20.1 build stays as it is today.

**Architecture:** One jar per Minecraft version. Each newer version is built from upstream Voxy's own branch for that version plus our changes applied as a topic patch series. The 1.20.1 branch (`opus-branch-1`) is not restructured.

**Tech Stack:** Fabric Loom (remap plugin up to 1.21.11, unobfuscated plugin from 26.1), Java 17 / 21 / 25 per version, Sodium 0.5 / 0.8 / 0.9, Iris 1.7 / 1.8 / 1.10 / 1.11.

---

## Summary

- The 1.20.1 condition can be met. With the recommended approach this work makes no commits to the 1.20.1 branch, so the 1.20.1 jar's source, dependencies and Java 17 bytecode stay the same. The full 1.20.1 harness is still re-run once as a gate.
- A single jar for every version is not possible:
  - Class-file versions differ: 61 for 1.20.1, 65 for 1.21.x, 69 for 26.x.
  - 26.x is unobfuscated while older versions use intermediary names.
  - Sodium renamed its packages (`me.jellysquid` to `net.caffeinemc`).
  - Mixin targets change between versions.
- Upstream already maintains one branch per version: 26.3, 26.2 (`dev`), 26.1.2, 1.21.11, 1.21.10, 1.21.7/8 and 1.21.5. Each upstream port touches 20–38 files. Our changes go on top of those ports instead of us maintaining the ports.
- Most of our work does not depend on the Minecraft version:
  - The GL 4.1 backend, its shaders and its unit tests are 14 new files and about 1,670 lines.
  - 32 of the 59 shared files we modified have content identical to `dev`, so those hunks apply to `dev` as written.
- **Lifetime limit:**
  - Voxy, upstream and ours, renders only with OpenGL.
  - MC 26.2 added an experimental Vulkan backend. On macOS it runs through MoltenVK.
  - 26.4 snapshots make Vulkan the default, and Mojang has announced OpenGL removal with no date.
  - On a Vulkan-only release neither upstream Voxy nor this fork renders. A Vulkan backend for Voxy is out of scope here.

## The 1.20.1 condition

The user's condition: build newer versions only if 1.20.1 keeps working without compromises. That condition is defined as the gate below. If the multi-version work causes any row to fail, revert that work; if it can't be fixed without touching 1.20.1, stop.

| Criterion | Check |
|---|---|
| Source unchanged by the multi-version work | No commit from this plan lands on `opus-branch-1`. Fixes found while porting are made on `opus-branch-1` first, as normal 1.20.1 work under the existing test routine, and only then carried forward. |
| Same build inputs and output | Same dependency versions: Sodium 0.5.13, Iris 1.7.6, Fabric API 0.92.12, LWJGL 3.3.1. Class files are still major version 61. |
| Same behaviour | 21 unit tests and the autotest scenarios smoke, smoke-shaders, stress, zoom, ingest, pregen, pregen-chunksmith and existing, plus the production jar on Java 17. |
| Same performance | Frame times within ±5% of the table in `docs/macos-gl41-mc1201.md`. |

## Version landscape

| MC | Java | Loom plugin | Sodium | Iris | Upstream Voxy branch | Renderer API state |
|---|---|---|---|---|---|---|
| 1.20.1 | 17 | `fabric-loom-remap` | 0.5.13 | 1.7.6 | none (ours: `opus-branch-1`) | RenderSystem, GlStateManager |
| 1.21.1 | 21 | `fabric-loom-remap` | 0.8.13 (backport of 0.8.12) | 1.8.14-beta.1 (for Sodium 0.8) | none; m3t4f1v3 commits 014c494a / d564d528 (April 2026) are a 1.21.1 port of an older `dev` | pre-1.21.5 API |
| 1.21.11 | 21 | `fabric-loom-remap` | 0.8.14 | 1.10.8 | `origin/12111` (cherry-picked, 98 `dev` commits behind) | GpuDevice, RenderPass, GpuSampler |
| 26.1.2 | 25 | `fabric-loom` (unobfuscated) | 0.9.2 | 1.11.4 | `origin/2612` (one commit on `dev` from 2026-07-21) | uber buffers for chunk geometry, `*Backend` interfaces |
| 26.2 | 25 | `fabric-loom` | 0.9.2 | 1.11.4 | `dev` | Vulkan backend experimental; Default means OpenGL |
| 26.3 | 25 | `fabric-loom` | 0.9.2 | 1.11.7 | `origin/263` (16 commits ahead of `dev`) | Renderpearl API, SDL3 instead of GLFW, shaders compiled with shaderc |
| 26.4 (snapshots) | 25 | `fabric-loom` | — | — | none | Default means Prefer Vulkan |

Facts behind the plan:

- **macOS still gets OpenGL 4.1 on 26.x.**
  - 26.2's OpenGL backend requests a GL 3.3 core forward-compatible context, which macOS answers with GL 4.1.
  - Upstream logs "unsupported" on that context because it needs compute shaders and indirect-count draws. The GL 4.1 backend covers exactly this case.
  - 26.3's vanilla shaders require `ARB_separate_shader_objects`, which is core in GL 4.1. This is an inference; it has not been tested on 26.3.
- **On 26.x, Voxy still uses raw GL.** Fabric's 26.2 notes say raw-GL mods must migrate to Blaze3D eventually. Voxy uses Blaze3D only at its edges (16 imports in about 10 files) and works only on the OpenGL backend. Upstream's 26.3 branch throws `BackendCreationException` when Vulkan is selected, so the game falls back to OpenGL.
- **Iris on 26.3 refuses to run under Vulkan.** Voxy with shader packs on 26.x therefore runs on OpenGL 4.1 on macOS, with the same shader-pack limits as 1.20.1.
- **Mojang's state caches.** 26.x caches more GL state than 1.20.1: per-unit texture bindings, blend per buffer, and the last program and last VAO in `GlCommandEncoder`. The GL 4.1 backend already routes binds through `GlStateManager`. It must also leave program and VAO state the way the cache expects.
- **License.** Upstream Voxy is All Rights Reserved: builds are for personal use, nothing is published.

## Approaches

| Approach | Effect on 1.20.1 | Ongoing cost | Assessment |
|---|---|---|---|
| A. Topic patch series on each upstream version branch | None; `opus-branch-1` is not changed | On each upstream update, rebase the series onto the branch tip. Expected conflicts: 11 files on 263, 13 on 2612, 8 on mc12110, 18 on 12111. | Recommended. Reuses upstream's ports. |
| B. One source tree with Stonecutter (conditional comments per version) | About 131 `src/main` files get version conditionals, about 33 of them gated as whole files. Needs proof that the generated 1.20.1 tree equals today's. | Upstream does not use a preprocessor, so every upstream commit is merged into the conditional regions by hand, for every version. | Worth considering only if upstream stops maintaining branches we need. Real Stonecutter projects spanning 1.20.1–26.x exist, but none has its own renderer. |
| C. One jar for all versions | — | — | Not feasible (see Summary). |

The rest of this plan assumes approach A.

## Patch series

`dev..opus-branch-1` is 194 files (+8416/−3174). 105 of those files come from merging the m3t4f1v3 1.20.1 port. Our 17 later commits are split by topic so that each patch applies on its own:

| Patch | Contents | Version-sensitive parts |
|---|---|---|
| P1 GL 4.1 backend (new files) | `backend/gl41/*`, `GlCompat`, `GlTextureBuffer`, `Glsl410Transform`, `PackShaderCompat`, `shaders/lod/gl41/*`, `src/test/**/gl41/*` | Location of the `GlStateManager` import (3 files); `MixinSamplerLimits` (Iris); `ModelFactory` |
| P2 GL 4.1 gates in shared code | About 24 files: Capabilities, GlBuffer/Texture/Framebuffer/RenderBuffer, Upload/DownloadStream, `AsyncNodeManager.tick41`, ModelStore, BoundRenderer, SSAO, DepthFramebuffer, `Shader.loc`, render pipelines, shaders with `#ifdef GL41` | Nearly none. Keep `dev`'s RenderProperties logic instead of the 1.20.1 hardcoded value. |
| P3 Shader-pack fixes | `quad_util.glsl` attribute initialisation, patched-shader handling | Iris version: pipeline mixins differ between 1.7, 1.10 and 1.11 |
| P4 Ingest | MixinChunkMap + PendingServerIngest, AutoWorldImport, IngestStats, Voxy instance without a renderer | `protoChunkToFullChunk` returns `Either` on 1.20.1 and `ChunkResult` from 1.20.5; 26.x needs checking. The IngestStats hooks depend on the Sodium version. |
| P5 Autotest harness | `src/autotest`, `scripts/autotest.sh` | About 550 lines call Minecraft APIs (world creation, commands, screenshots, input) |
| P6 Debug switches and docs | `voxy.debug.*` properties, `docs/` | None |

The 1.20.1 port itself stays on `opus-branch-1` only. That covers the m3t4f1v3 merge and our 1.20.1-specific fixes: JomlMem for JOML 1.10.5, Java 17 replacements, Sodium 0.5 hooks.

## Phases

### Phase 0: Baseline and series

- [ ] Tag the current 1.20.1 head (`v1201-baseline`). Record the harness results and frame times already in `docs/macos-gl41-mc1201.md` as the reference for the 1.20.1 gate.
- [ ] Build P1–P6 on a scratch branch, without rewriting `opus-branch-1` history. Partition `git diff <merge commit>..opus-branch-1` by topic.
- [ ] Completeness check: applying P1–P6 to the merge commit must reproduce the `opus-branch-1` tree, with `git diff` empty.

### Phase 1: 26.2 (`dev`), the closest base

- [ ] Create a branch from `dev`, apply P1–P3, and build with Java 25.
- [ ] Adapt the version-sensitive parts:
  - the `GlStateManager` package moves to `blaze3d.opengl`;
  - `_disableBlend()` becomes `_disableBlend(int)`;
  - re-sync program and VAO in Mojang's `GlCommandEncoder` cache after the LoD pass.
- [ ] Backend choice:
  - On a GL 4.1 context, `Capabilities` selects GL41.
  - When Vulkan is selected, fall back to OpenGL the way upstream's 263 branch does.
- [ ] Port P5. Gate: unit tests, smoke, smoke-shaders (Complementary with Voxy support on 26.2), stress.
- [ ] Port P4. Find the 26.2 method where a chunk becomes FULL. Gate: the ingest, pregen and existing scenarios, with Chunky for 26.2.
- [ ] Check that the GL 4.6 path behaves the same: the diff to `dev` on that path contains only equivalent calls (`glGen*` for `glCreate*`, `Shader.loc` returning the location unchanged). Note in the results that the GL 4.6 path can't run on this Mac, so it is checked by review only.

### Phase 2: 26.3 (`origin/263`)

- [ ] Rebase the 26.2 branch's patches onto `origin/263`, which is a linear continuation of `dev`.
- [ ] Adapt to 26.3:
  - the Renderpearl packages (`com.mojang.renderpearl.backend.opengl`);
  - the new `DefaultChunkRenderer` hook parameters (`RenderPass`, `OitStage`);
  - SDL3, which affects only the harness input code.
- [ ] Gate: same scenarios as Phase 1.
- [ ] Also test with the 26.4-style default (Prefer Vulkan) and MoltenVK present: the game must fall back to OpenGL, and Voxy must render.

### Phase 3: 1.21.11 (`origin/12111`)

- [ ] Apply P1–P5 to `origin/12111` and resolve conflicts in about 18 files.
- [ ] Build with Java 21 and the remap Loom plugin with Mojang mappings, against Sodium 0.8.14 and Iris 1.10.8.
- [ ] Gate: full scenario set, plus the user's Fabulously Optimized 1.21.11 instance as a production check.

### Phase 4: 1.21.1 (no upstream branch)

- [ ] Choose the base:
  - (a) down-port `opus/1.21.11` to the 1.21.1 Minecraft API, which keeps Sodium 0.8.x on both sides; or
  - (b) bring the m3t4f1v3 1.21.1 port (d564d528) forward to current `dev`.
  - Default: (a), because the Sodium 0.8 API is shared and only the Minecraft side changes.
- [ ] Gate: full scenario set, plus the user's BA+ 1.21 instance with Sodium 0.8.12 and Iris 1.8.14 as a production check.

### Phase 5 (optional): 26.1.2, 1.21.10, 1.21.7/8

- [ ] Only on request. Same procedure on `origin/2612`, `origin/mc12110` and `origin/mc_1217`.

### Phase 6: Maintenance

- [ ] When upstream updates a branch:
  - rebase that version's branch onto it;
  - run that version's gate;
  - record any conflicts in this plan's tables.
- [ ] 1.20.1 picks up upstream `dev` commits as it does today, as separate 1.20.1 work with the full 1.20.1 harness. A previous sync of 123 `dev` commits needed changes in 21 files.

## Per-version gate

| Check | 1.20.1 | 1.21.x | 26.x |
|---|---|---|---|
| Unit tests (21) | yes | yes | yes |
| smoke, stress, zoom | yes | yes | yes |
| smoke-shaders (Complementary Unbound with Voxy) | yes | yes | yes |
| ingest, pregen (Chunky), existing | yes | yes | yes |
| pregen-chunksmith | yes | only if Chunksmith is published for the version | only if Chunksmith is published for the version |
| Production jar on the version's Java | 17 | 21 | 25 |
| Frame time recorded (with and without shaders) | yes | yes | yes |

## Effort

Rough sizes, based on measured diff sizes rather than timings:

| Phase | Size | Basis |
|---|---|---|
| 0 | S | Splitting 17 commits by path |
| 1 (26.2) | M | P1–P3 apply as written to 32 of 59 shared files; harness port about 550 lines |
| 2 (26.3) | S–M | 16-commit difference from `dev`; Renderpearl renames |
| 3 (1.21.11) | M | 18 conflicting files; Sodium 0.8 API |
| 4 (1.21.1) | M–L | No upstream branch; Minecraft API down-port |
| 6 | S per upstream update per version | Rebase plus gate |

## Risks and open questions

- **OpenGL removal from Minecraft.** No date is announced. When it happens, both upstream Voxy and this fork stop rendering on the newest versions.
- **Vulkan default on macOS (26.4).** The OpenGL fallback must keep working. Upstream's 263 branch handles this; it is tested in Phase 2.
- **Mojang's GL state cache.** Raw GL calls that leave program, VAO or texture bindings different from Mojang's cache can corrupt vanilla rendering on 26.x. The smoke screenshots check for this.
- **GL 4.6 regressions can't be tested here.** The GL 4.6 path is checked by review only.
- **Shader packs per version.** Complementary's Voxy integration uses the shader define version (3 on `dev`). It must match on each version.
- **Branch policy.** Approach A needs one branch per version in this repo. The current working rule is "work only on `opus-branch-1`", so the new branches need approval. Proposed names: `opus-branch-1-26.3`, `opus-branch-1-26.2`, `opus-branch-1-1.21.11`, `opus-branch-1-1.21.1`.
- **Decisions needed before Phase 1:**
  1. Approach A or B.
  2. The target versions. Default: 26.3, 26.2, 1.21.11, 1.21.1.
  3. The branch names.
