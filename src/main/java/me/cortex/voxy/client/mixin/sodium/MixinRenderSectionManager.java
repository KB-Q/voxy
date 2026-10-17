package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Shadow @Final private ClientLevel world;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, CommandList commandList, CallbackInfo ci) {
        this.bottomSectionY = this.world.getMinBuildHeight()>>4;
    }

    //The visible section stream is rebuilt every time sodium rebuilds its render lists, see MixinVisibleChunkCollector
    @Inject(method = "createTerrainRenderList", at = @At("HEAD"))
    private void voxy$resetVisibleStream(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive() && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache)this.world.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.world != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.world.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    @Unique private int bottomSectionY;
    @Unique private static int voxy$skippedIngests;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setInfo(Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)V"))
    private void voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags()!=0;
        int flags = instance.getFlags();
        instance.setInfo(info);
        if (wasBuilt == (instance.getFlags()!=0)) {//Only want to do stuff on change
            return;
        }

        flags |= instance.getFlags();
        if (flags == 0)//Only process things with stuff
            return;

        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        //Ingest both when a section stops being built (it is about to be unloaded) and when it is first built,
        // the latter because onChunkAdded fires before lighting is available for freshly generated chunks
        var tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.world)).getChunkStatus();
        //The status is looked up every time, a cached value goes stale once the chunk unloads
        if (tracker.getOrDefault(ChunkPos.asLong(x, z), 0) == 3) {//If this chunk still has surrounding chunks
            //world.getChunk returns the all air placeholder chunk for chunks that are no longer loaded, ingesting that
            // overwrites real lod data with air, so only ingest chunks that are actually present
            var chunk = this.world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null || chunk instanceof EmptyLevelChunk) {
                if ((voxy$skippedIngests++ & 1023) == 0) {
                    Logger.info("Skipped section ingest for unloaded chunk " + x + "," + z + " (" + voxy$skippedIngests + " total)");
                }
                return;
            }
            var section = chunk.getSection(y-this.bottomSectionY);
            var lp = this.world.getLightEngine();

            var csp = SectionPos.of(x, y, z);
            var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
            var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

            //Note: we dont do this check and just blindly ingest, it shouldbe ok :tm:
            //if (blp != null || slp != null)
            VoxelIngestService.rawIngest(vrs.getEngine(), section, x, y, z, blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
        }
    }
}
