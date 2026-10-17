package me.cortex.voxy.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
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
import org.lwjgl.opengl.GL11;
import org.popcraft.chunky.ChunkyProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Stream;

//Drives the client through a scenario: open world, setup, optional seeding, viewpoints, report, quit
public final class AutotestRunner {
    private enum State { WAIT_TITLE, WAIT_WORLD, SETUP, SEED, VIEW_TELEPORT, VIEW_IDLE, VIEW_SETTLE, VIEW_SAMPLE, VIEW_OFF_SETTLE, DONE }

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
        switch (this.state) {
            case WAIT_TITLE -> {
                //The title screen shows before resource loading finishes, wait for the loading overlay to go away
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++this.waitTicks > 20) {
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
                mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", vp.x(), vp.y(), vp.z(), vp.yaw(), vp.pitch()));
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
            case DONE -> {}
        }
    }

    //Finds the closest position of each biome tag near the origin, used to pick interesting viewpoints
    private void survey(Minecraft mc) {
        var tags = this.scenario.surveyBiomeTags();
        var server = mc.getSingleplayerServer();
        if (tags == null || tags.isEmpty() || server == null) return;
        server.submit(() -> {
            var level = server.overworld();
            for (String tag : tags) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("tag", tag);
                var key = TagKey.create(Registries.BIOME, new ResourceLocation(tag));
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
            mc.createWorldOpenFlows().loadLevel(mc.screen, this.world);
        } else {
            var settings = new LevelSettings(this.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            var options = new WorldOptions(this.seed, true, false);
            mc.createWorldOpenFlows().createFreshLevel(this.world, settings, options, WorldPresets::createNormalWorldDimensions);
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
        VoxyClient.AUTOTEST_SUPPRESS_RENDER = false;
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            var lines = new ArrayList<String>();
            vrs.addDebugInfo(lines);
            this.report.voxyDebug = lines;
        }
        if (!this.report.voxyInstancePresent) this.report.fail("voxy instance not created");
        if (this.scenario.requireRenderer() && !this.report.voxyRendererPresent) this.report.fail("voxy renderer not created");
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
