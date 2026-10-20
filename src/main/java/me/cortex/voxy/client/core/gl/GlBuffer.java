package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class GlBuffer extends TrackedObject {
    public final int id;
    private final long size;
    private final int flags;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public GlBuffer(long size) {
        this(size, 0);
    }
    public GlBuffer(long size, boolean zero) {
        this(size, 0, zero);
    }

    public GlBuffer(long size, int flags) {
        this(size, flags, true);
    }

    public GlBuffer(long size, int flags, boolean zero) {
        this.flags = flags;
        this.size = size;
        if (GlCompat.GL41) {
            if ((flags&GL_SPARSE_STORAGE_BIT_ARB)!=0) {
                throw new IllegalArgumentException("Sparse buffers are not supported on the GL 4.1 backend");
            }
            this.id = glGenBuffers();
            glBindBuffer(GL_COPY_WRITE_BUFFER, this.id);
            nglBufferData(GL_COPY_WRITE_BUFFER, size, 0, GL_STATIC_DRAW);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        } else {
            this.id = glCreateBuffers();
            glNamedBufferStorage(this.id, size, flags);
        }
        if ((flags&GL_SPARSE_STORAGE_BIT_ARB)==0 && zero) {
            this.zero();
        }

        COUNT++;
        TOTAL_SIZE += size;
    }

    @Override
    public void free() {
        this.free0();
        glDeleteBuffers(this.id);

        COUNT--;
        TOTAL_SIZE -= this.size;
    }

    public boolean isSparse() {
        return (this.flags&GL_SPARSE_STORAGE_BIT_ARB)!=0;
    }

    public long size() {
        return this.size;
    }

    public GlBuffer zero() {
        if (GlCompat.GL41) {
            return this.fillRange41(0, this.size, 0);
        }
        nglClearNamedBufferData(this.id, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
        return this;
    }

    public GlBuffer zeroRange(long offset, long size) {
        if (GlCompat.GL41) {
            return this.fillRange41(offset, size, 0);
        }
        nglClearNamedBufferSubData(this.id, GL_R8UI, offset, size, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
        return this;
    }

    public GlBuffer fill(int data) {
        if (GlCompat.GL41) {
            return this.fillRange41(0, this.size, data);
        }
        //Clear unpack values
        //Fixed in mesa commit a5c3c452
        glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);

        MemoryUtil.memPutInt(SCRATCH, data);
        nglClearNamedBufferData(this.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);
        return this;
    }

    //Writes data into the buffer, used for uploads on the GL 4.1 backend (glBufferSubData does not stall on in use buffers)
    public void subData(long offset, long ptr, long size) {
        if (offset < 0 || offset + size > this.size) {
            throw new IndexOutOfBoundsException("Write of " + size + " at " + offset + " outside buffer of size " + this.size);
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, this.id);
        nglBufferSubData(GL_COPY_WRITE_BUFFER, offset, size, ptr);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    }

    //No clear buffer commands on GL 4.1, so write a filled block repeatedly
    private static final long FILL_BLOCK_SIZE = 1<<22;
    private static long FILL_BLOCK = 0;
    private static int FILL_BLOCK_VALUE = 0;
    private GlBuffer fillRange41(long offset, long size, int value) {
        if (FILL_BLOCK == 0) {
            FILL_BLOCK = MemoryUtil.nmemAlloc(FILL_BLOCK_SIZE);
            FILL_BLOCK_VALUE = ~value;
        }
        if (FILL_BLOCK_VALUE != value) {
            for (long i = 0; i < FILL_BLOCK_SIZE; i += 4) {
                MemoryUtil.memPutInt(FILL_BLOCK + i, value);
            }
            FILL_BLOCK_VALUE = value;
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, this.id);
        for (long done = 0; done < size; done += FILL_BLOCK_SIZE) {
            nglBufferSubData(GL_COPY_WRITE_BUFFER, offset + done, Math.min(FILL_BLOCK_SIZE, size - done), FILL_BLOCK);
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        return this;
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }

    public GlBuffer name(String name) {
        return GlDebug.name(name, this);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(4);
}
