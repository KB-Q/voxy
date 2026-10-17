package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.backend.gl41.Gl41TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class DrawEntryBuilderTest {
    //translucent 5, double sided 2, down 3, up 4, north 0, south 6, west 7, east 8, starting at quad 1000
    private static final int[] COUNTS = {5, 2, 3, 4, 0, 6, 7, 8};

    @Test
    void cameraInsideSectionMergesAllFaces() {
        var mirror = new Gl41NodeMirror(16, 16);
        long p = pos(0, 10, 2, -4);
        setSection(mirror, 0, p, 1000, COUNTS);
        var builder = new DrawEntryBuilder();
        builder.build(IntArrayList.of(0), mirror, 10, 2, -4);

        assertEquals(0, builder.getOpaqueEntryCount(0));
        assertEquals(0, builder.getOpaqueEntryCount(1));
        assertArrayEquals(new int[]{1005, 16, hi(p), lo(p), 1021, 14, hi(p), lo(p)}, builder.opaqueEntries(2));
        assertArrayEquals(new int[]{1000, 5, hi(p), lo(p)}, builder.translucentEntries());
        assertEquals(30, builder.opaqueQuads);
    }

    @Test
    void facesPointingAwayAreSkipped() {
        var mirror = new Gl41NodeMirror(16, 16);
        long p = pos(0, 10, 2, -4);
        setSection(mirror, 0, p, 1000, COUNTS);
        var builder = new DrawEntryBuilder();
        //Camera one section above: relative y = -1, down faces (-y) are hidden, the merged run breaks at them
        builder.build(IntArrayList.of(0), mirror, 10, 3, -4);
        assertArrayEquals(new int[]{
                1005, 2, hi(p), lo(p),//double sided
                1010, 16, hi(p), lo(p),//up, south, west, east (north is empty)
                1026, 9, hi(p), lo(p)}, builder.opaqueEntries(2));
    }

    @Test
    void largeRangesUseLargeBuckets() {
        var mirror = new Gl41NodeMirror(16, 16);
        long p = pos(0, 0, 0, 0);
        setSection(mirror, 0, p, 0, 0, 2000+130+20, 0, 0, 0, 0, 0, 0);
        var builder = new DrawEntryBuilder();
        builder.build(IntArrayList.of(0), mirror, 0, 0, 0);
        //2150 = 1024 + 1024 (2048, the second bucket is used since the remaining 1126 is more than 3/4 of 1024) + 102 in a 128 bucket
        assertArrayEquals(new int[]{0, 1024, hi(p), lo(p), 1024, 1024, hi(p), lo(p)}, builder.opaqueEntries(0));
        assertArrayEquals(new int[]{2048, 102, hi(p), lo(p)}, builder.opaqueEntries(1));
        assertEquals(0, builder.getOpaqueEntryCount(2));
    }

    @Test
    void translucentsAreSortedFarToNear() {
        var mirror = new Gl41NodeMirror(16, 16);
        long near = pos(0, 1, 0, 0);
        long far = pos(0, 5, 0, 0);
        long farLod = pos(1, 2, 0, 0);//distance 2<<1 = 4
        setSection(mirror, 0, near, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        setSection(mirror, 1, far, 100, 70, 0, 0, 0, 0, 0, 0, 0);
        setSection(mirror, 2, farLod, 200, 1, 0, 0, 0, 0, 0, 0, 0);
        var builder = new DrawEntryBuilder();
        builder.build(IntArrayList.of(0, 1, 2), mirror, 0, 0, 0);
        assertArrayEquals(new int[]{
                100, 64, hi(far), lo(far),
                164, 6, hi(far), lo(far),
                200, 1, hi(farLod), lo(farLod),
                0, 3, hi(near), lo(near)}, builder.translucentEntries());
    }

    @Test
    void writeLaysOutBucketsInOrder() {
        var mirror = new Gl41NodeMirror(16, 16);
        setSection(mirror, 0, pos(0, 0, 0, 0), 0, 4, 1100, 0, 0, 0, 0, 0, 0);
        setSection(mirror, 1, pos(0, 0, 0, 1), 5000, 0, 10, 0, 0, 0, 0, 0, 0);
        var builder = new DrawEntryBuilder();
        builder.build(IntArrayList.of(0, 1), mirror, 0, 0, 0);
        int total = builder.getTotalEntryCount();
        assertEquals(builder.getTranslucentEntryOffset() + 1, total);
        long buffer = MemoryUtil.nmemAlloc(total*16L);
        try {
            builder.write(buffer);
            assertEquals(4, MemoryUtil.memGetInt(buffer));//first 1024 bucket entry starts after the translucent quads
            //The 16 bucket holds the 76 quad remainder of section 0 (5 entries) followed by section 1
            assertEquals(6, builder.getOpaqueEntryCount(2));
            assertEquals(1028, MemoryUtil.memGetInt(buffer + builder.getOpaqueEntryOffset(2)*16L));
            assertEquals(5000, MemoryUtil.memGetInt(buffer + (builder.getOpaqueEntryOffset(2)+5)*16L));
            assertEquals(0, MemoryUtil.memGetInt(buffer + builder.getTranslucentEntryOffset()*16L));
            assertEquals(4, MemoryUtil.memGetInt(buffer + builder.getTranslucentEntryOffset()*16L + 4));
        } finally {
            MemoryUtil.nmemFree(buffer);
        }
    }

    @Test
    void requestLimitCurve() {
        assertEquals(50, Gl41LodSelection.requestLimit(0));
        assertEquals(0, Gl41LodSelection.requestLimit(4000));
        assertEquals(13, Gl41LodSelection.requestLimit(2000));
    }
}
