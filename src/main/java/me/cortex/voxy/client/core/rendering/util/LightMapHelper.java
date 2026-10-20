package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlCompat;
import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL33.glBindSampler;

public class LightMapHelper {
    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        GlCompat.bindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return ((com.mojang.blaze3d.opengl.GlTexture)(Minecraft.getInstance().gameRenderer.lightTexture().getTextureView().texture())).glId();
    }
}
