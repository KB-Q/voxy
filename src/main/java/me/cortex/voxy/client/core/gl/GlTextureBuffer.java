package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL31C.*;

//A texture buffer object over a GlBuffer, the GL 4.1 replacement for reading buffers from shaders (no SSBOs)
public class GlTextureBuffer extends TrackedObject {
    public final int id;
    private final GlBuffer buffer;
    private final int internalFormat;

    public GlTextureBuffer(GlBuffer buffer, int internalFormat) {
        this.buffer = buffer;
        this.internalFormat = internalFormat;
        this.id = glGenTextures();
        int prev = glGetInteger(GL_TEXTURE_BINDING_BUFFER);
        glBindTexture(GL_TEXTURE_BUFFER, this.id);
        glTexBuffer(GL_TEXTURE_BUFFER, internalFormat, buffer.id);
        glBindTexture(GL_TEXTURE_BUFFER, prev);
    }

    public GlBuffer getBuffer() {
        return this.buffer;
    }

    public void bind(int unit) {
        GlCompat.bindTextureUnit(unit, GL_TEXTURE_BUFFER, this.id);
    }

    @Override
    public void free() {
        super.free0();
        glDeleteTextures(this.id);
    }
}
