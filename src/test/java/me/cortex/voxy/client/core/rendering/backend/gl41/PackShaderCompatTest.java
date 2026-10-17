package me.cortex.voxy.client.core.rendering.backend.gl41;

import me.cortex.voxy.client.iris.PackShaderCompat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackShaderCompatTest {
    @Test
    void literalsInBitwiseOpsWithUintFieldsBecomeUnsigned() {
        assertEquals("if ((parameters.face & 1u) == 0) {", PackShaderCompat.fixUintBitwiseLiterals("if ((parameters.face & 1) == 0) {"));
        assertEquals("x = 3u|parameters.modelId;", PackShaderCompat.fixUintBitwiseLiterals("x = 3|parameters.modelId;"));
        //Already unsigned, other operators, other variables and float literals are left alone
        assertEquals("parameters.face & 1u", PackShaderCompat.fixUintBitwiseLiterals("parameters.face & 1u"));
        assertEquals("uint(parameters.face) >> 1u", PackShaderCompat.fixUintBitwiseLiterals("uint(parameters.face) >> 1u"));
        assertEquals("mat & 1", PackShaderCompat.fixUintBitwiseLiterals("mat & 1"));
        assertEquals("parameters.faceX & 1", PackShaderCompat.fixUintBitwiseLiterals("parameters.faceX & 1"));
    }
}
