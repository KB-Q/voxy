package me.cortex.voxy.autotest;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlInfo {
    public static Map<String, Object> capture() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", GL11.glGetString(GL11.GL_VERSION));
        info.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
        info.put("vendor", GL11.glGetString(GL11.GL_VENDOR));
        info.put("glsl", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        List<String> ext = new ArrayList<>();
        for (int i = 0; i < count; i++) ext.add(GL30.glGetStringi(GL11.GL_EXTENSIONS, i));
        info.put("extensions", ext);
        info.put("maxTextureBufferSize", GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE));
        info.put("maxUniformBlockSize", GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE));
        info.put("maxFragmentTextureUnits", GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
        info.put("maxVertexTextureUnits", GL11.glGetInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
        //Dont let the capability queries count as harness observed errors
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
        return info;
    }
}
