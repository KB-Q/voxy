package me.cortex.voxy.client.core.rendering.backend.gl41;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.backend.gl41.Gl41TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class Gl41NodeMirrorTest {
    @Test
    void scatterWritesNodesAndSplitSectionMetadata() {
        var mirror = new Gl41NodeMirror(16, 8);
        long p = pos(2, -5, 3, 7);
        //Two node writes and one 32 byte metadata write split into 2 uvec4 writes (location (id<<1)|bit31 and +1)
        int section = 3;
        int[] locations = {5, 9, (section<<1)|(1<<31), ((section<<1)+1)|(1<<31)};
        int[][] values = {
                {hi(p), lo(p), 42 | (1<<24), Gl41NodeMirror.NULL_NODE | (0x1C<<0)},
                {1, 2, 3, 4},
                {hi(p), lo(p), 0x1234, 1000},
                {7 | (8<<16), 9 | (10<<16), 11 | (12<<16), 13 | (14<<16)},
        };
        long buffer = scatter(locations, values);
        try {
            mirror.applyScatter(buffer, locations.length);
        } finally {
            MemoryUtil.nmemFree(buffer);
        }

        assertEquals(hi(p), mirror.posX(5));
        assertEquals(lo(p), mirror.posY(5));
        assertEquals(42, mirror.mesh(5));
        assertTrue(mirror.hasRequested(5));
        assertEquals(Gl41NodeMirror.NULL_NODE, mirror.childPtr(5));
        assertEquals(2, Gl41NodeMirror.lodLevel(mirror.posX(5)));
        assertEquals(-5, Gl41NodeMirror.decodeX(mirror.posY(5)));
        assertEquals(3, Gl41NodeMirror.decodeY(mirror.posX(5)));
        assertEquals(7, Gl41NodeMirror.decodeZ(mirror.posX(5), mirror.posY(5)));

        assertTrue(mirror.isAllocated(9));
        assertFalse(mirror.isAllocated(0));

        assertEquals(hi(p), mirror.sectionPosX(section));
        assertEquals(lo(p), mirror.sectionPosY(section));
        assertEquals(0x1234, mirror.sectionAabb(section));
        assertEquals(1000, mirror.sectionQuadStart(section));
        for (int i = 0; i < 8; i++) {
            assertEquals(7+i, mirror.sectionCount(section, i));
        }
    }

    @Test
    void decodesNegativeCoordinates() {
        long p = pos(0, -1000000, -100, -3000000);
        assertEquals(-1000000, Gl41NodeMirror.decodeX(lo(p)));
        assertEquals(-100, Gl41NodeMirror.decodeY(hi(p)));
        assertEquals(-3000000, Gl41NodeMirror.decodeZ(hi(p), lo(p)));
    }
}
