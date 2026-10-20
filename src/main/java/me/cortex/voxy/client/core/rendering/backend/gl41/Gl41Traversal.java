package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

//CPU port of the hierarchical traversal (lod/hierarchical/traversal_dev.comp + screenspace.glsl) for the GL 4.1 backend
// it selects which node meshes to render and which nodes need their children/meshes requested
// differences to the gpu version: no hiz occlusion culling, results are available in the same frame
public final class Gl41Traversal {
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_RENDER_QUEUE_SIZE = 200_000;

    public interface RequestSink {
        void submitRequestBatch(MemoryBuffer batch);
    }

    private final Gl41NodeMirror mirror;
    private final Gl41Cleaner cleaner;

    //Top level nodes, kept dense with swap removal like the gpu version
    private int[] topNodes = new int[1024];
    private int topNodeCount;
    private final Int2IntOpenHashMap topNodeIndex = new Int2IntOpenHashMap();

    private int[] queue = new int[1<<16];

    //Per frame outputs
    public final IntArrayList visibleSections = new IntArrayList();
    private final long[] requests = new long[MAX_REQUEST_QUEUE_SIZE];
    private int requestCount;
    public int visitedNodes;
    public int lastRequestCount;
    //Nodes that should be drawn but have no mesh yet and nothing to fall back to, these show up as holes
    public int holeNodes;
    public final long[] holePositions = new long[8];

    //Per frame parameters
    private final Matrix4f mvp = new Matrix4f();
    private final Vector4f[] planes = new Vector4f[5];
    private int camX, camY, camZ;
    private float subX, subY, subZ;
    private float minScreenSize;
    private float renderDistanceSq;
    private int frameId;
    private int requestLimit;

    public Gl41Traversal(Gl41NodeMirror mirror, Gl41Cleaner cleaner) {
        this.mirror = mirror;
        this.cleaner = cleaner;
        this.topNodeIndex.defaultReturnValue(-1);
        for (int i = 0; i < this.planes.length; i++) {
            this.planes[i] = new Vector4f();
        }
    }

    public void addTopLevelNode(int id) {
        if (this.topNodeIndex.put(id, this.topNodeCount) != -1) {
            throw new IllegalStateException("Top level node added twice: " + id);
        }
        if (this.topNodeCount == this.topNodes.length) {
            this.topNodes = java.util.Arrays.copyOf(this.topNodes, this.topNodes.length*2);
        }
        this.topNodes[this.topNodeCount++] = id;
    }

    public void removeTopLevelNode(int id) {
        int idx = this.topNodeIndex.remove(id);
        if (idx == -1) {
            throw new IllegalStateException("Top level node not present: " + id);
        }
        int last = this.topNodes[--this.topNodeCount];
        if (idx != this.topNodeCount) {
            this.topNodes[idx] = last;
            this.topNodeIndex.put(last, idx);
        }
    }

    public int getTopNodeCount() {
        return this.topNodeCount;
    }

    //minScreenSize is the screen area fraction above which a node is subdivided, renderDistance is in blocks (<0 means unlimited)
    public void traverse(Viewport<?> viewport, float subDivisionSize, float renderDistance, int frameId, int requestLimit, RequestSink requestSink) {
        this.traverse(viewport.MVP, viewport.frustumPlanes, viewport.section, viewport.innerTranslation, viewport.width, viewport.height,
                subDivisionSize, renderDistance, frameId, requestLimit, requestSink);
    }

