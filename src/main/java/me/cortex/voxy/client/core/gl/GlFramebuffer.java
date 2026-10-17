package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL45C.*;

public class GlFramebuffer extends TrackedObject {
    public final int id;
    public GlFramebuffer() {
        this.id = GlCompat.GL41?glGenFramebuffers():glCreateFramebuffers();
    }

    //GL 4.1 has no named framebuffer functions, bind to edit and restore the previous binding
    private int bindForEdit() {
        int prev = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, this.id);
        return prev;
    }

    public GlFramebuffer bind(int attachment, GlTexture texture) {
        return this.bind(attachment, texture, 0);
    }

    public GlFramebuffer bind(int attachment, int texture) {
        return this.bind(attachment, texture, 0);
    }

    public GlFramebuffer bind(int attachment, int texture, int lvl) {
        if (GlCompat.GL41) {
            int prev = this.bindForEdit();
            glFramebufferTexture(GL_FRAMEBUFFER, attachment, texture, lvl);
            glBindFramebuffer(GL_FRAMEBUFFER, prev);
        } else {
            glNamedFramebufferTexture(this.id, attachment, texture, lvl);
        }
        return this;
    }

    public GlFramebuffer bind(int attachment, GlTexture texture, int lvl) {
        return this.bind(attachment, texture.id, lvl);
    }

    public GlFramebuffer bind(int attachment, GlRenderBuffer buffer) {
        if (GlCompat.GL41) {
            int prev = this.bindForEdit();
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, buffer.id);
            glBindFramebuffer(GL_FRAMEBUFFER, prev);
        } else {
            glNamedFramebufferRenderbuffer(this.id, attachment, GL_RENDERBUFFER, buffer.id);
        }
        return this;
    }

    public GlFramebuffer setDrawBuffers(int... buffers) {
        if (GlCompat.GL41) {
            int prev = this.bindForEdit();
            glDrawBuffers(buffers);
            glBindFramebuffer(GL_FRAMEBUFFER, prev);
        } else {
            glNamedFramebufferDrawBuffers(this.id, buffers);
        }
        return this;
    }

    public GlFramebuffer setDrawBuffer(int buffer) {
        if (GlCompat.GL41) {
            int prev = this.bindForEdit();
            glDrawBuffer(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, prev);
        } else {
            glNamedFramebufferDrawBuffer(this.id, buffer);
        }
        return this;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteFramebuffers(this.id);
    }

    public GlFramebuffer verify() {
        int code;
        if (GlCompat.GL41) {
            int prev = this.bindForEdit();
            code = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, prev);
        } else {
            code = glCheckNamedFramebufferStatus(this.id, GL_FRAMEBUFFER);
        }
        if (code != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer incomplete with error code: " + code);
        }
        return this;
    }


    public GlFramebuffer name(String name) {
        return GlDebug.name(name, this);
    }
}
