package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gl.GlCompat;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlTextureBuffer;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.GL_R32UI;
import static org.lwjgl.opengl.GL30C.GL_RGBA32UI;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    //GL 4.1: texture buffer views since there are no SSBOs, a model is 4 RGBA32UI texels
    private final GlTextureBuffer modelTextureBuffer;
    private final GlTextureBuffer modelColourTextureBuffer;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        if (GlCompat.GL41) {
            this.modelTextureBuffer = new GlTextureBuffer(this.modelBuffer, GL_RGBA32UI);
            this.modelColourTextureBuffer = new GlTextureBuffer(this.modelColourBuffer, GL_R32UI);
        } else {
            this.modelTextureBuffer = null;
            this.modelColourTextureBuffer = null;
        }
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png")))
                .mipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    public void free() {
        if (this.modelTextureBuffer != null) {
            this.modelTextureBuffer.free();
            this.modelColourTextureBuffer.free();
        }
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    //On GL 4.1 the model and colour indices are texture units instead of SSBO bindings
    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        if (GlCompat.GL41) {
            this.modelTextureBuffer.bind(modelBindingIndex);
            this.modelColourTextureBuffer.bind(colourBindingIndex);
        } else {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        }
        GlCompat.bindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
