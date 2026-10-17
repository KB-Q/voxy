package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//Turns the sections selected by the traversal into draw entries for the GL 4.1 renderer (the cpu side of cmdgen.comp + buildtranslucents.comp)
// an entry is a uvec4: quad start, quad count, section position (2 words). Each instance of an instanced draw is one entry and
// the instance draws a fixed number of quads (the bucket size), quads past the entry count are discarded in the vertex shader.
// Opaque ranges go into 3 bucket sizes to keep both the entry count and the discarded quads low,
// translucent ranges use a single bucket so one draw keeps the far to near order
public final class DrawEntryBuilder {
    public static final int[] OPAQUE_BUCKETS = {1024, 128, 16};
    public static final int TRANSLUCENT_BUCKET = 64;
    private static final int DISTANCE_BUCKETS = 1024;//Same as TRANSLUCENT_WRITE_BASE in cmdgen.comp

    private final int[][] opaque = new int[OPAQUE_BUCKETS.length][];
    private final int[] opaqueCount = new int[OPAQUE_BUCKETS.length];

    //Translucent ranges before sorting: key, quad start, quad count, posX, posY
    private int[] translucentRanges = new int[5*1024];
    private int translucentRangeCount;
    private final int[] distanceCounts = new int[DISTANCE_BUCKETS];
    private int[] translucent = new int[4*1024];
    private int translucentCount;

    //Sections ordered near to far so opaque draws can reject hidden fragments with the depth test
    private int[] sectionOrder = new int[1024];
    private int[] sectionKeys = new int[1024];
    private final int[] orderCounts = new int[DISTANCE_BUCKETS];

    public long opaqueQuads;
    public long translucentQuads;

    public DrawEntryBuilder() {
        for (int i = 0; i < this.opaque.length; i++) {
            this.opaque[i] = new int[4*1024];
        }
    }

    //camX/Y/Z is the camera position in level 0 section units (Viewport.section)
    public void build(IntArrayList sections, Gl41NodeMirror mirror, int camX, int camY, int camZ) {
        Arrays.fill(this.opaqueCount, 0);
        this.translucentRangeCount = 0;
        this.translucentCount = 0;
        this.opaqueQuads = 0;
        this.translucentQuads = 0;

        int[] meta = mirror.sections;
        this.sortNearToFar(sections, meta, camX, camY, camZ);
        for (int i = 0; i < sections.size(); i++) {
            int base = this.sectionOrder[i]*8;
            int posX = meta[base];
            int posY = meta[base+1];
            int ptr = meta[base+3];
            int lod = Gl41NodeMirror.lodLevel(posX);
            int rx = Gl41NodeMirror.decodeX(posY) - (camX>>lod);
            int ry = Gl41NodeMirror.decodeY(posX) - (camY>>lod);
            int rz = Gl41NodeMirror.decodeZ(posX, posY) - (camZ>>lod);

            int countsA = meta[base+4];//translucent | doubleSided<<16
            int countsB = meta[base+5];//down | up<<16
            int countsC = meta[base+6];//north | south<<16
            int countsD = meta[base+7];//west | east<<16

            int translucentCount = countsA&0xFFFF;
            if (translucentCount != 0) {
                int distance = Math.min((Math.abs(rx)+Math.abs(ry)+Math.abs(rz))<<lod, DISTANCE_BUCKETS-1);
                this.addTranslucentRange(distance, ptr, translucentCount, posX, posY);
            }
            ptr += translucentCount;

            //Opaque groups in memory order, faces pointing away from the camera are skipped (same mask as cmdgen.comp),
            // adjacent visible groups are merged into one range
            int runStart = -1;
            int runEnd = -1;
            for (int g = 0; g < 7; g++) {
                int count;
                boolean visible;
                switch (g) {
                    case 0 -> {count = countsA>>>16;      visible = true;}//double sided
                    case 1 -> {count = countsB&0xFFFF;    visible = ry > -1;}//down
                    case 2 -> {count = countsB>>>16;      visible = ry < 1;}//up
                    case 3 -> {count = countsC&0xFFFF;    visible = rz > -1;}//north
                    case 4 -> {count = countsC>>>16;      visible = rz < 1;}//south
                    case 5 -> {count = countsD&0xFFFF;    visible = rx > -1;}//west
                    default -> {count = countsD>>>16;     visible = rx < 1;}//east
                }
                if (count == 0) continue;
                if (visible) {
                    if (runStart == -1) runStart = ptr;
                    runEnd = ptr + count;
                } else if (runStart != -1) {
                    this.addOpaqueRange(runStart, runEnd - runStart, posX, posY);
                    runStart = -1;
                }
                ptr += count;
            }
            if (runStart != -1) {
                this.addOpaqueRange(runStart, runEnd - runStart, posX, posY);
            }
        }

        this.sortTranslucents();
    }

    //Counting sort of the sections on the same distance metric as the translucents (in level 0 section units, clamped)
    private void sortNearToFar(IntArrayList sections, int[] meta, int camX, int camY, int camZ) {
        int n = sections.size();
        if (this.sectionOrder.length < n) {
            this.sectionOrder = new int[n*2];
            this.sectionKeys = new int[n*2];
        }
        Arrays.fill(this.orderCounts, 0);
        for (int i = 0; i < n; i++) {
            int base = sections.getInt(i)*8;
            int posX = meta[base], posY = meta[base+1];
            int lod = Gl41NodeMirror.lodLevel(posX);
            int dx = Math.abs(Gl41NodeMirror.decodeX(posY) - (camX>>lod));
            int dy = Math.abs(Gl41NodeMirror.decodeY(posX) - (camY>>lod));
            int dz = Math.abs(Gl41NodeMirror.decodeZ(posX, posY) - (camZ>>lod));
            int key = Math.min((dx+dy+dz)<<lod, DISTANCE_BUCKETS-1);
            this.sectionKeys[i] = key;
            this.orderCounts[key]++;
        }
        int offset = 0;
        for (int d = 0; d < DISTANCE_BUCKETS; d++) {
            int c = this.orderCounts[d];
            this.orderCounts[d] = offset;
            offset += c;
        }
        for (int i = 0; i < n; i++) {
            this.sectionOrder[this.orderCounts[this.sectionKeys[i]]++] = sections.getInt(i);
        }
    }

