package me.cortex.voxy.client.core.rendering.backend.gl41;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlCompat;
import me.cortex.voxy.client.core.gl.GlTextureBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL30C.GL_R32UI;
import static org.lwjgl.opengl.GL30C.GL_RG32UI;
import static org.lwjgl.opengl.GL30C.GL_RGBA32UI;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL32C.GL_FIRST_VERTEX_CONVENTION;
import static org.lwjgl.opengl.GL32C.glProvokingVertex;
import static org.lwjgl.opengl.GL33C.glBindSampler;

//Renders the sections selected by Gl41LodSelection with one instanced draw per draw entry bucket (see DrawEntryBuilder)
// quads, models and draw entries are read in the vertex shader from texture buffers
public class Gl41SectionRenderer extends AbstractSectionRenderer<Gl41Viewport, BasicSectionGeometryData> {
    public static final Factory<Gl41Viewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(Gl41SectionRenderer.class);

    private static final int ENTRY_RING_SIZE = 3;
    private static final int ENTRY_BASE_LOCATION = 10;

    //Texture units, must match lod/gl41/quads.vert and lod/gl46/quads.frag
    private static final int ATLAS_UNIT = 0;
    private static final int LIGHTMAP_UNIT = 1;
    private static final int DEPTH_BOUND_UNIT = 2;
    private static final int MODEL_UNIT = 3;
    private static final int MODEL_COLOUR_UNIT = 4;
    private static final int QUAD_UNIT = 5;
    private static final int ENTRY_UNIT = 6;

    private final AbstractRenderPipeline pipeline;
    private final Gl41LodSelection lodSelection;
    private final Shader terrainShader;
    private final Shader translucentTerrainShader;

    private final GlBuffer uniform = new GlBuffer(1024).zero();
    private final GlTextureBuffer quadTextureBuffer;

    private final DrawEntryBuilder entries = new DrawEntryBuilder();
    private final GlBuffer[] entryBuffers = new GlBuffer[ENTRY_RING_SIZE];
    private final GlTextureBuffer[] entryTextureBuffers = new GlTextureBuffer[ENTRY_RING_SIZE];
    private int entryRingIndex;
    private long entryScratch;
    private long entryScratchSize;
    private boolean hasEntries;
    private long uploadedEntryBytes;

    public Gl41SectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(pipeline.properties, modelStore, geometryData);
        this.pipeline = pipeline;
        this.lodSelection = pipeline.getGl41LodSelection();
        if (this.lodSelection == null) {
            throw new IllegalStateException("The GL 4.1 section renderer needs the GL 4.1 lod selection");
        }

        String vertex = ShaderLoader.parse("voxy:lod/gl41/quads.vert");
        String taa = pipeline.taaFunction("taaShift");
        if (taa != null) {
            vertex += "\n"+taa;
        }
        var builder = Shader.make()
                .apply(this.properties::apply)
                .defineIf("TAA_PATCH", taa != null)
                .addSource(ShaderType.VERTEX, vertex);
        addDirectionalFaceTint(builder, Minecraft.getInstance().level);

        String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
        String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
        opaqueFrag = opaqueFrag==null?frag:opaqueFrag;
        this.terrainShader = tryCompilePatchedOrNormal(builder, opaqueFrag, frag);

        String translucentFrag = pipeline.patchTranslucentShader(this, frag);
        translucentFrag = translucentFrag==null?frag:translucentFrag;
        this.translucentTerrainShader = tryCompilePatchedOrNormal(builder.define("TRANSLUCENT"), translucentFrag, frag);

