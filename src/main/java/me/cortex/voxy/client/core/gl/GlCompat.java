package me.cortex.voxy.client.core.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameteri;
import static org.lwjgl.opengl.GL45C.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL45C.nglClearNamedFramebufferiv;
import static org.lwjgl.opengl.GL45C.glClearNamedFramebufferfi;

//Small helpers for state that is set with direct state access on GL 4.5+ and with bind-to-edit on the GL 4.1 backend
// Active texture and 2D texture bindings go through GlStateManager on GL 4.1 so that minecraft's binding cache stays valid
public final class GlCompat {
    public static final boolean GL41 = Capabilities.INSTANCE.backend == Capabilities.Backend.GL41;

    private GlCompat() {}

    public static void bindTextureUnit(int unit, int texture) {
        bindTextureUnit(unit, GL_TEXTURE_2D, texture);
    }

    public static void bindTextureUnit(int unit, int target, int texture) {
        if (!GL41) {
            glBindTextureUnit(unit, texture);
            return;
        }
        GlStateManager._activeTexture(GL_TEXTURE0 + unit);
        if (target == GL_TEXTURE_2D) {
            GlStateManager._bindTexture(texture);
        } else {
            glBindTexture(target, texture);
        }
    }

    public static void textureParameteri(int texture, int target, int pname, int value) {
        if (!GL41) {
            glTextureParameteri(texture, pname, value);
            return;
        }
        int prev = glGetInteger(bindingQuery(target));
        glBindTexture(target, texture);
        glTexParameteri(target, pname, value);
        glBindTexture(target, prev);
    }

    public static void clearFramebufferDepth(int framebuffer, float depth) {
        if (!GL41) {
            try (var stack = MemoryStack.stackPush()) {
                nglClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, stack.nfloat(depth));
            }
            return;
        }
        int prev = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        try (var stack = MemoryStack.stackPush()) {
            nglClearBufferfv(GL_DEPTH, 0, stack.nfloat(depth));
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prev);
    }

    public static void clearFramebufferStencil(int framebuffer, int stencil) {
        if (!GL41) {
            try (var stack = MemoryStack.stackPush()) {
                nglClearNamedFramebufferiv(framebuffer, GL_STENCIL, 0, stack.nint(stencil));
            }
            return;
        }
        int prev = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        try (var stack = MemoryStack.stackPush()) {
            nglClearBufferiv(GL_STENCIL, 0, stack.nint(stencil));
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prev);
    }

    public static void clearFramebufferDepthStencil(int framebuffer, float depth, int stencil) {
        if (!GL41) {
            glClearNamedFramebufferfi(framebuffer, GL_DEPTH_STENCIL, 0, depth, stencil);
            return;
        }
        int prev = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glClearBufferfi(GL_DEPTH_STENCIL, 0, depth, stencil);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prev);
    }

    public static void blitFramebuffer(int src, int dst, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (!GL41) {
            org.lwjgl.opengl.GL45C.glBlitNamedFramebuffer(src, dst, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            return;
        }
        int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, src);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst);
        glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
    }

    public static int getTextureLevelParameteri(int texture, int pname) {
        if (!GL41) {
            return org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(texture, 0, pname);
        }
        int prev = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        int value = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, pname);
        glBindTexture(GL_TEXTURE_2D, prev);
        return value;
    }

    static int bindingQuery(int target) {
        return switch (target) {
            case GL_TEXTURE_2D -> GL_TEXTURE_BINDING_2D;
            case org.lwjgl.opengl.GL31C.GL_TEXTURE_BUFFER -> org.lwjgl.opengl.GL31C.GL_TEXTURE_BINDING_BUFFER;
            default -> throw new IllegalArgumentException("Unknown texture target " + target);
        };
    }
}
