package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlCompat;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import java.util.ArrayList;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL15C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glCreateSamplers;

public class SSAO {
    public static enum SSAOMode {
        AUTO,
        BASIC,
        BETTER,
        BEST
    }

    public static SSAO createSSAO(RenderProperties properties, SSAOMode mode) {
        if (mode == SSAOMode.BASIC) {
            return new SSAO(properties);
        } else if (mode == SSAOMode.BETTER) {
            return new SSAO(properties, true, 12);
        } else if (mode == SSAOMode.BEST) {
            return new SSAO(properties, true, 24);
        } else if (mode == SSAOMode.AUTO) {
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                if (Capabilities.INSTANCE.totalDedicatedMemory < 2_500_000_000L) {
                    return createSSAO(properties, SSAOMode.BASIC);//Create a basic instance (cant query memory (probably intel igpu or less then 2.5gb vram)
                } else if (Capabilities.INSTANCE.totalDedicatedMemory < 7_000_000_000L) {
                    return createSSAO(properties, SSAOMode.BETTER);//Less then 7gb of dedicated vram create a better instance (mid range dgpus (they can probably do best just fine but just in case)
                } else {
                    return createSSAO(properties, SSAOMode.BEST);//create the best ssao
                }
            } else {
                if (Capabilities.INSTANCE.isAmd) {
                    return createSSAO(properties, SSAOMode.BETTER);
                } else {
                    return createSSAO(properties, SSAOMode.BASIC);
                }
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    private final Shader ssaoCompute;//null on GL 4.1
    private final FullscreenBlit ssaoFragment;//GL 4.1 only, the same shader run as a fullscreen pass
    private final int outputSizeLocation;
    private final GlFramebuffer fragmentTarget;
    private final boolean isBetterSSAO;
    private final int spp;

    private final int depthSampler;
    public SSAO(RenderProperties properties) {
        this(properties, false, 0);
    }

    public SSAO(RenderProperties properties, boolean betterSSAO, int samples) {
        Shader.Builder<Shader> builder = GlCompat.GL41 ? null : Shader.make()
                .apply(properties::apply)
                .add(ShaderType.COMPUTE, "voxy:post/ssao.comp");
        var defines = new ArrayList<java.util.function.Consumer<Shader.Builder<Shader>>>();

        this.spp = samples;

        boolean useConstArray = true;

        this.isBetterSSAO = betterSSAO;
        if (betterSSAO) {
            defines.add(b->b.define("BETTER_SSAO")
                    .defineIf("SSAO_STEPS", samples!=0, samples)
                    .defineIf("USE_GENERATED_SAMPLE_POINTS", useConstArray));

            if (useConstArray) {
                String array = "";
                for (int i = 0; i < samples; i++) {
                    array += "vec2(";
                    float a = (((float) i) + 0.5f) * (1.0f / samples);

                    float base = (float) (i * (1.0 / 1.6180339887) + 0.5);
                    float r = (float) Math.sqrt(base % 1);
                    float theta = a * 6.2831853f;

                    array += (float) (r * Math.cos(theta));
                    array += "f, ";
                    array += (float) (r * Math.sin(theta));
                    array += "f)";
                    if (i != samples - 1) {
                        array += ", ";
                    }
                }
                String finalArray = array;
                defines.add(b->b.replace("%%CONST_ARRAY%%", finalArray));
            }
        }

        if (GlCompat.GL41) {
            this.ssaoCompute = null;
            this.ssaoFragment = new FullscreenBlit(properties, "voxy:post/ssao.comp", b->{
                b.define("SSAO_FRAGMENT");
                defines.forEach(d->d.accept((Shader.Builder<Shader>)(Object)b));
            });
            this.outputSizeLocation = this.ssaoFragment.uniformLocation("outputSize");
            this.fragmentTarget = new GlFramebuffer();
        } else {
            defines.forEach(d->d.accept(builder));
            this.ssaoCompute = builder.compile();
            this.ssaoFragment = null;
            this.outputSizeLocation = -1;
            this.fragmentTarget = null;
        }

        this.depthSampler = glGenSamplers();
        //UHHHH IS THIS EVEN VALID FOR A DEPTH SAMPLER????
        if (this.isBetterSSAO) {
            glSamplerParameteri(this.depthSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            glSamplerParameteri(this.depthSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            glSamplerParameteri(this.depthSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glSamplerParameteri(this.depthSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    }

    public void computeSSAO(Viewport<?> viewport, GlTexture colourOut, GlTexture colourIn, GlTexture baseDepthTex, int sourceDepthTexture) {
        if (this.ssaoFragment != null) {
            this.ssaoFragment.bind();
        } else {
            this.ssaoCompute.bind();
        }
        //The matrices
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4*4*4);
            var scratch = new Matrix4f();
            if (this.isBetterSSAO) {
                viewport.projection.getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(4), 1, false, ptr);//Proj
                viewport.projection.invert(scratch).getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(5), 1, false, ptr);//invProj
                viewport.modelView.getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(6), 1, false, ptr);//MV (the normal matrix)
                viewport.vanillaProjection.invert(scratch).getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(7), 1, false, ptr);//sourceInvProj
            } else {
                viewport.MVP.getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(3), 1, false, ptr);//MVP
                viewport.MVP.invert(scratch).getToAddress(ptr);
                nglUniformMatrix4fv(this.loc(4), 1, false, ptr);//invMVP
            }
        }

        if (this.ssaoFragment == null) {
            glBindImageTexture(0, colourOut.id, 0, false,0, GL_READ_WRITE, GL_RGBA8);
        }
        GlCompat.bindTextureUnit(1, colourIn.id);
        glBindSampler(1,0);
        GlCompat.bindTextureUnit(2, baseDepthTex.id);
        glBindSampler(2, this.depthSampler);

        if (this.isBetterSSAO) {
            GlCompat.bindTextureUnit(3, sourceDepthTexture);
            glBindSampler(3, this.depthSampler);
        }

        if (this.ssaoFragment != null) {
            //Separate framebuffer with only the output attached so the depth texture can be sampled
            int prevFb = glGetInteger(GL_FRAMEBUFFER_BINDING);
            this.fragmentTarget.bind(GL_COLOR_ATTACHMENT0, colourOut);
            glBindFramebuffer(GL_FRAMEBUFFER, this.fragmentTarget.id);
            glUniform2i(this.outputSizeLocation, viewport.width, viewport.height);
            boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
            boolean stencilTest = glIsEnabled(GL_STENCIL_TEST);
            boolean blend = glIsEnabled(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_BLEND);
            this.ssaoFragment.blit();
            if (depthTest) glEnable(GL_DEPTH_TEST);
            if (stencilTest) glEnable(GL_STENCIL_TEST);
            if (blend) glEnable(GL_BLEND);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        } else {
            glDispatchCompute((viewport.width+7)/8, (viewport.height+7)/8, 1);
        }

        GlCompat.bindTextureUnit(1, 0);
        glBindSampler(1,0);
        GlCompat.bindTextureUnit(2, 0);
        glBindSampler(2, 0);
        GlCompat.bindTextureUnit(3, 0);
        glBindSampler(3, 0);
    }

    private int loc(int logical) {
        return this.ssaoFragment != null ? this.ssaoFragment.loc(logical) : logical;
    }

    public void free() {
        glDeleteSamplers(this.depthSampler);
        if (this.ssaoCompute != null) this.ssaoCompute.free();
        if (this.ssaoFragment != null) this.ssaoFragment.delete();
        if (this.fragmentTarget != null) this.fragmentTarget.free();
    }

    public void addDebugInfo(List<String> debugLines) {
        debugLines.add("SSAO: "+(this.isBetterSSAO?("new ("+this.spp+" spp)"):"basic"));
    }
}