        this.quadTextureBuffer = new GlTextureBuffer(geometryData.getGeometryBuffer(), GL_RG32UI);
        for (int i = 0; i < ENTRY_RING_SIZE; i++) {
            this.allocEntryBuffer(i, 1<<16);
        }
    }

    private void allocEntryBuffer(int index, long entryCount) {
        if (this.entryBuffers[index] != null) {
            this.entryTextureBuffers[index].free();
            this.entryBuffers[index].free();
        }
        this.entryBuffers[index] = new GlBuffer(entryCount*16, false).name("Gl41DrawEntries");
        this.entryTextureBuffers[index] = new GlTextureBuffer(this.entryBuffers[index], GL_RGBA32UI);
    }

    private void uploadUniformBuffer(Gl41Viewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);

        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        UploadStream.INSTANCE.commit();
    }

    @Override
    public void renderOpaque(Gl41Viewport viewport) {
        //Nothing to draw before the traversal, there is no temporal reprojection on GL 4.1, everything is drawn in renderTemporal
    }

    @Override
    public void buildDrawCalls(Gl41Viewport viewport) {
        this.hasEntries = false;
        if (this.geometryManager.getSectionCount() == 0) return;

        this.entries.build(this.lodSelection.traversal.visibleSections, this.lodSelection.mirror, viewport.section.x, viewport.section.y, viewport.section.z);
        int total = this.entries.getTotalEntryCount();
        if (total == 0) return;

        long bytes = total*16L;
        if (this.entryScratchSize < bytes) {
            this.entryScratchSize = Math.max(bytes, this.entryScratchSize*2);
            this.entryScratch = MemoryUtil.nmemRealloc(this.entryScratch, this.entryScratchSize);
        }
        this.entries.write(this.entryScratch);

        this.entryRingIndex = (this.entryRingIndex+1)%ENTRY_RING_SIZE;
        if (this.entryBuffers[this.entryRingIndex].size() < bytes) {
            this.allocEntryBuffer(this.entryRingIndex, Long.highestOneBit(total)*2);
        }
        this.entryBuffers[this.entryRingIndex].subData(0, this.entryScratch, bytes);
        this.uploadedEntryBytes = bytes;

        this.uploadUniformBuffer(viewport);
        this.hasEntries = true;
    }

    private void bindRenderingState(Gl41Viewport viewport, Shader shader) {
        shader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        this.modelStore.bind(MODEL_UNIT, MODEL_COLOUR_UNIT, ATLAS_UNIT);
        LightMapHelper.bind(LIGHTMAP_UNIT);
        GlCompat.bindTextureUnit(DEPTH_BOUND_UNIT, viewport.depthBoundingBuffer.getDepthTex().id);
        this.quadTextureBuffer.bind(QUAD_UNIT);
        this.entryTextureBuffers[this.entryRingIndex].bind(ENTRY_UNIT);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
    }

    private void unbindRenderingState() {
        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        for (int unit : new int[]{MODEL_UNIT, MODEL_COLOUR_UNIT, QUAD_UNIT, ENTRY_UNIT}) {
            GlCompat.bindTextureUnit(unit, GL_TEXTURE_BUFFER, 0);
        }
        for (int unit = 0; unit <= DEPTH_BOUND_UNIT; unit++) {
            glBindSampler(unit, 0);
            GlCompat.bindTextureUnit(unit, 0);
        }
    }

    private void drawEntries(Shader shader, int quadsPerEntry, int entryOffset, int entryCount) {
        if (entryCount == 0) return;
        glUniform1i(shader.loc(ENTRY_BASE_LOCATION), entryOffset);
        glDrawElementsInstanced(GL_TRIANGLES, quadsPerEntry*6, GL_UNSIGNED_SHORT, 0, entryCount);
    }

    @Override
    public void renderTemporal(Gl41Viewport viewport) {
        if (!this.hasEntries) return;
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingState(viewport, this.terrainShader);

        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        for (int b = 0; b < DrawEntryBuilder.OPAQUE_BUCKETS.length; b++) {
            this.drawEntries(this.terrainShader, DrawEntryBuilder.OPAQUE_BUCKETS[b], this.entries.getOpaqueEntryOffset(b), this.entries.getOpaqueEntryCount(b));
        }
        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        this.unbindRenderingState();
    }

    @Override
    public void renderTranslucent(Gl41Viewport viewport) {
        if (!this.hasEntries || this.entries.getTranslucentEntryCount() == 0) return;
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingState(viewport, this.translucentTerrainShader);

        this.drawEntries(this.translucentTerrainShader, DrawEntryBuilder.TRANSLUCENT_BUCKET, this.entries.getTranslucentEntryOffset(), this.entries.getTranslucentEntryCount());

        this.unbindRenderingState();
        glDisable(GL_BLEND);
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        this.lodSelection.addDebug(lines);
        var e = this.entries;
        lines.add("GL41 entries [1024/128/16/T]: " + e.getOpaqueEntryCount(0) + "/" + e.getOpaqueEntryCount(1) + "/" + e.getOpaqueEntryCount(2) + "/" + e.getTranslucentEntryCount()
                + ", quads O/T: " + e.opaqueQuads + "/" + e.translucentQuads + ", upload: " + (this.uploadedEntryBytes>>10) + "KiB");
    }

    @Override
    public Gl41Viewport createViewport() {
        return new Gl41Viewport(this.properties);
    }

    @Override
    public void free() {
        this.uniform.free();
        this.quadTextureBuffer.free();
        for (int i = 0; i < ENTRY_RING_SIZE; i++) {
            this.entryTextureBuffers[i].free();
            this.entryBuffers[i].free();
        }
        if (this.entryScratch != 0) {
            MemoryUtil.nmemFree(this.entryScratch);
        }
        this.terrainShader.free();
        this.translucentTerrainShader.free();
    }
}
