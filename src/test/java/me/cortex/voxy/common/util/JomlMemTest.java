package me.cortex.voxy.common.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JomlMemTest {
    @Test
    void matrixLayoutMatchesJomlBufferLayout() {
        var m = new Matrix4f().perspective(1.2f, 1.7f, 0.1f, 1000f).rotateY(0.3f).translate(1, 2, 3);
        long a = MemoryUtil.nmemAlloc(64);
        var expected = MemoryUtil.memAllocFloat(16);
        try {
            JomlMem.put(m, a);
            m.get(expected);//column major, the layout getToAddress writes
            for (int i = 0; i < 16; i++) {
                assertEquals(expected.get(i), MemoryUtil.memGetFloat(a + i*4L));
            }
        } finally {
            MemoryUtil.nmemFree(a);
            MemoryUtil.memFree(expected);
        }
    }

    @Test
    void vectorsRoundTrip() {
        long a = MemoryUtil.nmemAlloc(16);
        try {
            JomlMem.put(new Vector4f(1, 2, 3, 4), a);
            var v = new Vector4f();
            JomlMem.get(v, a);
            assertEquals(new Vector4f(1, 2, 3, 4), v);
            var v3 = new Vector3f();
            JomlMem.get(v3, a + 4);
            assertEquals(new Vector3f(2, 3, 4), v3);
            JomlMem.put(new Vector3i(-1, 7, 9), a);
            assertEquals(-1, MemoryUtil.memGetInt(a));
            assertEquals(9, MemoryUtil.memGetInt(a + 8));
        } finally {
            MemoryUtil.nmemFree(a);
        }
    }
}
