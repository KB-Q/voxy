package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static me.cortex.voxy.client.core.rendering.backend.gl41.Gl41TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class Gl41TraversalTest {
    private static final int W = 1000, H = 1000;
    private static final float SUBDIV = 64;

    //Root lod 1 node 0 at section (0,0,0) with mesh 100 and 8 lod 0 children (nodes 1-8, meshes 200-207)
    private static Gl41NodeMirror tree() {
        var mirror = new Gl41NodeMirror(64, 1024);
        setNode(mirror, 0, pos(1, 0, 0, 0), 100, 1, 8, false);
        for (int i = 0; i < 8; i++) {
            setNode(mirror, 1+i, pos(0, i&1, (i>>1)&1, (i>>2)&1), 200+i, Gl41NodeMirror.NULL_NODE, 1, false);
        }
        return mirror;
    }

    private record Camera(Matrix4f mvp, Vector4f[] planes, Vector3i section, Vector3f inner) {}

    //Camera at block position looking down +z
    private static Camera camera(float x, float y, float z) {
        var mvp = new Matrix4f().perspective((float) Math.toRadians(100), W/(float)H, 0.1f, 100000f)
                .lookAt(0, 0, 0, 0, 0, 1, 0, 1, 0);
        Vector4f[] planes;
        try {
            var field = FrustumIntersection.class.getDeclaredField("planes");
            field.setAccessible(true);
            planes = (Vector4f[]) field.get(new FrustumIntersection(mvp, false));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        var section = new Vector3i((int)Math.floor(x)>>5, (int)Math.floor(y)>>5, (int)Math.floor(z)>>5);
        var inner = new Vector3f(x-(section.x<<5), y-(section.y<<5), z-(section.z<<5));
        return new Camera(mvp, planes, section, inner);
    }

    private static List<MemoryBuffer> run(Gl41Traversal traversal, Camera cam, float renderDistance, int frame) {
        var batches = new ArrayList<MemoryBuffer>();
        traversal.traverse(cam.mvp, cam.planes, cam.section, cam.inner, W, H, SUBDIV, renderDistance, frame, 50, batches::add);
        return batches;
    }

    @Test
    void farCameraRendersRoot() {
        var mirror = tree();
        var traversal = new Gl41Traversal(mirror, new Gl41Cleaner(mirror));
        traversal.addTopLevelNode(0);
        var requests = run(traversal, camera(32, 32, -5000), -1, 1);
        assertEquals(List.of(100), traversal.visibleSections);
        assertTrue(requests.isEmpty());
    }

    @Test
    void nearCameraRendersChildren() {
        var mirror = tree();
        var cleaner = new Gl41Cleaner(mirror);
        var traversal = new Gl41Traversal(mirror, cleaner);
        traversal.addTopLevelNode(0);
        run(traversal, camera(32, 32, -40), -1, 7);
        var visible = new IntOpenHashSet(traversal.visibleSections);
        assertEquals(new IntOpenHashSet(new int[]{200, 201, 202, 203, 204, 205, 206, 207}), visible);
        assertEquals(7, cleaner.getVisibility(3));
        assertEquals(-1, cleaner.getVisibility(0), "the root is descended through, not rendered");
    }

    @Test
    void behindCameraIsCulled() {
        var mirror = tree();
        var traversal = new Gl41Traversal(mirror, new Gl41Cleaner(mirror));
        traversal.addTopLevelNode(0);
        run(traversal, camera(32, 32, 500), -1, 1);
        assertTrue(traversal.visibleSections.isEmpty());
    }

    @Test
    void outsideRenderDistanceIsSkipped() {
        var mirror = tree();
        var traversal = new Gl41Traversal(mirror, new Gl41Cleaner(mirror));
        traversal.addTopLevelNode(0);
        run(traversal, camera(32, 32, -5000), 1000, 1);
        assertTrue(traversal.visibleSections.isEmpty());
        assertEquals(0, traversal.visitedNodes);
    }

    @Test
    void childWithoutMeshIsRequestedOnce() {
        var mirror = tree();
        long childPos = pos(0, 1, 0, 0);
        setNode(mirror, 2, childPos, Gl41NodeMirror.NULL_MESH, Gl41NodeMirror.NULL_NODE, 1, false);
        var traversal = new Gl41Traversal(mirror, new Gl41Cleaner(mirror));
        traversal.addTopLevelNode(0);

        var first = run(traversal, camera(32, 32, -40), -1, 1);
        assertEquals(1, first.size());
        var batch = first.get(0);
        assertEquals(1, MemoryUtil.memGetInt(batch.address));
        //Same order the node manager reads: high word first
        long requested = (((long) MemoryUtil.memGetInt(batch.address+8))<<32) | Integer.toUnsignedLong(MemoryUtil.memGetInt(batch.address+12));
        assertEquals(childPos, requested);
        assertTrue(mirror.hasRequested(2));
        assertFalse(traversal.visibleSections.contains(Gl41NodeMirror.NULL_MESH));
        batch.free();

        assertTrue(run(traversal, camera(32, 32, -40), -1, 2).isEmpty());
    }

    @Test
    void topLevelNodesSwapRemove() {
        var mirror = tree();
        var traversal = new Gl41Traversal(mirror, new Gl41Cleaner(mirror));
        traversal.addTopLevelNode(10);
        traversal.addTopLevelNode(11);
        traversal.addTopLevelNode(12);
        traversal.removeTopLevelNode(10);
        assertEquals(2, traversal.getTopNodeCount());
        assertThrows(IllegalStateException.class, () -> traversal.removeTopLevelNode(10));
        assertThrows(IllegalStateException.class, () -> traversal.addTopLevelNode(12));
    }
}
