# Phase 0–1: Minecraft 1.20.1 port and automated test harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `opus-branch-1` builds a Fabric 1.20.1 Voxy jar, and `scripts/autotest.sh` boots it on macOS into a fixed world, collects screenshots and a JSON report, and exits — with Voxy storing LOD data even though no GL 4.1 renderer exists yet.

**Architecture:** Merge m3t4f1v3/voxy `mc_1201` (a 1.20.1 port of Voxy 0.2.14) into `opus-branch-1` (upstream `dev`, Voxy 0.2.20), resolving conflicts as "port's 1.20.1 API + upstream's newer logic". A dev-only Fabric mod in a separate `autotest` source set drives the client through scenarios and writes a report; it is never part of the release jar. Voxy's instance (storage, ingest) is decoupled from render-backend support so ingest works before the GL 4.1 backend lands.

**Tech Stack:** Gradle 9.6, Fabric Loom (remap variant), Mojang mappings, Java 17 bytecode, Fabric API 0.92.12+1.20.1, Sodium 0.5.13, Iris 1.7.6, Chunky 1.3.146, LWJGL 3.3.1, bash + jq.

Design reference: `docs/superpowers/specs/2026-10-17-voxy-macos-gl41-mc1201-design.md`.

---

## Facts this plan relies on

- Reference port: `https://github.com/m3t4f1v3/voxy`, branch `mc_1201`, head `69b19fee`. Merge base with `dev`: `41dd201d`.
- A trial merge (done in a scratch clone) produced 44 conflicted paths: 38 content conflicts (about 85 hunks), 5 deleted-by-port, 1 deleted-by-upstream.
- Deleted by the port (26.x-only APIs): `client/DebugEntries.java`, `client/VoxyDebugScreenEntry.java`, `client/config/SodiumConfigBuilder.java`, `client/config/VoxyConfigMenu.java`, `client/mixin/sodium/MixinRenderRegionManager.java`.
- Deleted upstream (replaced by `client/core/rendering/bounding/*`): `client/core/rendering/ChunkBoundRenderer.java`.
- Added upstream since the merge base, so never conflicting but needing 1.20.1 adaptation: `rendering/bounding/*` (6 files), `building/OccupancySet2.java`, `core/IVoxyRenderSystemHolder.java`, `core/RenderProperties.java`, `mixin/minecraft/MixinLevelExtractor.java`, `mixin/sodium/MixinVisibleChunkCollector.java`, `mixin/sodium/MixinFallbackVisibleChunkCollector.java`, `commonImpl/DontCreateInstance.java`, `shaders/util/depthutils.glsl`, `src/jmh/**`.
- Added by the port: `config/VoxyConfigScreenPages.java`, `core/model/ARGB.java`, `core/util/ExpansionUtil.java` (+ Java 21 variant in `src/java21`), `core/util/StairBlockCooked.java`, `mixin/ClientVoxyMixinPlugin.java`, `mixin/iris/MixinPackRenderTargetDirectives.java`, `mixin/minecraft/{MixinDebugScreenOverlay,MixinGameRenderer,MixinStairBlock}.java`, `mixin/sodium/{AccessorSodiumOptionsGUI,MixinCloudRenderer,MixinSodiumOptionsGUI,MixinSodiumWorldRendererVS,MixinSodiumWorldRendererVSLegacy}.java`, `commonImpl/mixin/distanthorizons/MixinSharedApi.java`.
- `VoxyClient.initVoxyClient()` only installs the instance factory when compute + indirect-count draws exist, so on macOS no ingest or storage happens today. `SharedIndexBuffer.INSTANCE` uses DSA and must not be touched on GL 4.1.
- Default storage is RocksDB + zstd. macOS needs `librocksdbjni-osx-*.jnilib` and LWJGL `lwjgl-zstd`/`lwjgl-lmdb` macOS natives.

## File map

| Path | Responsibility |
|---|---|
| `build.gradle`, `gradle.properties` | 1.20.1 toolchain, dependencies, natives, `autotest` source set and run |
| `src/main/java/...` (merge) | 1.20.1 Voxy |
| `src/main/java/me/cortex/voxy/client/VoxyClient.java` | Separate "instance supported" from "rendering supported" |
| `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java` | Skip renderer creation when rendering is unsupported |
| `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java` | `isIdle()` for the harness |
| `src/autotest/java/me/cortex/voxy/autotest/VoxyAutotest.java` | Harness entrypoint |
| `src/autotest/java/me/cortex/voxy/autotest/Scenario.java` | Scenario JSON model |
| `src/autotest/java/me/cortex/voxy/autotest/AutotestRunner.java` | State machine: open world, setup, seed, viewpoints, report, quit |
| `src/autotest/java/me/cortex/voxy/autotest/FrameStats.java` | Frame times and GL error counts |
| `src/autotest/java/me/cortex/voxy/autotest/ImageMetrics.java` | LOD coverage and baseline difference |
| `src/autotest/java/me/cortex/voxy/autotest/GlInfo.java` | GL capability dump |
| `src/autotest/java/me/cortex/voxy/autotest/Report.java` | Report model + JSON writer |
| `src/autotest/resources/fabric.mod.json` | Harness mod metadata |
| `src/autotest/resources/autotest/scenarios/{seed,smoke}.json` | Scenarios |
| `scripts/autotest.sh`, `scripts/autotest-options.txt` | Runner script, deterministic game options |

---

