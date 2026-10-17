package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.HashSet;
import java.util.Set;

import static me.cortex.voxy.client.core.rendering.backend.gl41.Gl41TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class Gl41CleanerTest {
    @Test
    void selectsLeastRecentlyRenderedCandidates() {
        var mirror = new Gl41NodeMirror(1024, 16);
        var cleaner = new Gl41Cleaner(mirror);
        //300 candidate nodes, visibility = node id, so nodes 0..255 are the 256 least recently rendered
        for (int node = 0; node < 300; node++) {
            setNode(mirror, node, pos(1, node, 0, 0), node, Gl41NodeMirror.NULL_NODE, 1, false);
            cleaner.markVisible(node, node);
        }
        //Non candidates with the lowest visibility: top level, requested, empty mesh, never rendered
        setNode(mirror, 300, pos(4, 0, 0, 0), 5, Gl41NodeMirror.NULL_NODE, 1, false);
        setNode(mirror, 301, pos(1, 301, 0, 0), 5, Gl41NodeMirror.NULL_NODE, 1, true);
        setNode(mirror, 302, pos(1, 302, 0, 0), Gl41NodeMirror.EMPTY_MESH, Gl41NodeMirror.NULL_NODE, 1, false);
        setNode(mirror, 303, pos(1, 303, 0, 0), 5, Gl41NodeMirror.NULL_NODE, 1, false);
        for (int node = 300; node < 303; node++) cleaner.markVisible(node, -5+node-300);

        cleaner.visibilityId = 1000;
        var batch = cleaner.select(303);
        assertNotNull(batch);
        Set<Integer> selectedX = new HashSet<>();
        for (int i = 0; i < Gl41Cleaner.OUTPUT_COUNT; i++) {
            long p = (((long) MemoryUtil.memGetInt(batch.address+i*8L))<<32) | Integer.toUnsignedLong(MemoryUtil.memGetInt(batch.address+i*8L+4));
            assertEquals(1, (int)(p>>>60));
            selectedX.add(Gl41NodeMirror.decodeX((int) p));
        }
        batch.free();
        for (int x = 0; x < 256; x++) assertTrue(selectedX.contains(x), "missing " + x);
        assertEquals(1060, cleaner.getVisibility(0));
        assertEquals(256, cleaner.getVisibility(256));
    }

    @Test
    void nothingToSelect() {
        var mirror = new Gl41NodeMirror(16, 16);
        assertNull(new Gl41Cleaner(mirror).select(15));
    }

    @Test
    void updateIdsSetsAndClears() {
        var mirror = new Gl41NodeMirror(16, 16);
        var cleaner = new Gl41Cleaner(mirror);
        cleaner.visibilityId = 9;
        cleaner.markVisible(4, 3);
        var ops = new IntOpenHashSet();
        ops.add(2|(1<<31));
        ops.add(4);
        cleaner.updateIds(ops);
        assertEquals(9, cleaner.getVisibility(2));
        assertEquals(-1, cleaner.getVisibility(4));
    }

    @Test
    void cleansWhenLessThan256MbFree() {
        assertTrue(Gl41Cleaner.shouldCleanGeometry(1L<<30, (1L<<30) - 100_000_000));
        assertFalse(Gl41Cleaner.shouldCleanGeometry(1L<<30, 100_000_000));
    }
}
