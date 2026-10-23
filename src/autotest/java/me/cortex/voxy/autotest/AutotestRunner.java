package me.cortex.voxy.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.irisshaders.iris.Iris;
import org.popcraft.chunky.ChunkyProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

//Drives the client through a scenario: open world, setup, optional seeding, viewpoints, report, quit
public final class AutotestRunner {
    //Must match the --width/--height program arguments of the autotest run in build.gradle
    private static final int WINDOW_WIDTH = 1280, WINDOW_HEIGHT = 720;
    private enum State { WAIT_TITLE, WAIT_WORLD, SETUP, SEED, POST_SEED, WAIT_IMPORT, VIEW_TELEPORT, VIEW_IDLE, VIEW_SETTLE, VIEW_SAMPLE, VIEW_OFF_SETTLE, VIEW_OFF_SAMPLE, DONE }

    private final Scenario scenario;
    private final String world;
    private final long seed;
    private final Path out;
    private final Report report = new Report();
    private final FrameStats frames = new FrameStats();
    private final long startNanos = System.nanoTime();

    private State state = State.WAIT_TITLE;
    private int waitTicks;
    private long stateStartNanos = System.nanoTime();
    private int viewIndex;
    private int postSeedIndex;
    private int frameCounter;
    private NativeImage voxyOnImage;

    public AutotestRunner(Scenario scenario, Path out) {
        this.scenario = scenario;
        this.world = System.getProperty("voxy.autotest.world", scenario.world());
        this.seed = Long.getLong("voxy.autotest.seed", scenario.seed());
        this.out = out;
        this.report.scenario = scenario.name();
    }