## Phase 0 — Port

### Task 0.1: Merge the reference port

**Files:** whole tree.

- [ ] **Step 1: Fetch the port into the repo**

```bash
cd /Users/kbmsvj/Desktop/experiments/voxy-main
git remote add m3t4f1v3 https://github.com/m3t4f1v3/voxy.git
git fetch m3t4f1v3 mc_1201
git status --short --branch   # expect: ## opus-branch-1...origin/opus-branch-1, ?? bin/
```

- [ ] **Step 2: Start the merge**

```bash
git merge --no-ff --no-commit m3t4f1v3/mc_1201
git diff --name-only --diff-filter=U | wc -l   # expect 44
```

- [ ] **Step 3: Resolve the delete/modify conflicts**

```bash
P=src/main/java/me/cortex/voxy/client
git rm -q $P/DebugEntries.java $P/VoxyDebugScreenEntry.java $P/config/SodiumConfigBuilder.java $P/config/VoxyConfigMenu.java $P/mixin/sodium/MixinRenderRegionManager.java $P/core/rendering/ChunkBoundRenderer.java
```

Then carry upstream's post-merge-base changes from those deleted files into their 1.20.1 replacements:
- `git log -p 41dd201d..dev -- $P/config/VoxyConfigMenu.java` → add the new options (fog mode from `67439627`) to `config/VoxyConfigScreenPages.java`, using the same option builder pattern the file already uses.
- `git log -p 41dd201d..dev -- $P/DebugEntries.java $P/VoxyDebugScreenEntry.java` → ensure every debug line upstream emits is emitted by `mixin/minecraft/MixinDebugScreenOverlay.java`.
- `ChunkBoundRenderer`: the port moved "apply pending adds" before the early return. Check `rendering/bounding/StreamedBoundStore.java` / `BoundRenderer.render` for the same ordering; if the store is drained after the `count == 0` check, move the drain before it.

- [ ] **Step 4: Resolve content conflicts, file by file**

Rule for every hunk: keep the port's side wherever it is a Minecraft/Sodium/Iris API difference, then re-apply upstream's behavioural change from `git log -p 41dd201d..dev -- <file>` on top. Never drop an upstream behavioural change silently; if it cannot be expressed on 1.20.1, leave a `//TODO(1.20.1):` comment with the commit hash and list it in the commit message.

Special cases:
- `build.gradle`, `gradle.properties`: take the port's side now (`git checkout --theirs build.gradle gradle.properties`); Task 0.2 replaces them.
- `src/main/resources/client.voxy.mixins.json`: union of both sides, then remove 26.x-only entries (`minecraft.MixinDebugScreenEntryList`, `minecraft.MixinLevelExtractor`, `sodium.MixinVisibleChunkCollector`, `sodium.MixinFallbackVisibleChunkCollector`, `sodium.MixinRenderRegionManager`) and keep the port's entries. Use upstream's renamed paths (`minecraft.session.*`, `minecraft.util.*`) for files that exist under those paths.
- `fabric.mod.json`, `voxy.accesswidener`: port's side, plus any access widener entries upstream added that the merged code still needs (resolved when compiling in Task 0.3).
- `shaders/post/blit_texture_depth_cutout.frag`: upstream's fog-mode version, with the port's change re-applied (see `git diff 41dd201d m3t4f1v3/mc_1201 -- <file>`).

Check progress:

```bash
git diff --name-only --diff-filter=U        # repeat until empty
grep -rln '^<<<<<<<\|^>>>>>>>' src build.gradle gradle.properties   # expect no output
```

- [ ] **Step 5: Record the merge (compile fixes follow in 0.3)**

```bash
git add -A src build.gradle gradle.properties
git commit -m "merge m3t4f1v3/mc_1201 (1.20.1 port) into dev-based branch

Conflict policy: port's 1.20.1 API, upstream's newer logic re-applied.
Build files are finalised in the next commit."
```

### Task 0.2: Build configuration for 1.20.1

**Files:** Modify `gradle.properties`, `build.gradle`.

- [ ] **Step 1: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.daemon = false

minecraft_version=1.20.1
loader_version=0.19.3
loom_version=1.16-SNAPSHOT
fabric_api_version=0.92.12+1.20.1

sodium_version=mc1.20.1-0.5.13-fabric
iris_version=1.7.6+1.20.1
modmenu_version=7.2.2
chunky_version=1.3.146

mod_version = 0.2.20-beta-mc1201-gl41
maven_group = me.cortex
archives_base_name = voxy
```

- [ ] **Step 2: Rewrite the dependency/plugin blocks of `build.gradle`**

Starting from the port's `build.gradle` (now in the tree), make exactly these changes:

```groovy
plugins {
    id 'net.fabricmc.fabric-loom-remap' version "${loom_version}"
}
```
(Remove `maven-publish` and the `publishing {}` block, and the `me.champeau.jmh` plugin; delete `src/jmh` with `git rm -r src/jmh`.)

Dependencies block (replaces the port's mod dependency list; DH, sodium-extra, indium, lithium, nvidium, vivecraft, flashback, DevAuth are dropped from runtime; keep only the compile-only entries that merged sources still import — re-add any of `vivecraft`, `flashback`, `DistantHorizons`, `nvidium` as `modCompileOnly` only if Task 0.3 shows a compile error for them):

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    modImplementation "maven.modrinth:sodium:${project.sodium_version}"
    modImplementation "maven.modrinth:iris:${project.iris_version}"
    modRuntimeOnly "io.github.douira:glsl-transformer:2.0.1"
    modRuntimeOnly "org.anarres:jcpp:1.4.14"
    modImplementation "maven.modrinth:modmenu:${project.modmenu_version}"
    modImplementation "maven.modrinth:chunky:${project.chunky_version}"
}
```

