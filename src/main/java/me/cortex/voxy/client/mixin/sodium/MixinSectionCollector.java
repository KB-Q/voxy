package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Sodium 0.8 replacement of VisibleChunkCollector: every section the occlusion search finds visible passes through here
@Mixin(value = SectionCollector.class, remap = false)
public class MixinSectionCollector {
    //Gather the built sections sodium found visible, these are used to bound where voxy should not render
    @Inject(method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V", at = @At("HEAD"))
    private void voxy$injectVisibleSectionGather(RenderSection section, int flags, CallbackInfo ci) {
        if (!section.isBuilt()) return;
        VoxyRenderSystem vrs;
        if (!IrisUtil.irisShadowActive() && (vrs = IVoxyRenderSystemHolder.getNullable()) != null && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.put(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
    }
}
