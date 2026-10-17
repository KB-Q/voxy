package me.cortex.voxy.client.core.rendering.backend.gl41;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//Builds node and section metadata state directly in a mirror, using the same encodings as NodeStore and pos_util.glsl
final class Gl41TestUtil {
    private Gl41TestUtil() {}

    //WorldEngine.getWorldSectionId
    static long pos(int lvl, int x, int y, int z) {
        return ((long)lvl<<60)|((long)(y&0xFF)<<52)|((long)(z&((1<<24)-1))<<28)|((long)(x&((1<<24)-1))<<4);
    }

    static int hi(long pos) {
        return (int) (pos>>>32);
    }

    static int lo(long pos) {
        return (int) pos;
    }

    static void setNode(Gl41NodeMirror mirror, int node, long pos, int mesh, int childPtr, int childCount, boolean requested) {
        int flags = (requested?1:0) | ((childCount-1)&7)<<2;
        int b = node*4;
        mirror.nodes[b] = hi(pos);
        mirror.nodes[b+1] = lo(pos);
        mirror.nodes[b+2] = (mesh&0xFFFFFF) | ((flags&0xFF)<<24);
        mirror.nodes[b+3] = (childPtr&0xFFFFFF) | (((flags>>8)&0xFF)<<24);
    }

    //counts: translucent, double sided, down, up, north, south, west, east
    static void setSection(Gl41NodeMirror mirror, int section, long pos, int quadStart, int... counts) {
        int b = section*8;
        mirror.sections[b] = hi(pos);
        mirror.sections[b+1] = lo(pos);
        mirror.sections[b+2] = 0;
        mirror.sections[b+3] = quadStart;
        for (int i = 0; i < 4; i++) {
            mirror.sections[b+4+i] = (counts[i*2]&0xFFFF) | (counts[i*2+1]<<16);
        }
    }

    //Writes a scatter buffer in the AsyncNodeManager.SyncResults layout: chunks of 4 locations followed by 4 uvec4 values
    static long scatter(int[] locations, int[][] values) {
        int chunks = (locations.length+3)/4;
        long buffer = MemoryUtil.nmemCalloc(chunks, 80);
        for (int i = 0; i < locations.length; i++) {
            long chunk = buffer + (i/4)*80L;
            MemoryUtil.memPutInt(chunk + (i&3)*4L, locations[i]);
            for (int j = 0; j < 4; j++) {
                MemoryUtil.memPutInt(chunk + 16 + (i&3)*16L + j*4L, values[i][j]);
            }
        }
        return buffer;
    }

    static int[] range(int[] arr, int from, int to) {
        return Arrays.copyOfRange(arr, from, to);
    }
}
