package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL45C.*;

public class GlRenderBuffer extends TrackedObject {
    public final int id;

    public GlRenderBuffer(int format, int width, int height) {
        if (GlCompat.GL41) {
            this.id = glGenRenderbuffers();
            int prev = glGetInteger(GL_RENDERBUFFER_BINDING);
            glBindRenderbuffer(GL_RENDERBUFFER, this.id);
            glRenderbufferStorage(GL_RENDERBUFFER, format, width, height);
            glBindRenderbuffer(GL_RENDERBUFFER, prev);
        } else {
            this.id = glCreateRenderbuffers();
            glNamedRenderbufferStorage(this.id, format, width, height);
        }
    }

    @Override
    public void free() {
        super.free0();
        glDeleteRenderbuffers(this.id);
    }
}