LWJGL / natives block (replaces the port's):

```groovy
project.ext.lwjglVersion = "3.3.1"
dependencies {
    implementation platform("org.lwjgl:lwjgl-bom:$lwjglVersion")
    implementation "org.lwjgl:lwjgl"
    include(implementation "org.lwjgl:lwjgl-lmdb")
    include(implementation "org.lwjgl:lwjgl-zstd")
    for (String natives : ["natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64"]) {
        include(runtimeOnly "org.lwjgl:lwjgl-lmdb:$lwjglVersion:$natives")
        include(runtimeOnly "org.lwjgl:lwjgl-zstd:$lwjglVersion:$natives")
    }
    include(implementation 'redis.clients:jedis:5.1.0')
    include(implementation('org.rocksdb:rocksdbjni:10.2.1'))
    include(implementation 'org.apache.commons:commons-pool2:2.12.0')
    include(implementation 'org.lz4:lz4-java:1.8.0')
    include(implementation('org.tukaani:xz:1.10'))
}
```

RocksDB repack: keep macOS libraries, still drop the rest.

```groovy
    exclude {
        def file = it.name
        if (file.endsWith(".jnilib")) {
            return !(file.contains("osx-arm64") || file.contains("osx-x86_64"))
        }
        if (!file.endsWith(".so")) {
            return false
        }
        return ["osx", "linux32", "musl", "s390x", "riscv64", "ppc64le", "aarch64"].any(file::contains)
    }
```

Java: keep the port's `targetJavaVersion = 17` and its `java21` multi-release source set.

- [ ] **Step 3: Resolve dependencies**

Run: `./gradlew --console=plain dependencies --configuration modCompileClasspath > /tmp/voxy-deps.txt 2>&1; tail -5 /tmp/voxy-deps.txt`
Expected: `BUILD SUCCESSFUL`. If the plugin id fails to resolve, fall back to `id 'fabric-loom' version "1.13-SNAPSHOT"` (the port's working combination) and note it in the commit.

- [ ] **Step 4: Commit**

```bash
git add gradle.properties build.gradle && git rm -r -q --ignore-unmatch src/jmh
git commit -m "build: 1.20.1 toolchain, macOS natives for lmdb/zstd/rocksdb"
```

### Task 0.3: Compile fixes

**Files:** whatever `compileJava` reports; expected hot spots are the upstream-added files listed under "Facts".

- [ ] **Step 1: Compile and list errors**

Run: `./gradlew --console=plain compileJava > /tmp/voxy-compile.txt 2>&1; grep -E '^.*\.java:[0-9]+: error' /tmp/voxy-compile.txt | sed 's|.*/voxy/||' | sort | uniq | head -50; grep -c ': error' /tmp/voxy-compile.txt`

- [ ] **Step 2: Fix by category, re-run after each category**

| Error source | Fix |
|---|---|
| 26.x class names (`Identifier`, `ARGB`, `DeltaTracker`, `ChunkSectionLayer`, `LevelExtractor`, `CameraRenderState`, `DebugScreenEntries`) | Use the port's equivalents (`ResourceLocation`, `me.cortex.voxy.client.core.model.ARGB`, partial tick float, `RenderType`, `LevelRenderer`, `Camera`, `MixinDebugScreenOverlay`) — grep the port's code for the same call |
| Sodium 0.9 classes in `bounding/*` and the two `VisibleChunkCollector` mixins | Delete the two mixins; feed `StreamedBoundStore.put(SectionPos.asLong(x,y,z))` / `reset()` from the port's `MixinSodiumWorldRendererVS`/`VSLegacy` at the point where it iterates Sodium 0.5's visible render lists |
| `RenderProperties` (uses `DepthStencilState`, `RenderSystem.getDevice()`) | 1.20.1: `isZero2One=false`, `isReverseZ=false` constants |
| `MixinLevelExtractor` | Delete; ensure the port's `MixinLevelRenderer` calls `voxy$setWorld` from `setLevel` and shutdown+create from `allChanged` |
| Java 21 APIs (`Math.clamp`, `getFirst/getLast/removeFirst/removeLast`) | Use `ExpansionUtil` helpers from the port, or `Math.max(lo, Math.min(hi, v))` / index-based access |
| Missing access wideners | Copy the matching line from the port's `voxy.accesswidener` with 1.20.1 names |

- [ ] **Step 3: Full build**

Run: `./gradlew --console=plain build > /tmp/voxy-build.txt 2>&1; tail -5 /tmp/voxy-build.txt`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify the jar carries macOS natives**

Run: `J=$(ls build/libs/voxy-*.jar | grep -v sources | head -1); unzip -l "$J" | grep -E 'META-INF/jars/.*(lmdb|zstd|rocksdb).*\.jar'; unzip -p "$J" $(unzip -l "$J" | grep -oE 'META-INF/jars/rocksdbjni[^ ]*\.jar') > /tmp/rocks.jar; unzip -l /tmp/rocks.jar | grep -E 'osx'`
Expected: lmdb/zstd natives jars for `natives-macos` and `natives-macos-arm64` listed; `librocksdbjni-osx-arm64.jnilib` and `librocksdbjni-osx-x86_64.jnilib` present.

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "port: adapt post-merge-base upstream code to 1.20.1"
git push
```

---

## Phase 1 — Harness and boot

### Task 1.1: Decouple Voxy's instance from render support

**Files:** Modify `src/main/java/me/cortex/voxy/client/VoxyClient.java`, `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java`.

- [ ] **Step 1: `VoxyClient` — track rendering support separately**

Replace the body of `initVoxyClient()` from `boolean systemSupported = ...` to the end of the method with:

```java
        boolean renderSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!renderSupported) {
            Logger.error("Voxy rendering is unsupported on this system (no GL 4.6 compute + indirect count), LoD data will still be ingested and stored");
        }
        RENDER_SUPPORTED = renderSupported;

        boolean instanceSupported = true;
        if (System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                instanceSupported = false;
            }
        }

        if (instanceSupported) {
            if (renderSupported) {
                SharedIndexBuffer.INSTANCE.id();
                if (!Capabilities.INSTANCE.subgroup) {
                    Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
                }
            }
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        }
    }

    private static boolean RENDER_SUPPORTED = false;
    public static boolean isRenderSupported() {
        return RENDER_SUPPORTED;
    }
