package me.cortex.voxy.common.util;

import org.joml.*;
import org.lwjgl.system.MemoryUtil;

//Replacement for JOML's getToAddress/setFromAddress. Those always use JOML's Unsafe backend, which refuses to initialise
// when the JVM lays out the matrix fields differently than it expects (seen with JOML 1.10.5, as shipped with
// Minecraft 1.20.1, on Java 17 arm64), they then throw NoClassDefFoundError. Same memory layout: matrices column major
public final class JomlMem {
    private JomlMem() {}

    public static void put(Matrix4fc m, long a) {
        MemoryUtil.memPutFloat(a,      m.m00()); MemoryUtil.memPutFloat(a+4,  m.m01()); MemoryUtil.memPutFloat(a+8,  m.m02()); MemoryUtil.memPutFloat(a+12, m.m03());
        MemoryUtil.memPutFloat(a+16,   m.m10()); MemoryUtil.memPutFloat(a+20, m.m11()); MemoryUtil.memPutFloat(a+24, m.m12()); MemoryUtil.memPutFloat(a+28, m.m13());
        MemoryUtil.memPutFloat(a+32,   m.m20()); MemoryUtil.memPutFloat(a+36, m.m21()); MemoryUtil.memPutFloat(a+40, m.m22()); MemoryUtil.memPutFloat(a+44, m.m23());
        MemoryUtil.memPutFloat(a+48,   m.m30()); MemoryUtil.memPutFloat(a+52, m.m31()); MemoryUtil.memPutFloat(a+56, m.m32()); MemoryUtil.memPutFloat(a+60, m.m33());
    }

    public static void put(Vector2fc v, long a) {
        MemoryUtil.memPutFloat(a, v.x()); MemoryUtil.memPutFloat(a+4, v.y());
    }

    public static void put(Vector3fc v, long a) {
        MemoryUtil.memPutFloat(a, v.x()); MemoryUtil.memPutFloat(a+4, v.y()); MemoryUtil.memPutFloat(a+8, v.z());
    }

    public static void put(Vector4fc v, long a) {
        MemoryUtil.memPutFloat(a, v.x()); MemoryUtil.memPutFloat(a+4, v.y()); MemoryUtil.memPutFloat(a+8, v.z()); MemoryUtil.memPutFloat(a+12, v.w());
    }

    public static void put(Vector2ic v, long a) {
        MemoryUtil.memPutInt(a, v.x()); MemoryUtil.memPutInt(a+4, v.y());
    }

    public static void put(Vector3ic v, long a) {
        MemoryUtil.memPutInt(a, v.x()); MemoryUtil.memPutInt(a+4, v.y()); MemoryUtil.memPutInt(a+8, v.z());
    }

    public static void get(Vector3f v, long a) {
        v.set(MemoryUtil.memGetFloat(a), MemoryUtil.memGetFloat(a+4), MemoryUtil.memGetFloat(a+8));
    }

    public static void get(Vector4f v, long a) {
        v.set(MemoryUtil.memGetFloat(a), MemoryUtil.memGetFloat(a+4), MemoryUtil.memGetFloat(a+8), MemoryUtil.memGetFloat(a+12));
    }
}
