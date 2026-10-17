package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, priority = 900)//We must execute before sodium
public class MixinFogRenderer {
    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", at = @At("TAIL"))
    private static void voxy$modifyFog(Camera camera, FogMode fogMode, float viewDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return;
        if (IVoxyRenderSystemHolder.getNullable() == null) return;

        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        boolean fogIsDamnClose = end < 10;

        if (fogMode == FogMode.FOG_SKY) {
            //Keep the sky fog independent of the vanilla render distance
            if (!fogIsDamnClose) {
                RenderSystem.setShaderFogStart(0);
                RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance);
            }
            return;
        }

        //1.20.1 has a single terrain fog, split it into environmental fog (fluids, effects, thick fog) and render distance fog
        float[] colour = RenderSystem.getShaderFogColor();
        boolean environmental = thickFog || camera.getFluidInCamera() != FogType.NONE || voxy$hasVisionEffect(camera.getEntity());
        if (environmental) {
            FogParameters.capture(new FogParameters(start, end, colour[0], colour[1], colour[2], colour[3]));
        } else {
            FogParameters.capture(new FogParameters(FogParameters.NO_FOG, FogParameters.NO_FOG, colour[0], colour[1], colour[2], colour[3]));
        }

        //Render distance fog is always removed, environmental fog only if the fog mode replaces it
        if (!environmental || (VoxyConfig.CONFIG.getFogMode().removesVanillaEnvFog && !fogIsDamnClose)) {
            RenderSystem.setShaderFogStart(FogParameters.NO_FOG);
            RenderSystem.setShaderFogEnd(FogParameters.NO_FOG);
        }
    }

    @Unique
    private static boolean voxy$hasVisionEffect(Entity entity) {
        return entity instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }
}