```

- [ ] **Step 2: `MixinLevelRenderer.voxy$createRenderer` — gate on support**

Insert after the `if (!VoxyConfig.CONFIG.enabled)` block:

```java
        if (!VoxyClient.isRenderSupported()) {
            Logger.info("Not creating renderer, no supported render backend");
            return;
        }
```

(Add `import me.cortex.voxy.client.VoxyClient;`.)

- [ ] **Step 3: Compile and commit**

Run: `./gradlew --console=plain compileJava > /tmp/voxy-compile.txt 2>&1; tail -3 /tmp/voxy-compile.txt` → `BUILD SUCCESSFUL`.

```bash
git add -A src && git commit -m "client: create voxy instance without a render backend so ingest/storage work"
```

### Task 1.2: Harness hooks in Voxy

**Files:** Modify `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`, `src/main/java/me/cortex/voxy/client/VoxyClient.java`, `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java`.

- [ ] **Step 1: `VoxyRenderSystem.isIdle()`**

```java
    //Used by the dev autotest harness: true when no meshing, model baking or node work is pending
    public boolean isIdle() {
        return this.renderGen.getTaskCount() == 0 && this.modelService.areQueuesEmpty() && !this.nodeManager.hasWork();
    }
```

- [ ] **Step 2: Render suppression flag in `VoxyClient`**

```java
    //Set by the dev autotest harness to capture reference frames without LoDs
    public static volatile boolean AUTOTEST_SUPPRESS_RENDER = false;
```

- [ ] **Step 3: Honour it in the port's `MixinDefaultChunkRenderer` render hook**

At the start of the method that calls `renderer.renderOpaque(...)`:

```java
        if (VoxyClient.AUTOTEST_SUPPRESS_RENDER) return;
```

- [ ] **Step 4: Compile and commit**

Run: `./gradlew --console=plain compileJava > /tmp/voxy-compile.txt 2>&1; tail -3 /tmp/voxy-compile.txt` → `BUILD SUCCESSFUL`.

```bash
git add -A src && git commit -m "client: idle query and render suppression hooks for the autotest harness"
```

### Task 1.3: Harness source set and run configuration

**Files:** Modify `build.gradle`, `.gitignore`. Create `src/autotest/resources/fabric.mod.json`.

- [ ] **Step 1: Source set, mods, run (append to `build.gradle`)**

```groovy
sourceSets {
    autotest {
        compileClasspath += sourceSets.main.compileClasspath + sourceSets.main.output
        runtimeClasspath += sourceSets.main.runtimeClasspath + sourceSets.main.output
    }
}

