package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL45C.*;

public class GlTexture extends TrackedObject {
    public final int id;
    private final int type;
    private int format;
    private int width;
    private int height;
    private int levels;
    private boolean hasAllocated;

    private static int COUNT;
    private static long ESTIMATED_TOTAL_SIZE;

    public GlTexture() {
        this(GL_TEXTURE_2D);
    }

    public GlTexture(int type) {
        this.id = GlCompat.GL41?glGenTextures():glCreateTextures(type);
        this.type = type;
        COUNT++;
    }

    private GlTexture(int type, boolean useGenTypes) {
        if (useGenTypes) {
            this.id = glGenTextures();
        } else {
            this.id = glCreateTextures(type);
        }
        this.type = type;
        COUNT++;
    }

    public GlTexture store(int format, int levels, int width, int height) {
        if (this.hasAllocated) {
            throw new IllegalStateException("Texture already allocated");
        }
        this.hasAllocated = true;

        this.format = format;
        if (this.type == GL_TEXTURE_2D) {
            if (GlCompat.GL41) {
                int prev = glGetInteger(GL_TEXTURE_BINDING_2D);
                glBindTexture(GL_TEXTURE_2D, this.id);
                org.lwjgl.opengl.ARBTextureStorage.glTexStorage2D(GL_TEXTURE_2D, levels, format, width, height);
                glBindTexture(GL_TEXTURE_2D, prev);
            } else {
                glTextureStorage2D(this.id, levels, format, width, height);
            }
            this.width = width;
            this.height = height;
            this.levels = levels;
        } else {
            throw new IllegalStateException("Unknown texture type");
        }
        ESTIMATED_TOTAL_SIZE += this.getEstimatedSize();
        return this;
    }

    public GlTexture createView() {
        this.assertAllocated();
        if (GlCompat.GL41) {
            throw new UnsupportedOperationException("Texture views are not available on the GL 4.1 backend");
        }
        var view = new GlTexture(this.type, true);
        glTextureView(view.id, this.type, this.id, this.format, 0, 1, 0, 1);
        return view;
    }

    @Override
    public void free() {
        if (this.hasAllocated) {
            ESTIMATED_TOTAL_SIZE -= this.getEstimatedSize();
        }
        COUNT--;
        this.hasAllocated = false;
        super.free0();
        glDeleteTextures(this.id);
    }

    public GlTexture name(String name) {
        this.assertAllocated();
        return GlDebug.name(name, this);
    }

    public int getWidth() {
        this.assertAllocated();
        return this.width;
    }

    public int getHeight() {
        this.assertAllocated();
        return this.height;
    }

    public int getLevels() {
        this.assertAllocated();
        return this.levels;
    }

    public int getFormat() {
        this.assertAllocated();
        return this.format;
    }

    public int getPixelTransferFormat() {
        this.assertAllocated();
        return switch (this.format) {
            case GL_RGBA8 -> GL_RGBA;
            case GL_RG16F -> GL_RG;
            case GL_R32UI -> GL_RED_INTEGER;
            case GL_R32F -> GL_RED;
            case GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT32 -> GL_DEPTH_COMPONENT;
            case GL_DEPTH24_STENCIL8 -> GL_DEPTH_STENCIL;
            default -> throw new IllegalStateException("Unknown format");
        };
    }

    private long getEstimatedSize() {
        this.assertAllocated();
        long elemSize = switch (this.format) {
            case GL_R32UI, GL_RGBA8, GL_DEPTH24_STENCIL8, GL_R32F, GL_RG16F -> 4;
            case GL_DEPTH_COMPONENT24 -> 4;//TODO: check this is right????
            case GL_DEPTH_COMPONENT32F -> 4;
            case GL_DEPTH_COMPONENT32 -> 4;

            default -> throw new IllegalStateException("Unknown element size");
        };

        long size = 0;
        for (int lvl = 0; lvl < this.levels; lvl++) {
            size += Math.max((((long)this.width)>>lvl), 1) * Math.max((((long)this.height)>>lvl), 1) * elemSize;
        }
        return size;
    }

    public void assertAllocated() {
        if (!this.hasAllocated) {
            throw new IllegalStateException("Texture not yet allocated");
        }
    }

    public GlTexture zero() {
        this.assertAllocated();
        int type = switch (this.format) {
            case GL_R32UI -> GL_UNSIGNED_INT;
            case GL_RGBA8 -> GL_INT;
            case GL_R32F,GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT32, GL_RG16F -> GL_FLOAT;
            case GL_DEPTH24_STENCIL8 -> GL_UNSIGNED_INT_24_8;
            case GL_DEPTH32F_STENCIL8 -> GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            default -> throw new IllegalStateException("Unknown format");
        };
        if (GlCompat.GL41) {
            this.zero41(type);
            return this;
        }
        for (int lvl = 0; lvl < this.levels; lvl++) {
            nglClearTexImage(this.id, lvl, this.getPixelTransferFormat(), type, 0);
        }
        return this;
    }

    //No clear texture on GL 4.1: upload zeros, in row strips so large textures (the model atlas is 12288x8192) do not need a
    // full size cpu buffer. The transfer type decides how many bytes the driver reads per pixel, RGBA8 uses unsigned bytes
    private void zero41(int type) {
        if (this.format == GL_RGBA8) {
            type = GL_UNSIGNED_BYTE;
        }
        int bytesPerPixel = switch (this.format) {
            case GL_RG16F, GL_DEPTH32F_STENCIL8 -> 8;
            default -> 4;
        };
        final long STRIP_BYTES = 16L<<20;
        int rows = (int) Math.max(1, Math.min(this.height, STRIP_BYTES/((long)this.width*bytesPerPixel)));
        long scratch = org.lwjgl.system.MemoryUtil.nmemCalloc(1, (long)this.width*rows*bytesPerPixel);
        int prev = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, this.id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        for (int lvl = 0; lvl < this.levels; lvl++) {
            int w = Math.max(1, this.width>>lvl);
            int h = Math.max(1, this.height>>lvl);
            for (int y = 0; y < h; y += rows) {
                nglTexSubImage2D(GL_TEXTURE_2D, lvl, 0, y, w, Math.min(rows, h - y), this.getPixelTransferFormat(), type, scratch);
            }
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glBindTexture(GL_TEXTURE_2D, prev);
        org.lwjgl.system.MemoryUtil.nmemFree(scratch);
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getEstimatedTotalSize() {
        return ESTIMATED_TOTAL_SIZE;
    }
}
