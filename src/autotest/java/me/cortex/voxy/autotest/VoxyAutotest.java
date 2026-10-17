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
