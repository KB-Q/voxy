package me.cortex.voxy.client.core.rendering.backend.gl41;

import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.List;

//The GL 4.1 replacement for NodeCleaner + HierarchicalOcclusionTraverser, owns the cpu mirrors and runs the per frame
// node sync, cleaning and traversal. The selected sections are consumed by Gl41SectionRenderer in the same frame
public final class Gl41LodSelection {
    private final AsyncNodeManager nodeManager;
    private final RenderGenerationService meshGen;
    public final Gl41NodeMirror mirror;
    public final Gl41Cleaner cleaner;
    public final Gl41Traversal traversal;

    private final Gl41Cleaner.Sink cleanerSink;
    private long traversalNanos;

    public Gl41LodSelection(AsyncNodeManager nodeManager, RenderGenerationService meshGen, int maxSectionCount) {
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.mirror = new Gl41NodeMirror(nodeManager.maxNodeCount, maxSectionCount);
        this.cleaner = new Gl41Cleaner(this.mirror);
        this.traversal = new Gl41Traversal(this.mirror, this.cleaner);
        nodeManager.setTLNAddRemoveCallbacks(this.traversal::addTopLevelNode, this.traversal::removeTopLevelNode);
        this.cleanerSink = new Gl41Cleaner.Sink() {
            @Override
            public void submitRemoveBatch(MemoryBuffer batch) {
                nodeManager.submitRemoveBatch(batch);
            }

            @Override
            public long getGeometryCapacity() {
                return nodeManager.getGeometryCapacity();
            }

            @Override
            public long getUsedGeometryCapacity() {
                return nodeManager.getUsedGeometryCapacity();
            }

            @Override
            public int getCurrentMaxNodeId() {
                return nodeManager.getCurrentMaxNodeId();
            }
        };
    }

    //Same order as AbstractRenderPipeline.innerPrimaryWork on GL 4.6: sync node manager results, tick the cleaner, traverse
    public void run(Viewport<?> viewport) {
        TimingStatistics.main.stop();
        TimingStatistics.dynamic.start();
        this.nodeManager.tick41(this.mirror, this.cleaner);
        this.cleaner.tick(this.cleanerSink);
        TimingStatistics.dynamic.stop();
        TimingStatistics.main.start();

        TimingStatistics.F.start();
        long start = System.nanoTime();
        this.traversal.traverse(viewport,
                VoxyConfig.CONFIG.subDivisionSize,
                VoxyConfig.CONFIG.sectionRenderDistance*16*32,
                this.cleaner.visibilityId,
                requestLimit(this.meshGen.getTaskCount()),
                this.nodeManager::submitRequestBatch);
        this.traversalNanos = System.nanoTime() - start;
        TimingStatistics.F.stop();
    }

    //Fewer requests while the mesh queue is full, same curve as HierarchicalOcclusionTraverser.uploadUniform
    static int requestLimit(int meshTasks) {
        final double TARGET_COUNT = 4000;
        double iFillness = Math.max(0, (TARGET_COUNT - meshTasks) / TARGET_COUNT);
        iFillness = Math.pow(iFillness, 2);
        int requestSize = (int) Math.ceil(iFillness * Gl41Traversal.MAX_REQUEST_QUEUE_SIZE);
        return Math.max(0, Math.min(Gl41Traversal.MAX_REQUEST_QUEUE_SIZE, requestSize));
    }

    public void addDebug(List<String> debug) {
        debug.add(String.format("GL41 traversal: %.2fms, TLN %d, visited %d, visible %d",
                this.traversalNanos/1e6, this.traversal.getTopNodeCount(), this.traversal.visitedNodes, this.traversal.visibleSections.size()));
    }
}