    public void onFrameEnd() {
        if (this.report.gl == null) {
            //Errors raised before the first world frame (e.g. capability probing) are reported separately
            int before = 0;
            while (GL11.glGetError() != GL11.GL_NO_ERROR) before++;
            this.report.gl = GlInfo.capture();
            this.report.gl.put("errorsBeforeFirstWorldFrame", before);
            return;
        }
        this.frames.onFrameEnd();
        this.frameCounter++;
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
        //A paused world stops the server (and chunk generation), so close any menu opened during the run
        if (this.state != State.WAIT_TITLE && this.state != State.WAIT_WORLD && mc.level != null && mc.screen != null) {
            this.report.closedScreens.add(mc.screen.getClass().getSimpleName() + " in " + this.state);
            mc.setScreen(null);
        }
        //Mouse or keyboard input on the window must not move the camera between viewpoint captures
        if (mc.level != null && mc.player != null) {
            mc.mouseHandler.releaseMouse();
            if (this.state.ordinal() > State.VIEW_TELEPORT.ordinal() && this.state != State.DONE) {
                this.pinCamera(mc);
            }
        }
        switch (this.state) {
            case WAIT_TITLE -> {
                //The title screen shows before resource loading finishes, wait for the loading overlay to go away
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++this.waitTicks > 20) {
                    if (this.scenario.systemProperties() != null) {
                        this.scenario.systemProperties().forEach(System::setProperty);
                    }
                    this.applyShaderPack();
                    this.report.storageBytesStart = this.voxyStorageBytes(mc);
                    this.openWorld(mc);
                    this.enter(State.WAIT_WORLD);
                }
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null && this.secondsInState() > 5) {
                    mc.options.hideGui = true;
                    this.report.voxyInstancePresent = VoxyCommon.getInstance() != null;
                    this.scenario.setupCommands().forEach(c -> mc.player.connection.sendCommand(c));
                    this.survey(mc);
                    this.enter(State.SETUP);
                }
            }
            case SETUP -> {
                if (this.secondsInState() > 2) {
                    if (!this.scenario.seedCommands().isEmpty() || hasPostSeedCommands()) {
                        this.scenario.seedCommands().forEach(c -> mc.player.connection.sendCommand(c));
                        this.enter(State.SEED);
                    } else {
                        this.enter(State.VIEW_TELEPORT);
                    }
                }
            }
            case SEED -> {
                boolean running = this.scenario.waitForChunky() && ChunkyProvider.get().getApi().isRunning("minecraft:overworld");
                if (this.secondsInState() > Math.max(5, this.scenario.seedWaitSeconds()) && !running) {
                    this.report.seedSeconds = this.secondsInState();
                    this.postSeedIndex = 0;
                    this.enter(hasPostSeedCommands() ? State.POST_SEED : State.VIEW_TELEPORT);
                }
            }
            case POST_SEED -> {
                if (this.secondsInState() > 3) {
                    var commands = this.scenario.postSeedCommands();
                    runCommand(mc, commands.get(this.postSeedIndex++));
                    this.enter(this.postSeedIndex < commands.size() ? State.POST_SEED : State.WAIT_IMPORT);
                }
            }
            case WAIT_IMPORT -> {
                var instance = VoxyCommon.getInstance();
                boolean importing = this.scenario.waitForImport() && instance != null && instance.getImportManager().hasActiveImports();
                if (this.secondsInState() > 5 && !importing) {
                    this.report.importSeconds = this.secondsInState();
                    this.enter(State.VIEW_TELEPORT);
                }
            }
            case VIEW_TELEPORT -> {
                if (this.viewIndex >= this.scenario.viewpoints().size()) {
                    this.finish(mc);
                    return;
                }
                var vp = this.scenario.viewpoints().get(this.viewIndex);
                if (vp.aboveSurface() != null) {
                    vp = vp.withY(this.surfaceY(mc, (int) Math.floor(vp.x()), (int) Math.floor(vp.z())) + vp.aboveSurface());
                    this.scenario.viewpoints().set(this.viewIndex, vp);//pinCamera reads the resolved position
                    this.report.notes.add(vp.name() + " resolved to y " + vp.y());
                }
                this.restoreWindowSize(mc);
                if (vp.fov() != null) mc.options.fov().set(vp.fov());
                if (vp.renderDistance() != null && mc.options.renderDistance().get().intValue() != vp.renderDistance()) {
                    mc.options.renderDistance().set(vp.renderDistance());
                }
                if (vp.spyglass()) {
                    mc.player.connection.sendCommand("gamemode creative");
                    mc.player.connection.sendCommand("item replace entity @s weapon.mainhand with minecraft:spyglass");
                }
                mc.options.keyUse.setDown(vp.spyglass());
                String tp = String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", vp.x(), vp.y(), vp.z(), vp.yaw(), vp.pitch());
                mc.player.connection.sendCommand(vp.dimension() == null ? tp : "execute in " + vp.dimension() + " run " + tp);
                this.enter(State.VIEW_IDLE);
            }
            case VIEW_IDLE -> {
                var vrs = IVoxyRenderSystemHolder.getNullable();
                this.report.voxyRendererPresent |= vrs != null;
                boolean idle = vrs == null || vrs.isIdle();
                if ((this.secondsInState() > 3 && idle) || this.secondsInState() > this.scenario.idleTimeoutSeconds()) {
                    if (!idle && !this.scenario.allowBusy()) this.report.fail("viewpoint " + this.currentViewName() + ": voxy not idle after " + this.scenario.idleTimeoutSeconds() + "s");
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
                    vp.put("framebuffer", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                    vp.put("frameTimeMeanMs", this.frames.meanMs());
                    vp.put("frameTimeP99Ms", this.frames.p99Ms());
                    vp.put("frameTimeP50Ms", this.frames.percentileMs(0.5));
                    vp.put("frameTimeP90Ms", this.frames.percentileMs(0.9));
                    vp.put("frameTimeMaxMs", this.frames.percentileMs(1.0));
                    vp.put("frameTimesMs", this.frames.framesMs());
                    var debugVrs = IVoxyRenderSystemHolder.getNullable();
                    if (debugVrs != null) {
                        var lines = new ArrayList<String>();
                        debugVrs.addDebugInfo(lines);
                        vp.put("voxyDebug", lines);
                    }
                    this.voxyOnImage = this.capture(mc, this.currentViewName() + ".png");
                    this.compareBaseline(vp, this.voxyOnImage, this.currentViewName());
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
                    this.frames.startRecording();
                    this.enter(State.VIEW_OFF_SAMPLE);
                }
            }
            case VIEW_OFF_SAMPLE -> {
                //Same frame count as with voxy, the difference is the cost of voxy at this viewpoint
                if (this.frames.recorded() >= this.scenario.sampleFrames()) {
                    this.frames.stopRecording();
                    var vp = this.report.viewpoints.get(this.report.viewpoints.size() - 1);
                    vp.put("frameTimeOffMeanMs", this.frames.meanMs());
                    vp.put("frameTimeOffP99Ms", this.frames.p99Ms());
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
            case DONE -> {}
        }
    }

    //Finds the closest position of each biome tag near the origin, used to pick interesting viewpoints
    private void survey(Minecraft mc) {
        if (this.scenario.surveyPeaks()) this.surveyPeaks(mc);
        var tags = this.scenario.surveyBiomeTags();
        var server = mc.getSingleplayerServer();
        if (tags == null || tags.isEmpty() || server == null) return;
        server.submit(() -> {
            var level = server.overworld();
            for (String tag : tags) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("tag", tag);
                var key = TagKey.create(Registries.BIOME, ResourceLocation.parse(tag));
                var found = level.findClosestBiome3d(b -> b.is(key), new BlockPos(0, 64, 0), 1000, 32, 64);
                if (found != null) {
                    var pos = found.getFirst();
                    entry.put("x", pos.getX());
                    entry.put("z", pos.getZ());
                    level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);//Make sure the chunk exists so the heightmap is valid
                    entry.put("surfaceY", level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()));
                    entry.put("biome", found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse("?"));
                }
                this.report.survey.add(entry);
            }
        }).join();
    }

    private int surfaceY(Minecraft mc, int x, int z) {
        var server = mc.getSingleplayerServer();
        return server.submit(() -> {
            var level = server.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }).join();
    }

    //Highest surface point of each 128x128 cell of the pre-generated area (radius 1024), sampled every 16 blocks
    private void surveyPeaks(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.submit(() -> {
            var level = server.overworld();
            for (int cx = -1024; cx < 1024; cx += 128) {
                for (int cz = -1024; cz < 1024; cz += 128) {
                    int bestY = Integer.MIN_VALUE, bestX = 0, bestZ = 0;
                    for (int x = cx; x < cx + 128; x += 16) {
                        for (int z = cz; z < cz + 128; z += 16) {
                            level.getChunk(x >> 4, z >> 4);
                            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            if (y > bestY) { bestY = y; bestX = x; bestZ = z; }
                        }
                    }
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("tag", "peak");
                    entry.put("x", bestX);
                    entry.put("z", bestZ);
                    entry.put("surfaceY", bestY);
                    this.report.survey.add(entry);
                }
            }
        }).join();
    }

    //The teleport sets the camera once, this keeps it there (and restores it after mouse or keyboard input)
    private void pinCamera(Minecraft mc) {
        var vp = this.scenario.viewpoints().get(this.viewIndex);
        var player = mc.player;
        if (vp.dimension() != null && !mc.level.dimension().location().toString().equals(vp.dimension())) return;
        player.setYRot(vp.yaw());
        player.setXRot(vp.pitch());
        player.yRotO = vp.yaw();
        player.xRotO = vp.pitch();
        player.setYHeadRot(vp.yaw());
        player.setDeltaMovement(Vec3.ZERO);
        if (vp.spyglass()) {
            player.getAbilities().flying = true;
        }
        if (player.distanceToSqr(vp.x(), vp.y(), vp.z()) > 0.01) {
            player.setPos(vp.x(), vp.y(), vp.z());
        }
    }

    //Frame times and screenshots depend on the framebuffer size, undo any resize or maximise of the window
    private void restoreWindowSize(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
            GLFW.glfwRestoreWindow(window);
        }
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        if (w[0] != WINDOW_WIDTH || h[0] != WINDOW_HEIGHT) {
            this.report.notes.add("window resized from " + w[0] + "x" + h[0] + " before " + this.currentViewNameOr("start"));
            GLFW.glfwSetWindowSize(window, WINDOW_WIDTH, WINDOW_HEIGHT);
        }
    }

    private String currentViewNameOr(String fallback) {
        return this.viewIndex < this.scenario.viewpoints().size() ? this.currentViewName() : fallback;
    }

    //Every scenario sets the shader state explicitly so a previous run does not leak into it
    private void applyShaderPack() {
        String pack = System.getProperty("voxy.autotest.shaderPack", this.scenario.shaderPack());
        boolean enable = pack != null && !pack.isEmpty();
        //The 1.21.1 dev run has no iris (see build.gradle), shader scenarios run in the production launch
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
            if (enable) this.report.fail("shader pack " + pack + " requested but iris is not loaded");
            this.report.shaderPack = null;
            return;
        }
        try {
            IrisAccess.apply(enable, pack);
        } catch (IOException e) {
            this.report.fail("could not apply shader pack " + pack + ": " + e);
        }
        this.report.shaderPack = enable ? pack : null;
    }

    //Iris classes are only touched from here, so the runner loads without iris
    private static final class IrisAccess {
        static void apply(boolean enable, String pack) throws IOException {
            var config = Iris.getIrisConfig();
            boolean changed = config.areShadersEnabled() != enable || (enable && !config.getShaderPackName().orElse("").equals(pack));
            if (changed) {
                if (enable) config.setShaderPackName(pack);
                config.setShadersEnabled(enable);
                config.save();
                Iris.reload();
            }
        }
    }

    private boolean hasPostSeedCommands() {
        return this.scenario.postSeedCommands() != null && !this.scenario.postSeedCommands().isEmpty();
    }

    //Client side commands (e.g. /voxy) are not sent to the server, try them first
    private static void runCommand(Minecraft mc, String command) {
        if (!ClientCommandInternals.executeCommand(command)) {
            mc.player.connection.sendCommand(command);
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
        if (mc.getLevelSource().levelExists(this.world)) {
            mc.createWorldOpenFlows().openWorld(this.world, () -> {});
        } else {
            var settings = new LevelSettings(this.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            var options = new WorldOptions(this.seed, true, false);
            if ("amplified".equals(this.scenario.worldPreset())) {
                mc.createWorldOpenFlows().createFreshLevel(this.world, settings, options,
                        ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.AMPLIFIED).value().createWorldDimensions(), mc.screen);
            } else {
                mc.createWorldOpenFlows().createFreshLevel(this.world, settings, options, WorldPresets::createNormalWorldDimensions, mc.screen);
            }
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

    //baselineDiff: mean absolute channel difference (0-255), baselineDiffFraction: fraction of pixels with a channel
    // differing by more than 24, both -1 without a baseline of the same size
    private void compareBaseline(Map<String, Object> vp, NativeImage current, String viewName) {
        vp.put("baselineDiff", -1.0);
        vp.put("baselineDiffFraction", -1.0);
        Path baseline = this.out.getParent().resolve("baselines").resolve(this.scenario.name()).resolve(viewName + ".png");
        if (!Files.exists(baseline)) return;
        try (var in = Files.newInputStream(baseline); NativeImage base = NativeImage.read(in)) {
            if (base.getWidth() != current.getWidth() || base.getHeight() != current.getHeight()) return;
            vp.put("baselineDiff", ImageMetrics.meanAbsDiff(current, base));
            double fraction = ImageMetrics.differingFraction(current, base, 24);
            vp.put("baselineDiffFraction", fraction);
            if (this.scenario.maxBaselineDiffFraction() > 0 && fraction > this.scenario.maxBaselineDiffFraction()) {
                this.report.fail("viewpoint " + viewName + ": baselineDiffFraction " + fraction + " > " + this.scenario.maxBaselineDiffFraction());
            }
        } catch (IOException e) {
            this.report.notes.add("baseline read failed for " + viewName + ": " + e);
        }
    }

    private void finish(Minecraft mc) {
        if (this.state == State.DONE) return;
        this.state = State.DONE;
        VoxyClient.AUTOTEST_SUPPRESS_RENDER = false;
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            var lines = new ArrayList<String>();
            vrs.addDebugInfo(lines);
            this.report.voxyDebug = lines;
        }
        if (!this.report.voxyInstancePresent) this.report.fail("voxy instance not created");
        if (this.scenario.requireRenderer() && !this.report.voxyRendererPresent) this.report.fail("voxy renderer not created");
        this.report.shaderPatchFallbacks = AbstractSectionRenderer.PATCH_FALLBACKS;
        if (this.scenario.requireShaderPatch()) {
            boolean irisPipeline = this.report.voxyDebug.stream().anyMatch(l -> l.contains("Using: IrisVoxyRenderPipeline"));
            if (!irisPipeline) this.report.fail("voxy is not rendering through the shader pack (no IrisVoxyRenderPipeline)");
            if (this.report.shaderPatchFallbacks != 0) this.report.fail(this.report.shaderPatchFallbacks + " shader pack patches failed to compile");
        }
        this.report.glErrors = this.frames.glErrors;
        this.report.storageBytesEnd = this.voxyStorageBytes(mc);
        this.report.durationSeconds = (System.nanoTime() - this.startNanos) / 1e9;
        this.report.status = this.report.failures.isEmpty() ? "PASS" : "FAIL";
        this.report.write(this.out);
        mc.stop();
    }

    //Singleplayer worlds keep their voxy storage inside the world save
    private long voxyStorageBytes(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(this.world).resolve("voxy");
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }
}
