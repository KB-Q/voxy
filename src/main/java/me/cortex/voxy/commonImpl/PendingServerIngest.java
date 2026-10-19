package me.cortex.voxy.commonImpl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

//Chunks that became full on the server before the voxy instance existed (the spawn area generated while a single
// player world is created or opened), ingested once the instance is available. Spawn chunks stay loaded, others that
// were unloaded in between are skipped (they are in the region files, see AutoWorldImport)
public final class PendingServerIngest {
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PENDING = new HashMap<>();

    private PendingServerIngest() {}

    public static synchronized void add(Level level, ChunkPos pos) {
        PENDING.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(pos.toLong());
    }

    public static synchronized boolean isEmpty() {
        return PENDING.isEmpty();
    }

    //Must be called with a voxy instance present, the work runs on the server thread
    public static void drain(MinecraftServer server) {
        Map<ResourceKey<Level>, LongOpenHashSet> work;
        synchronized (PendingServerIngest.class) {
            if (PENDING.isEmpty()) return;
            work = new HashMap<>(PENDING);
            PENDING.clear();
        }
        server.execute(() -> {
            for (var entry : work.entrySet()) {
                var level = server.getLevel(entry.getKey());
                if (level == null) continue;
                var iter = entry.getValue().iterator();
                while (iter.hasNext()) {
                    long pos = iter.nextLong();
                    var chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                    if (chunk != null && VoxelIngestService.tryAutoIngestChunk(chunk)) {
                        me.cortex.voxy.client.IngestStats.serverDeferredOk++;
                    }
                }
            }
        });
    }
}