loom {
    mods {
        voxy { sourceSet sourceSets.main }
        voxy_autotest { sourceSet sourceSets.autotest }
    }
    runs {
        autotest {
            client()
            name = "Voxy Autotest"
            source sourceSets.autotest
            runDir "run-autotest"
            programArgs "--width", "1280", "--height", "720"
            vmArgs "-Dvoxy.autotest=true",
                   "-Dvoxy.autotest.scenario=${project.findProperty('voxy.autotest.scenario') ?: 'smoke'}",
                   "-Dvoxy.autotest.out=${project.findProperty('voxy.autotest.out') ?: 'autotest-out'}",
                   "-Dsodium.checks.issue2561=false"
        }
    }
}
```

- [ ] **Step 2: `.gitignore`**

Append: `/run-autotest/`

- [ ] **Step 3: `src/autotest/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "voxy_autotest",
  "version": "0.0.0",
  "name": "Voxy autotest harness (dev only)",
  "environment": "client",
  "entrypoints": { "client": ["me.cortex.voxy.autotest.VoxyAutotest"] },
  "depends": { "voxy": "*", "fabric-api": "*" }
}
```

- [ ] **Step 4: Commit** (code arrives in 1.4)

```bash
git add build.gradle .gitignore src/autotest/resources/fabric.mod.json
git commit -m "build: dev-only autotest source set and run configuration"
```

### Task 1.4: Harness code

**Files:** Create the six classes under `src/autotest/java/me/cortex/voxy/autotest/`.

Minecraft 1.20.1 signatures used here (verify each at compile; fix to the actual 1.20.1 signature if javac disagrees): `WorldOpenFlows.loadLevel(Screen, String)`, `WorldOpenFlows.createFreshLevel(String, LevelSettings, WorldOptions, Function<RegistryAccess, WorldDimensions>)`, `WorldPresets.createNormalWorldDimensions(RegistryAccess)`, `Screenshot.takeScreenshot(RenderTarget)`, `ClientPacketListener.sendCommand(String)`, `Minecraft.stop()`, Chunky `ChunkyProvider.get().getApi().isRunning(String)`.

- [ ] **Step 1: `Scenario.java`**

```java
package me.cortex.voxy.autotest;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record Scenario(String name, String world, long seed, List<String> setupCommands, List<String> seedCommands,
                       boolean waitForChunky, List<Viewpoint> viewpoints, int settleFrames, int sampleFrames,
                       int idleTimeoutSeconds, int totalTimeoutSeconds, boolean compareVoxyOff,
                       boolean requireRenderer, double minLodCoverage) {
    public record Viewpoint(String name, double x, double y, double z, float yaw, float pitch) {}

    public static Scenario load(String name) {
        var res = Scenario.class.getResourceAsStream("/autotest/scenarios/" + name + ".json");
        if (res == null) throw new IllegalArgumentException("Unknown autotest scenario: " + name);
        try (var reader = new InputStreamReader(res, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, Scenario.class);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: `FrameStats.java`**

```java
package me.cortex.voxy.autotest;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

public final class FrameStats {
    private long lastFrame = -1;
    private final LongArrayList deltas = new LongArrayList();
    private boolean recording;
    public int glErrors;

    public void onFrameEnd() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            this.glErrors++;
        }
        long now = System.nanoTime();
        if (this.recording && this.lastFrame != -1) {
            this.deltas.add(now - this.lastFrame);
        }
        this.lastFrame = now;
    }

    public void startRecording() { this.deltas.clear(); this.recording = true; }
    public void stopRecording() { this.recording = false; }
    public int recorded() { return this.deltas.size(); }

    public double meanMs() {
        if (this.deltas.isEmpty()) return 0;
        long sum = 0;
        for (int i = 0; i < this.deltas.size(); i++) sum += this.deltas.getLong(i);
        return sum / 1e6 / this.deltas.size();
    }

    public double p99Ms() {
        if (this.deltas.isEmpty()) return 0;
        long[] sorted = this.deltas.toLongArray();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)] / 1e6;
    }
}
```

- [ ] **Step 3: `ImageMetrics.java`**

```java
package me.cortex.voxy.autotest;

import com.mojang.blaze3d.platform.NativeImage;

public final class ImageMetrics {
    //Fraction of pixels whose max channel difference exceeds the threshold
    public static double differingFraction(NativeImage a, NativeImage b, int threshold) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1;
        long differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (maxChannelDiff(a.getPixelRGBA(x, y), b.getPixelRGBA(x, y)) > threshold) differing++;
            }
        }
        return differing / (double) ((long) a.getWidth() * a.getHeight());
    }

    //Mean absolute per-channel difference over RGB, in 0..255
    public static double meanAbsDiff(NativeImage a, NativeImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1;
        long total = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getPixelRGBA(x, y), q = b.getPixelRGBA(x, y);
                for (int s = 0; s < 24; s += 8) total += Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF));
            }
        }
        return total / (3.0 * a.getWidth() * a.getHeight());
    }

    private static int maxChannelDiff(int p, int q) {
        int m = 0;
        for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF)));
        return m;
    }
}
```

- [ ] **Step 4: `GlInfo.java`**

```java
package me.cortex.voxy.autotest;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlInfo {
    public static Map<String, Object> capture() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", GL11.glGetString(GL11.GL_VERSION));
        info.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
        info.put("vendor", GL11.glGetString(GL11.GL_VENDOR));
        info.put("glsl", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        List<String> ext = new ArrayList<>();
        for (int i = 0; i < count; i++) ext.add(GL30.glGetStringi(GL11.GL_EXTENSIONS, i));
        info.put("extensions", ext);
        info.put("maxTextureBufferSize", GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE));
        info.put("maxUniformBlockSize", GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE));
        info.put("maxFragmentTextureUnits", GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
        info.put("maxVertexTextureUnits", GL11.glGetInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
        return info;
    }
}
```

- [ ] **Step 5: `Report.java`**

```java
package me.cortex.voxy.autotest;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Report {
    public String scenario;
    public String status = "RUNNING";
    public final List<String> failures = new ArrayList<>();
    public Map<String, Object> gl;
    public boolean voxyInstancePresent;
    public boolean voxyRendererPresent;
    public long storageBytesStart;
    public long storageBytesEnd;
    public int glErrors;
    public double durationSeconds;
    public final List<Map<String, Object>> viewpoints = new ArrayList<>();
    public List<String> voxyDebug = new ArrayList<>();

    public Map<String, Object> addViewpoint(String name) {
        var vp = new LinkedHashMap<String, Object>();
        vp.put("name", name);
        this.viewpoints.add(vp);
        return vp;
    }

    public void fail(String reason) {
        this.failures.add(reason);
    }

    public void write(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(this));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 6: `AutotestRunner.java`**

```java
package me.cortex.voxy.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.popcraft.chunky.ChunkyProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

public final class AutotestRunner {
    private enum State { WAIT_TITLE, WAIT_WORLD, SETUP, SEED, VIEW_TELEPORT, VIEW_IDLE, VIEW_SETTLE, VIEW_SAMPLE, VIEW_OFF_SETTLE, FINISH, DONE }

    private final Scenario scenario;
    private final Path out;
    private final Report report = new Report();
    private final FrameStats frames = new FrameStats();
    private final long startNanos = System.nanoTime();

    private State state = State.WAIT_TITLE;
    private int waitTicks;
    private long stateStartNanos = System.nanoTime();
    private int viewIndex;
    private int frameCounter;
    private NativeImage voxyOnImage;

    public AutotestRunner(Scenario scenario, Path out) {
        this.scenario = scenario;
        this.out = out;
        this.report.scenario = scenario.name();
    }

    public void onFrameEnd() {
        this.frames.onFrameEnd();
        this.frameCounter++;
        if (this.report.gl == null) {
            this.report.gl = GlInfo.capture();
        }
    }

    private void enter(State s) {
        this.state = s;
        this.stateStartNanos = System.nanoTime();
        this.frameCounter = 0;
    }

    private double secondsInState() {
        return (System.nanoTime() - this.stateStartNanos) / 1e9;
    }

    public void tick(Minecraft mc) {
        if (this.state == State.DONE) return;
        if ((System.nanoTime() - this.startNanos) / 1e9 > this.scenario.totalTimeoutSeconds()) {
            this.report.fail("total timeout in state " + this.state);
            this.finish(mc);
            return;
        }
        try {
            this.step(mc);
        } catch (Throwable t) {
            this.report.fail("harness exception: " + t);
            this.finish(mc);
        }
    }

    private void step(Minecraft mc) {
        switch (this.state) {
            case WAIT_TITLE -> {
                if (mc.screen instanceof TitleScreen && ++this.waitTicks > 20) {
                    this.report.storageBytesStart = voxyStorageBytes(mc);
                    this.openWorld(mc);
                    this.enter(State.WAIT_WORLD);
                }
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null && this.secondsInState() > 5) {
                    mc.options.hideGui = true;
                    this.report.voxyInstancePresent = VoxyCommon.getInstance() != null;
                    this.scenario.setupCommands().forEach(c -> mc.player.connection.sendCommand(c));
                    this.enter(State.SETUP);
                }
            }
            case SETUP -> {
                if (this.secondsInState() > 2) {
                    if (!this.scenario.seedCommands().isEmpty()) {
                        this.scenario.seedCommands().forEach(c -> mc.player.connection.sendCommand(c));
                        this.enter(State.SEED);
                    } else {
                        this.enter(State.VIEW_TELEPORT);
                    }
                }
            }
            case SEED -> {
                boolean running = this.scenario.waitForChunky() && ChunkyProvider.get().getApi().isRunning("minecraft:overworld");
                if (this.secondsInState() > 5 && !running) {
                    this.enter(State.VIEW_TELEPORT);
                }
            }
            case VIEW_TELEPORT -> {
                if (this.viewIndex >= this.scenario.viewpoints().size()) {
                    this.finish(mc);
                    return;
                }
                var vp = this.scenario.viewpoints().get(this.viewIndex);
                mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", vp.x(), vp.y(), vp.z(), vp.yaw(), vp.pitch()));
                this.enter(State.VIEW_IDLE);
            }
            case VIEW_IDLE -> {
                var vrs = IVoxyRenderSystemHolder.getNullable();
                this.report.voxyRendererPresent |= vrs != null;
                boolean idle = vrs == null || vrs.isIdle();
                if ((this.secondsInState() > 3 && idle) || this.secondsInState() > this.scenario.idleTimeoutSeconds()) {
                    if (!idle) this.report.fail("viewpoint " + this.currentViewName() + ": voxy not idle after " + this.scenario.idleTimeoutSeconds() + "s");
                    this.enter(State.VIEW_SETTLE);
                }
            }
            case VIEW_SETTLE -> {
                if (this.frameCounter >= this.scenario.settleFrames()) {
                    this.frames.startRecording();
                    this.enter(State.VIEW_SAMPLE);
                }
            }
            case VIEW_SAMPLE -> {
                if (this.frames.recorded() >= this.scenario.sampleFrames()) {
                    this.frames.stopRecording();
                    var vp = this.report.addViewpoint(this.currentViewName());
                    vp.put("frameTimeMeanMs", this.frames.meanMs());
                    vp.put("frameTimeP99Ms", this.frames.p99Ms());
                    this.voxyOnImage = this.capture(mc, this.currentViewName() + ".png");
                    vp.put("baselineDiff", this.baselineDiff(this.voxyOnImage, this.currentViewName()));
                    if (this.scenario.compareVoxyOff()) {
                        VoxyClient.AUTOTEST_SUPPRESS_RENDER = true;
                        this.enter(State.VIEW_OFF_SETTLE);
                    } else {
                        this.nextView();
                    }
                }
            }
            case VIEW_OFF_SETTLE -> {
                if (this.frameCounter >= this.scenario.settleFrames()) {
                    NativeImage off = this.capture(mc, this.currentViewName() + "-voxyoff.png");
                    VoxyClient.AUTOTEST_SUPPRESS_RENDER = false;
                    double coverage = ImageMetrics.differingFraction(this.voxyOnImage, off, 24);
                    this.report.viewpoints.get(this.report.viewpoints.size() - 1).put("lodCoverage", coverage);
                    if (this.scenario.requireRenderer() && coverage < this.scenario.minLodCoverage()) {
                        this.report.fail("viewpoint " + this.currentViewName() + ": lodCoverage " + coverage + " < " + this.scenario.minLodCoverage());
                    }
                    off.close();
                    this.nextView();
                }
            }
            case FINISH, DONE -> {}
        }
    }

    private String currentViewName() {
        return this.scenario.viewpoints().get(this.viewIndex).name();
    }

    private void nextView() {
        if (this.voxyOnImage != null) {
            this.voxyOnImage.close();
            this.voxyOnImage = null;
        }
        this.viewIndex++;
        this.enter(State.VIEW_TELEPORT);
    }

    private void openWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(this.scenario.world())) {
            mc.createWorldOpenFlows().loadLevel(mc.screen, this.scenario.world());
        } else {
            var settings = new LevelSettings(this.scenario.world(), GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            var options = new WorldOptions(this.scenario.seed(), true, false);
            mc.createWorldOpenFlows().createFreshLevel(this.scenario.world(), settings, options, WorldPresets::createNormalWorldDimensions);
        }
    }

    private NativeImage capture(Minecraft mc, String fileName) {
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        try {
            Files.createDirectories(this.out);
            image.writeToFile(this.out.resolve(fileName));
        } catch (IOException e) {
            this.report.fail("screenshot write failed: " + e);
        }
        return image;
    }

    private double baselineDiff(NativeImage current, String viewName) {
        Path baseline = this.out.getParent().resolve("baselines").resolve(this.scenario.name()).resolve(viewName + ".png");
        if (!Files.exists(baseline)) return -1;
        try (var in = Files.newInputStream(baseline); NativeImage base = NativeImage.read(in)) {
            return ImageMetrics.meanAbsDiff(current, base);
        } catch (IOException e) {
            return -1;
        }
    }

    private void finish(Minecraft mc) {
        if (this.state == State.DONE) return;
        this.state = State.DONE;
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            var lines = new ArrayList<String>();
            vrs.addDebugInfo(lines);
            this.report.voxyDebug = lines;
        }
        if (!this.report.voxyInstancePresent) this.report.fail("voxy instance not created");
        if (this.scenario.requireRenderer() && !this.report.voxyRendererPresent) this.report.fail("voxy renderer not created");
        this.report.glErrors = this.frames.glErrors;
        this.report.storageBytesEnd = voxyStorageBytes(mc);
        this.report.durationSeconds = (System.nanoTime() - this.startNanos) / 1e9;
        this.report.status = this.report.failures.isEmpty() ? "PASS" : "FAIL";
        this.report.write(this.out);
        mc.stop();
    }

    private static long voxyStorageBytes(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve(".voxy");
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }
}
```

- [ ] **Step 7: `VoxyAutotest.java`**

```java
package me.cortex.voxy.autotest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import java.nio.file.Path;