    //Splits a range over the buckets, largest first. A bucket is only used partially filled when it is at least 3/4 full
    private void addOpaqueRange(int start, int count, int posX, int posY) {
        this.opaqueQuads += count;
        for (int b = 0; b < OPAQUE_BUCKETS.length; b++) {
            int size = OPAQUE_BUCKETS[b];
            int threshold = b == OPAQUE_BUCKETS.length-1 ? 0 : size - size/4;
            while (count > threshold) {
                int take = Math.min(count, size);
                this.addOpaque(b, start, take, posX, posY);
                start += take;
                count -= take;
            }
        }
    }

    private void addOpaque(int bucket, int start, int count, int posX, int posY) {
        int n = this.opaqueCount[bucket];
        int[] arr = this.opaque[bucket];
        if ((n+1)*4 > arr.length) {
            arr = this.opaque[bucket] = Arrays.copyOf(arr, arr.length*2);
        }
        int o = n*4;
        arr[o] = start;
        arr[o+1] = count;
        arr[o+2] = posX;
        arr[o+3] = posY;
        this.opaqueCount[bucket] = n+1;
    }

    private void addTranslucentRange(int distance, int start, int count, int posX, int posY) {
        this.translucentQuads += count;
        int n = this.translucentRangeCount;
        if ((n+1)*5 > this.translucentRanges.length) {
            this.translucentRanges = Arrays.copyOf(this.translucentRanges, this.translucentRanges.length*2);
        }
        int o = n*5;
        this.translucentRanges[o] = distance;
        this.translucentRanges[o+1] = start;
        this.translucentRanges[o+2] = count;
        this.translucentRanges[o+3] = posX;
        this.translucentRanges[o+4] = posY;
        this.translucentRangeCount = n+1;
    }

    //Counting sort on the distance, far to near, each range is split into TRANSLUCENT_BUCKET sized entries
    private void sortTranslucents() {
        Arrays.fill(this.distanceCounts, 0);
        int total = 0;
        for (int i = 0; i < this.translucentRangeCount; i++) {
            int entries = (this.translucentRanges[i*5+2] + TRANSLUCENT_BUCKET - 1)/TRANSLUCENT_BUCKET;
            this.distanceCounts[this.translucentRanges[i*5]] += entries;
            total += entries;
        }
        //Exclusive prefix sum from the furthest distance down
        int offset = 0;
        for (int d = DISTANCE_BUCKETS-1; d >= 0; d--) {
            int c = this.distanceCounts[d];
            this.distanceCounts[d] = offset;
            offset += c;
        }
        if (total*4 > this.translucent.length) {
            this.translucent = new int[Math.max(total*4, this.translucent.length*2)];
        }
        for (int i = 0; i < this.translucentRangeCount; i++) {
            int o = i*5;
            int start = this.translucentRanges[o+1];
            int count = this.translucentRanges[o+2];
            int slot = this.distanceCounts[this.translucentRanges[o]];
            while (count > 0) {
                int take = Math.min(count, TRANSLUCENT_BUCKET);
                int e = slot*4;
                this.translucent[e] = start;
                this.translucent[e+1] = take;
                this.translucent[e+2] = this.translucentRanges[o+3];
                this.translucent[e+3] = this.translucentRanges[o+4];
                slot++;
                start += take;
                count -= take;
            }
            this.distanceCounts[this.translucentRanges[o]] = slot;
        }
        this.translucentCount = total;
    }

    public int getOpaqueEntryCount(int bucket) {
        return this.opaqueCount[bucket];
    }

    public int getTranslucentEntryCount() {
        return this.translucentCount;
    }

    public int getTotalEntryCount() {
        int total = this.translucentCount;
        for (int c : this.opaqueCount) total += c;
        return total;
    }

    //Entry index of the first entry of the given opaque bucket in the written layout
    public int getOpaqueEntryOffset(int bucket) {
        int offset = 0;
        for (int b = 0; b < bucket; b++) offset += this.opaqueCount[b];
        return offset;
    }

    public int getTranslucentEntryOffset() {
        return this.getOpaqueEntryOffset(OPAQUE_BUCKETS.length);
    }

    //Writes all entries, opaque buckets in order followed by the translucent entries, address must hold getTotalEntryCount()*16 bytes
    public void write(long address) {
        for (int b = 0; b < OPAQUE_BUCKETS.length; b++) {
            int n = this.opaqueCount[b]*4;
            MemoryUtil.memIntBuffer(address, n).put(this.opaque[b], 0, n);
            address += n*4L;
        }
        int n = this.translucentCount*4;
        MemoryUtil.memIntBuffer(address, n).put(this.translucent, 0, n);
    }

    //Test access to the built entries
    int[] opaqueEntries(int bucket) {
        return Arrays.copyOf(this.opaque[bucket], this.opaqueCount[bucket]*4);
    }

    int[] translucentEntries() {
        return Arrays.copyOf(this.translucent, this.translucentCount*4);
    }
}
