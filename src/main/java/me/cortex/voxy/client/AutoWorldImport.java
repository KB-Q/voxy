package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

//Single player: imports the chunks already saved in the world's region files once per dimension (what /voxy import
// current does), so terrain generated before voxy was installed, or by pregenerators while it was not, shows up without
// visiting it. Chunks generated afterwards are ingested as the server creates them (commonImpl MixinChunkMap).
// A marker file in the world's voxy folder records a finished import, a cancelled or interrupted one runs again
public final class AutoWorldImport {
    private static final Set<String> checkedThisSession = new HashSet<>();
    private static Object lastServer;

    private AutoWorldImport() {}

    public static void tick(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != lastServer) {
            lastServer = server;
            checkedThisSession.clear();
        }
        if (server == null || mc.level == null || mc.player == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        if (VoxyCommon.getInstance() != null) {
            me.cortex.voxy.commonImpl.PendingServerIngest.drain(server);
        }
        if (!VoxyConfig.CONFIG.autoImportExistingChunks) {
            return;
        }
        var dimension = mc.level.dimension();
        String key = dimension.location().toString();
        if (!checkedThisSession.add(key)) return;

        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            checkedThisSession.remove(key);//Not ready yet, try again next tick
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path marker = worldRoot.resolve("voxy").resolve("auto-import-" + key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".done");
        if (Files.exists(marker)) return;
        Path regions = DimensionType.getStorageFolder(dimension, worldRoot).resolve("region");
        if (!Files.isDirectory(regions)) return;

        WorldEngine engine = WorldIdentifier.ofEngine(mc.level);
        if (engine == null) {
            checkedThisSession.remove(key);
            return;
        }
        var level = mc.level;
        boolean started = instance.getImportManager().makeAndRunIfNone(engine, () -> {
            var importer = new WorldImporter(engine, level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(regions.toFile());
            return new MarkingImporter(importer, marker);
        });
        if (started) {
            Logger.info("Auto importing existing chunks of " + key + " from " + regions);
            mc.player.displayClientMessage(Component.literal("Voxy: importing the existing chunks of this dimension (one time, \"/voxy import cancel\" stops it)"), false);
        }
    }

    //Writes the marker when the import finishes, unless it was cancelled or shut down
    private static final class MarkingImporter implements IDataImporter {
        private final IDataImporter delegate;
        private final Path marker;
        private volatile boolean stopped;

        private MarkingImporter(IDataImporter delegate, Path marker) {
            this.delegate = delegate;
            this.marker = marker;
        }

        @Override
        public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
            this.delegate.runImport(updateCallback, chunks -> {
                if (!this.stopped) {
                    try {
                        Files.createDirectories(this.marker.getParent());
                        Files.writeString(this.marker, "imported " + chunks + " chunks\n");
                        Logger.info("Auto import finished, " + chunks + " chunks");
                    } catch (IOException e) {
                        Logger.error("Could not write the auto import marker " + this.marker, e);
                    }
                }
                completionCallback.onCompletion(chunks);
            });
        }

        @Override
        public WorldEngine getEngine() {
            return this.delegate.getEngine();
        }

        @Override
        public void shutdown() {
            this.stopped = true;
            this.delegate.shutdown();
        }

        @Override
        public boolean isRunning() {
            return this.delegate.isRunning();
        }
    }
}
