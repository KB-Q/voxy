package me.cortex.voxy.commonImpl.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.datafixers.util.Either;
import me.cortex.voxy.client.IngestStats;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.PendingServerIngest;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

//Ingests every chunk the (integrated) server turns into a full chunk: newly generated ones, whatever requested them
// (players, Chunky, Chunksmith or any other pregenerator), and ones loaded back from disk. Light has been computed by
// then (the LIGHT status runs before FULL). Without a voxy instance (dedicated server) this is a no-op
@Mixin(ChunkMap.class)
public class MixinChunkMap {
    @ModifyReturnValue(method = "protoChunkToFullChunk", at = @At("RETURN"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> voxy$ingestFullChunk(CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future) {
        return future.thenApply(result -> {
            result.ifLeft(chunk -> {
                if (chunk instanceof LevelChunk levelChunk) {
                    if (VoxyCommon.getInstance() == null) {
                        if (VoxyCommon.isAvailable()) {
                            PendingServerIngest.add(levelChunk.getLevel(), levelChunk.getPos());
                        }
                    } else if (VoxelIngestService.tryAutoIngestChunk(levelChunk)) {
                        IngestStats.serverOk++;
                    } else {
                        IngestStats.serverFailed++;
                    }
                }
            });
            return result;
        });
    }
}