public final class VoxyAutotest implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("voxy.autotest")) return;
        var scenario = Scenario.load(System.getProperty("voxy.autotest.scenario", "smoke"));
        var out = Path.of(System.getProperty("voxy.autotest.out", "autotest-out")).toAbsolutePath();
        var runner = new AutotestRunner(scenario, out);
        ClientTickEvents.END_CLIENT_TICK.register(runner::tick);
        WorldRenderEvents.END.register(ctx -> runner.onFrameEnd());
    }
}
```

- [ ] **Step 8: Compile**

Run: `./gradlew --console=plain compileAutotestJava > /tmp/voxy-at.txt 2>&1; grep -E 'error|BUILD' /tmp/voxy-at.txt | head -20`
Expected: `BUILD SUCCESSFUL`. Fix any 1.20.1 signature mismatches reported here (see the signature list at the top of this task).

- [ ] **Step 9: Commit**

```bash
git add src/autotest && git commit -m "autotest: dev-only harness mod (world, viewpoints, screenshots, report)"
```

### Task 1.5: Scenarios, options and runner script

**Files:** Create `src/autotest/resources/autotest/scenarios/seed.json`, `src/autotest/resources/autotest/scenarios/smoke.json`, `scripts/autotest-options.txt`, `scripts/autotest.sh`.

- [ ] **Step 1: `seed.json`** (one-time terrain generation + ingest)

```json
{
  "name": "seed", "world": "voxy-autotest", "seed": 20261017,
  "setupCommands": ["gamemode spectator", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set 6000", "weather clear"],
  "seedCommands": ["chunky world minecraft:overworld", "chunky center 0 0", "chunky radius 1024", "chunky start"],
  "waitForChunky": true,
  "viewpoints": [{"name": "spawn-high", "x": 0, "y": 200, "z": 0, "yaw": 0, "pitch": 20}],
  "settleFrames": 120, "sampleFrames": 120, "idleTimeoutSeconds": 120, "totalTimeoutSeconds": 900,
  "compareVoxyOff": false, "requireRenderer": false, "minLodCoverage": 0.0
}
```

- [ ] **Step 2: `smoke.json`** (per-iteration check)

```json
{
  "name": "smoke", "world": "voxy-autotest", "seed": 20261017,
  "setupCommands": ["gamemode spectator", "time set 6000", "weather clear"],
  "seedCommands": [],
  "waitForChunky": false,
  "viewpoints": [
    {"name": "spawn-high-north", "x": 0, "y": 200, "z": 0, "yaw": 180, "pitch": 15},
    {"name": "spawn-high-east", "x": 0, "y": 200, "z": 0, "yaw": -90, "pitch": 15},
    {"name": "ground-horizon", "x": 300, "y": 120, "z": 300, "yaw": 45, "pitch": 0}
  ],
  "settleFrames": 120, "sampleFrames": 300, "idleTimeoutSeconds": 90, "totalTimeoutSeconds": 420,
  "compareVoxyOff": true, "requireRenderer": false, "minLodCoverage": 0.05
}
```

(`requireRenderer` becomes `true` in phase 3.)

- [ ] **Step 3: `scripts/autotest-options.txt`**

```
renderDistance:8
simulationDistance:8
pauseOnLostFocus:false
onboardAccessibility:false
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
narrator:0
fov:0.0
guiScale:2
maxFps:260
enableVsync:false
fullscreen:false
realmsNotifications:false
soundCategory_master:0.0
```

- [ ] **Step 4: `scripts/autotest.sh`**

```bash
#!/usr/bin/env bash
# Runs one autotest scenario in the dev client and checks the result.
# Usage: scripts/autotest.sh [scenario] ; env AUTOTEST_TIMEOUT (seconds, default 900)
set -uo pipefail
SCENARIO="${1:-smoke}"
TIMEOUT="${AUTOTEST_TIMEOUT:-900}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT/run-autotest"
OUT="$RUN_DIR/autotest/$(date +%Y%m%d-%H%M%S)-$SCENARIO"
mkdir -p "$OUT"
cp "$ROOT/scripts/autotest-options.txt" "$RUN_DIR/options.txt"

( cd "$ROOT" && exec ./gradlew --console=plain runAutotest \
    -Pvoxy.autotest.scenario="$SCENARIO" -Pvoxy.autotest.out="$OUT" ) > "$OUT/gradle.log" 2>&1 &
GRADLE_PID=$!
( sleep "$TIMEOUT"; echo "autotest: timeout after ${TIMEOUT}s" >> "$OUT/gradle.log"; kill "$GRADLE_PID" 2>/dev/null; pkill -f 'voxy.autotest=true' ) &
WATCHDOG=$!
wait "$GRADLE_PID"; RC=$?
kill "$WATCHDOG" 2>/dev/null; pkill -f 'voxy.autotest=true' 2>/dev/null

cp "$RUN_DIR/logs/latest.log" "$OUT/latest.log" 2>/dev/null
grep -nE 'Exception|Mixin apply failed|FATAL|\[Render thread/ERROR\]' "$OUT/latest.log" > "$OUT/log-issues.txt" 2>/dev/null

STATUS=MISSING
[ -f "$OUT/report.json" ] && STATUS="$(jq -r .status "$OUT/report.json")"
echo "scenario: $SCENARIO  gradle-exit: $RC  status: $STATUS  out: $OUT"
[ -f "$OUT/report.json" ] && jq -r '.failures[]?, (.viewpoints[] | "\(.name): mean \(.frameTimeMeanMs|floor)ms p99 \(.frameTimeP99Ms|floor)ms lod \(.lodCoverage // "n/a") base \(.baselineDiff)"), "glErrors: \(.glErrors)  storage: \(.storageBytesStart) -> \(.storageBytesEnd)  instance: \(.voxyInstancePresent) renderer: \(.voxyRendererPresent)"' "$OUT/report.json"
echo "log issues: $(wc -l < "$OUT/log-issues.txt" | tr -d ' ')"
[ "$STATUS" = "PASS" ]
```

```bash
chmod +x scripts/autotest.sh
```

- [ ] **Step 5: Commit**

```bash
git add scripts src/autotest/resources && git commit -m "autotest: seed and smoke scenarios, runner script"
```

### Task 1.6: Boot verification on macOS

- [ ] **Step 1: Seed run** — `AUTOTEST_TIMEOUT=900 scripts/autotest.sh seed`
Expected: `status: PASS`, `instance: true renderer: false`, `storage: 0 -> >0` (Voxy ingested Chunky-generated chunks into RocksDB), `log issues` lists no Voxy exceptions; `latest.log` contains "Voxy rendering is unsupported on this system".

- [ ] **Step 2: Smoke run** — `scripts/autotest.sh smoke`
Expected: `status: PASS`, three screenshots in the output directory, `lodCoverage` near 0 for all viewpoints (no renderer yet), `glErrors: 0`.

- [ ] **Step 3: Inspect** the screenshots and `log-issues.txt`; record frame times as the pre-renderer baseline in the commit message.

- [ ] **Step 4: Accept baselines and commit**

```bash
mkdir -p run-autotest/autotest/baselines/smoke && cp "$(ls -d run-autotest/autotest/*-smoke | tail -1)"/spawn-*.png "$(ls -d run-autotest/autotest/*-smoke | tail -1)"/ground-*.png run-autotest/autotest/baselines/smoke/ 2>/dev/null
git commit --allow-empty -m "phase 1 verified: boots on macOS 1.20.1, voxy ingests and stores without a renderer

seed: <storage bytes>, smoke frame time mean/p99 per viewpoint: <values>"
git push
```

---

## Later phases

Phases 2–5 get their own plans once phase 1 is merged, because their concrete code depends on the merged 1.20.1 sources (exact `VoxyRenderSystem`, pipeline and mixin shapes after the merge).
