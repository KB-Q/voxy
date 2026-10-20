package me.cortex.voxy.client.core.rendering.backend.gl41;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//CPU port of the node cleaner (hierarchical/cleaner/*.comp) for the GL 4.1 backend
// tracks the last frame each node was rendered, when geometry memory runs low the least recently rendered nodes have their meshes removed
public final class Gl41Cleaner {
    public static final int OUTPUT_COUNT = 256;//Must match NodeCleaner.OUTPUT_COUNT, the node manager reads exactly this many entries

    public interface Sink {
        void submitRemoveBatch(MemoryBuffer batch);
        long getGeometryCapacity();
        long getUsedGeometryCapacity();
        int getCurrentMaxNodeId();
    }

    private final Gl41NodeMirror mirror;
    private final int[] visibility;
    public int visibilityId = 0;

    //Scratch for the selection, a max heap of the OUTPUT_COUNT smallest visibility values seen so far
    private final int[] heapVis = new int[OUTPUT_COUNT];
    private final int[] heapNode = new int[OUTPUT_COUNT];

    public Gl41Cleaner(Gl41NodeMirror mirror) {
        this.mirror = mirror;
        this.visibility = new int[mirror.nodeCapacity()];
        Arrays.fill(this.visibility, -1);
    }

    public void markVisible(int node, int frameId) {
        this.visibility[node] = frameId;
    }

    public int getVisibility(int node) {
        return this.visibility[node];
    }

    //Bit 31 set: (re)allocated node, set to the current visibility id. Clear: freed node, set to -1 (batch_visibility_set.comp)
    public void updateIds(IntOpenHashSet operations) {
        var iter = operations.intIterator();
        while (iter.hasNext()) {
            int op = iter.nextInt();
            int node = op&0x7FFFFFFF;
            this.visibility[node] = (op&(1<<31)) != 0 ? this.visibilityId : -1;
        }
    }

    public void tick(Sink sink) {
        this.visibilityId++;
        if (shouldCleanGeometry(sink.getGeometryCapacity(), sink.getUsedGeometryCapacity())) {
            var batch = this.select(sink.getCurrentMaxNodeId());
            if (batch != null) {
                sink.submitRemoveBatch(batch);
            }
        }
    }

    static boolean shouldCleanGeometry(long capacity, long used) {
        return capacity - used < 256_000_000;//Less than 256 mb free, same as NodeCleaner
    }

    //Selects up to OUTPUT_COUNT removable nodes with the lowest visibility (least recently rendered), sort_visibility.comp + result_transformer.comp
    // returns null when nothing can be removed
    MemoryBuffer select(int maxNodeId) {
        int count = 0;
        int end = Math.min(maxNodeId+1, this.visibility.length);
        for (int node = 0; node < end; node++) {
            int vis = this.visibility[node];
            if (vis == -1 || !this.isCandidate(node)) continue;
            if (count < OUTPUT_COUNT) {
                this.heapPush(count++, vis, node);
            } else if (vis < this.heapVis[0]) {
                this.heapReplaceTop(count, vis, node);
            }
        }
        if (count == 0) {
            return null;
        }
        //Output layout: OUTPUT_COUNT node positions written as uvec2(node.x, node.y) like the gpu, unused entries are -1
        var batch = new MemoryBuffer(OUTPUT_COUNT*8L);
        MemoryUtil.memSet(batch.address, -1, batch.size);
        for (int i = 0; i < count; i++) {
            int node = this.heapNode[i];
            //Push the visibility forward so the node is not selected again before the removal has been processed
            this.visibility[node] = this.visibilityId + 60;
            MemoryUtil.memPutInt(batch.address + i*8L, this.mirror.posX(node));
            MemoryUtil.memPutInt(batch.address + i*8L + 4, this.mirror.posY(node));
        }
        return batch;
    }

    private boolean isCandidate(int node) {
        var m = this.mirror;
        if (!m.isAllocated(node)) return false;
        int mesh = m.mesh(node);
        if (mesh == Gl41NodeMirror.NULL_MESH || mesh == Gl41NodeMirror.EMPTY_MESH) return false;
        if (Gl41NodeMirror.lodLevel(m.posX(node)) == 4) return false;//Top level nodes are never cleaned
        return !m.hasRequested(node);
    }

    private void heapPush(int size, int vis, int node) {
        int i = size;
        this.heapVis[i] = vis;
        this.heapNode[i] = node;
        while (i > 0) {
            int parent = (i-1)>>1;
            if (this.heapVis[parent] >= this.heapVis[i]) break;
            this.swap(i, parent);
            i = parent;
        }
    }

    private void heapReplaceTop(int size, int vis, int node) {
        this.heapVis[0] = vis;
        this.heapNode[0] = node;
        int i = 0;
        while (true) {
            int l = i*2+1, r = l+1, largest = i;
            if (l < size && this.heapVis[l] > this.heapVis[largest]) largest = l;
            if (r < size && this.heapVis[r] > this.heapVis[largest]) largest = r;
            if (largest == i) break;
            this.swap(i, largest);
            i = largest;
        }
    }

    private void swap(int a, int b) {
        int tv = this.heapVis[a]; this.heapVis[a] = this.heapVis[b]; this.heapVis[b] = tv;
        int tn = this.heapNode[a]; this.heapNode[a] = this.heapNode[b]; this.heapNode[b] = tn;
    }
}
