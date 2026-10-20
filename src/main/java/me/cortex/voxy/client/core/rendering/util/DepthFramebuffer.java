package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.system.MemoryStack;

import me.cortex.voxy.client.core.gl.GlCompat;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public class DepthFramebuffer {
    private final int depthType;
    private GlTexture depthBuffer;
    public final GlFramebuffer framebuffer = new GlFramebuffer();

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
    }

    public boolean resize(int width, int height) {
        if (this.depthBuffer == null || this.depthBuffer.getWidth() != width || this.depthBuffer.getHeight() != height) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }
            this.depthBuffer = new GlTexture().store(this.depthType, 1, width, height);
            //glTextureParameteri(this.depthBuffer.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            //glTextureParameteri(this.depthBuffer.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            this.framebuffer.bind(this.getDepthAttachmentType(), this.depthBuffer).verify();
            return true;
        }
        return false;
    }

    public int getDepthAttachmentType() {
        return this.depthType == GL_DEPTH24_STENCIL8?GL_DEPTH_STENCIL_ATTACHMENT: GL_DEPTH_ATTACHMENT;
    }

    public void clear(float depth) {
        GlCompat.clearFramebufferDepth(this.framebuffer.id, depth);
    }

    public void clearStencil(int to) {
        GlCompat.clearFramebufferStencil(this.framebuffer.id, to);
    }

    public GlTexture getDepthTex() {
        return this.depthBuffer;
    }

    public void free() {
        this.framebuffer.free();
        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
    }

    public int getFormat() {
        return this.depthType;
    }
}
