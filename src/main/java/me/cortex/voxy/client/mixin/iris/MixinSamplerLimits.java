package me.cortex.voxy.client.mixin.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.core.gl.GlCompat;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.opengl.GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;

//Iris hands out one texture unit per active sampler of a program (all stages) but caps the unit index at
// GL_MAX_TEXTURE_IMAGE_UNITS, the fragment stage limit (16 on macOS). Unit indices are limited by the combined
// limit, the per stage sampler counts are already checked by the driver at link time. With voxy enabled, packs such as
// Complementary sample the voxy depth textures in their composite passes and go over 16 units across the stages
@Mixin(value = SamplerLimits.class, remap = false)
public class MixinSamplerLimits {
    @Inject(method = "getMaxTextureUnits", at = @At("RETURN"), cancellable = true)
    private void voxy$useCombinedUnitLimit(CallbackInfoReturnable<Integer> cir) {
        if (GlCompat.GL41) {
            cir.setReturnValue(Math.max(cir.getReturnValueI(), GlStateManager._getInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)));
        }
    }
}
