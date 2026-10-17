package me.cortex.voxy.client.core.rendering.backend.gl41;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//CPU copies of the node buffer and the section metadata buffer, the GL 4.1 backend traverses these instead of the gpu buffers
// they receive exactly the same scatter writes the gpu buffers get on the GL 4.6 backend
public final class Gl41NodeMirror {
    //Node layout (4 ints): x,y packed lod position, z mesh id (low 24 bits) + flags, w child pointer (low 24 bits) + flags
    public static final int NULL_NODE = (1<<24)-1;
    public static final int EMPTY_QUEUE_ID = (1<<24)-2;
    public static final int NULL_MESH = (1<<24)-1;
    public static final int EMPTY_MESH = (1<<24)-2;

    final int[] nodes;
    //Section metadata layout (8 ints): posHi, posLo, aabb, quadStart, then 4 ints of 16 bit quad counts
    final int[] sections;

    public Gl41NodeMirror(int maxNodes, int maxSections) {
        this.nodes = new int[maxNodes*4];
        Arrays.fill(this.nodes, -1);//Matches the gpu node buffer being filled with -1
        this.sections = new int[maxSections*8];
    }

    //Scatter write buffer layout: chunks of 5 uvec4, the first holds 4 write locations (in uvec4 units), followed by the 4 values
    // a location with bit 31 set targets the section metadata buffer
    public void applyScatter(long buffer, int count) {
        for (int i = 0; i < count; i++) {
            long chunk = buffer + (i>>2)*80L;
            int location = MemoryUtil.memGetInt(chunk + (i&3)*4L);
            long value = chunk + 16L + (i&3)*16L;
            int[] target;
            int index;
            if ((location&(1<<31)) != 0) {
                target = this.sections;
                index = (location&0x7FFFFFFF)*4;
            } else {
                target = this.nodes;
                index = location*4;
            }
            target[index] = MemoryUtil.memGetInt(value);
            target[index+1] = MemoryUtil.memGetInt(value+4);
            target[index+2] = MemoryUtil.memGetInt(value+8);
            target[index+3] = MemoryUtil.memGetInt(value+12);
        }
    }

    public int nodeCapacity() {
        return this.nodes.length>>2;
    }

    //---- node accessors, mirror node.glsl

    public static int lodLevel(int posX) {
        return posX>>>28;
    }

    public boolean isAllocated(int node) {
        int b = node*4;
        return !(this.nodes[b] == -1 && this.nodes[b+1] == -1 && this.nodes[b+2] == -1 && this.nodes[b+3] == -1);
    }

    public int posX(int node) { return this.nodes[node*4]; }
    public int posY(int node) { return this.nodes[node*4+1]; }
    public int mesh(int node) { return this.nodes[node*4+2]&0xFFFFFF; }
    public int childPtr(int node) { return this.nodes[node*4+3]&0xFFFFFF; }
    public int flags(int node) { return ((this.nodes[node*4+2]>>>24)&0xFF) | (((this.nodes[node*4+3]>>>24)&0xFF)<<8); }
    public int childCount(int node) { return ((this.flags(node)>>2)&7)+1; }
    public boolean hasRequested(int node) { return (this.flags(node)&1) != 0; }

    public void markRequested(int node) {
        this.nodes[node*4+2] |= 1<<24;
    }

    //---- section metadata accessors, mirror section.glsl

    public int sectionPosX(int section) { return this.sections[section*8]; }
    public int sectionPosY(int section) { return this.sections[section*8+1]; }
    public int sectionAabb(int section) { return this.sections[section*8+2]; }
    public int sectionQuadStart(int section) { return this.sections[section*8+3]; }
    //count index: 0 translucent, 1 double sided, 2 down, 3 up, 4 north, 5 south, 6 west, 7 east
    public int sectionCount(int section, int index) {
        int packed = this.sections[section*8+4+(index>>1)];
        return (index&1)==0?(packed&0xFFFF):(packed>>>16);
    }

    //---- position decoding, mirror pos_util.glsl

    public static int decodeY(int x) {
        return (x<<4)>>24;
    }

    public static int decodeX(int y) {
        return (y<<4)>>8;
    }

    public static int decodeZ(int x, int y) {
        int z = (x&((1<<20)-1))<<4;
        z |= y>>>28;
        z <<= 8;
        z >>= 8;
        return z;
    }
}
