package me.cortex.voxy.autotest;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

//postSeedCommands run one every 3 seconds after seeding (client commands such as "voxy import current" work too),
// waitForImport then waits until no voxy import is running
public record Scenario(String name, String world, long seed, List<String> setupCommands, List<String> seedCommands,
                       boolean waitForChunky, List<String> postSeedCommands, boolean waitForImport, List<Viewpoint> viewpoints, int settleFrames, int sampleFrames,
                       int idleTimeoutSeconds, int totalTimeoutSeconds, boolean compareVoxyOff,
                       boolean requireRenderer, double minLodCoverage, List<String> surveyBiomeTags,
                       double maxBaselineDiffFraction,//0 disables the baseline check
                       String shaderPack,//Iris shader pack file name in shaderpacks/, null runs without shaders
                       boolean requireShaderPatch,//fail unless voxy renders through the pack (IrisVoxyRenderPipeline, no patch fallback)
                       java.util.Map<String, String> systemProperties,//set before the world opens, e.g. voxy.geometryBufferSizeOverrideMB
                       boolean allowBusy,//viewpoints may be captured while voxy still has work (moving camera tests)
                       boolean surveyPeaks,//report the highest surface points of the pre-generated area
                       String worldPreset,//preset of a newly created world: null (normal) or "amplified"
                       int seedWaitSeconds) {//minimum time in the seed state, for pregenerators the harness can not query (Chunksmith)
    //dimension: optional, e.g. minecraft:the_nether, the teleport then goes through execute in
    //fov (degrees, 30-110) and renderDistance (vanilla chunks): optional, applied before the teleport and kept for later viewpoints
    //spyglass: zoom through a held spyglass (vanilla 10x zoom, the same projection change zoom mods make), switches to creative flight
    //aboveSurface: optional, y is replaced by the world surface height at x,z plus this offset
    public record Viewpoint(String name, double x, double y, double z, float yaw, float pitch, String dimension, Integer fov, Integer renderDistance, boolean spyglass, Double aboveSurface) {
        public Viewpoint withY(double newY) {
            return new Viewpoint(this.name, this.x, newY, this.z, this.yaw, this.pitch, this.dimension, this.fov, this.renderDistance, this.spyglass, null);
        }
    }

    public static Scenario load(String name) {
        var res = Scenario.class.getResourceAsStream("/autotest/scenarios/" + name + ".json");
        if (res == null) throw new IllegalArgumentException("Unknown autotest scenario: " + name);
        try (var reader = new InputStreamReader(res, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, Scenario.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