    //mvp maps camera relative block positions to clip space, planes are the joml FrustumIntersection planes of mvp,
    // section is the camera position in level 0 section units and innerTranslation the camera position within that section
    public void traverse(Matrix4fc mvp, Vector4f[] planes, Vector3ic section, Vector3fc innerTranslation, int width, int height,
                         float subDivisionSize, float renderDistance, int frameId, int requestLimit, RequestSink requestSink) {
        this.setup(mvp, planes, section, innerTranslation, width, height, subDivisionSize, renderDistance, frameId, requestLimit);
        this.visibleSections.clear();
        this.requestCount = 0;
        this.visitedNodes = 0;
        this.holeNodes = 0;

        //Breadth first over a growable fifo, each node is visited at most once per frame
        this.queueSize = 0;
        for (int i = 0; i < this.topNodeCount; i++) {
            this.push(this.topNodes[i]);
        }
        for (int head = 0; head < this.queueSize; head++) {
            this.visit(this.queue[head]);
        }

        this.lastRequestCount = this.requestCount;
        if (this.requestCount != 0) {
            var batch = new MemoryBuffer(this.requestCount*8L+8);
            MemoryUtil.memPutInt(batch.address, this.requestCount);
            MemoryUtil.memPutInt(batch.address+4, 0);
            for (int i = 0; i < this.requestCount; i++) {
                MemoryUtil.memPutLong(batch.address+8+i*8L, this.requests[i]);
            }
            requestSink.submitRequestBatch(batch);
        }
    }

