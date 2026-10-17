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
                       double maxBaselineDiffFraction) {//0 disables the baseline check
    public record Viewpoint(String name, double x, double y, double z, float yaw, float pitch) {}

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