    private void setup(Matrix4fc mvp, Vector4f[] planes, Vector3ic section, Vector3fc innerTranslation, int width, int height,
                       float subDivisionSize, float renderDistance, int frameId, int requestLimit) {
        this.mvp.set(mvp);
        for (int i = 0; i < 5; i++) {//The far plane is not tested, same as frustum.glsl
            this.planes[i].set(planes[i]);
        }
        this.camX = section.x();
        this.camY = section.y();
        this.camZ = section.z();
        this.subX = innerTranslation.x();
        this.subY = innerTranslation.y();
        this.subZ = innerTranslation.z();
        this.minScreenSize = (subDivisionSize*subDivisionSize)/(width*(float)height);
        this.renderDistanceSq = renderDistance<0?-1:renderDistance*renderDistance;
        this.frameId = frameId;
        this.requestLimit = Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestLimit));
    }

    private int queueSize;
    private void push(int node) {
        if (this.queueSize == this.queue.length) {
            this.queue = java.util.Arrays.copyOf(this.queue, this.queue.length*2);
        }
        this.queue[this.queueSize++] = node;
    }

    private void visit(int node) {
        var m = this.mirror;
        int px = m.posX(node);
        int py = m.posY(node);
        int lod = Gl41NodeMirror.lodLevel(px);
        int sx = Gl41NodeMirror.decodeX(py);
        int sy = Gl41NodeMirror.decodeY(px);
        int sz = Gl41NodeMirror.decodeZ(px, py);

        //Camera relative position of the node min corner in blocks, size of the node in blocks
        float bx = (((sx<<lod)-this.camX)<<5)-this.subX;
        float by = (((sy<<lod)-this.camY)<<5)-this.subY;
        float bz = (((sz<<lod)-this.camZ)<<5)-this.subZ;
        float size = 32<<lod;

        if (this.renderDistanceSq >= 0) {//isWithinRenderDistance, closest point on xz
            float cx = closest(bx, size);
            float cz = closest(bz, size);
            if (cx*cx+cz*cz > this.renderDistanceSq) return;
        }
        this.visitedNodes++;

        if (this.outsideFrustum(bx, by, bz, size)) {
            return;
        }

        int mesh = m.mesh(node);
        int childPtr = m.childPtr(node);
        boolean hasChildren = childPtr != Gl41NodeMirror.NULL_NODE;
        if (lod != 0 && this.screenSize(bx, by, bz, size) > this.minScreenSize) {
            if (hasChildren) {
                this.enqueueChildren(node, childPtr);
                return;
            }
            this.addRequest(node);
            boolean renderSelf = this.renderDistanceSq < 0;
            if (!renderSelf) {
                float fx = furthest(bx, size);
                float fz = furthest(bz, size);
                renderSelf = (fx*fx+fz*fz+(16*16*16)) <= this.renderDistanceSq;//the +16^3 is buffer room
            }
            if (renderSelf) {
                if (mesh == Gl41NodeMirror.NULL_MESH) {
                    this.recordHole(node);
                }
                this.enqueueSelfForRender(node, mesh);
            }
        } else {
            if (mesh != Gl41NodeMirror.NULL_MESH) {
                this.enqueueSelfForRender(node, mesh);
            } else {
                //We want to render this node but have no mesh, request one and fall back to the children
                this.addRequest(node);
                if (lod != 0 && hasChildren) {
                    this.enqueueChildren(node, childPtr);
                } else {
                    this.recordHole(node);
                }
            }
        }
    }

    private void recordHole(int node) {
        if (this.holeNodes < this.holePositions.length) {
            this.holePositions[this.holeNodes] = (((long)this.mirror.posX(node))<<32) | Integer.toUnsignedLong(this.mirror.posY(node));
        }
        this.holeNodes++;
    }

    private void enqueueChildren(int node, int childPtr) {
        if (childPtr == Gl41NodeMirror.EMPTY_QUEUE_ID) {//Rare but valid, nothing to enqueue
            return;
        }
        int count = this.mirror.childCount(node);
        for (int i = 0; i < count; i++) {
            this.push(childPtr+i);
        }
    }

    private void enqueueSelfForRender(int node, int mesh) {
        if (this.visibleSections.size() >= MAX_RENDER_QUEUE_SIZE) return;
        this.cleaner.markVisible(node, this.frameId);
        if (mesh != Gl41NodeMirror.EMPTY_MESH) {
            this.visibleSections.add(mesh);
        }
    }

    private void addRequest(int node) {
        if (this.mirror.hasRequested(node)) return;
        if (this.requestCount >= this.requestLimit) return;
        this.requests[this.requestCount++] = (this.mirror.posX(node)&0xFFFFFFFFL) | (((long)this.mirror.posY(node))<<32);
        this.mirror.markRequested(node);
    }

    private static float closest(float base, float size) {
        if (base > 0) return base;
        if (base + size < 0) return base + size;
        return 0;
    }

    private static float furthest(float base, float size) {
        //Mirrors furthestPointToCamera in traversal_dev.comp
        float near = base > 0 ? base : 0;
        return (base + size < 0) ? near : base + size;
    }

    private boolean outsideFrustum(float x, float y, float z, float size) {
        for (var p : this.planes) {
            float vx = p.x < 0 ? x : x + size;
            float vy = p.y < 0 ? y : y + size;
            float vz = p.z < 0 ? z : z + size;
            if (p.x*vx + p.y*vy + p.z*vz < -p.w) {
                return true;
            }
        }
        return false;
    }

    //Projected screen area (fraction of the screen) of the node box, see setupScreenspace in screenspace.glsl
    private final float[] sx = new float[8];
    private final float[] sy = new float[8];
    private float screenSize(float x, float y, float z, float size) {
        var m = this.mvp;
        for (int i = 0; i < 8; i++) {
            float px = x + ((i&1)!=0?size:0);
            float py = y + ((i&2)!=0?size:0);
            float pz = z + ((i&4)!=0?size:0);
            float cx = m.m00()*px + m.m10()*py + m.m20()*pz + m.m30();
            float cy = m.m01()*px + m.m11()*py + m.m21()*pz + m.m31();
            float cw = m.m03()*px + m.m13()*py + m.m23()*pz + m.m33();
            this.sx[i] = (cx/cw)*0.5f+0.5f;
            this.sy[i] = (cy/cw)*0.5f+0.5f;
        }
        //Corner index bits: 1=x, 2=y, 4=z. Faces around corner 000 and corner 111, halved as both front and back are counted
        float s = 0;
        s += cross(0, 1, 2, 0);//A=x,B=y
        s += cross(0, 1, 4, 0);//A=x,C=z
        s += cross(0, 4, 2, 0);//C=z,B=y
        s += cross(7, 3, 5, 7);//A=011-111, B=101-111
        s += cross(7, 3, 6, 7);//A, C=110-111
        s += cross(7, 6, 5, 7);//C, B
        return s*0.5f;
    }

    private float cross(int origin, int a, int b, int unused) {
        float ax = this.sx[a]-this.sx[origin], ay = this.sy[a]-this.sy[origin];
        float bx = this.sx[b]-this.sx[origin], by = this.sy[b]-this.sy[origin];
        return Math.abs(ax*by - bx*ay);
    }
}
