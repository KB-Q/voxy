package me.cortex.voxy.client.iris;

import java.util.regex.Pattern;

//Source fixups for shader pack voxy patches on the GL 4.1 backend. The macOS glsl compiler does not apply the implicit
// int to uint conversion for bitwise operators, so packs written against other drivers fail on e.g. (parameters.face & 1)
public final class PackShaderCompat {
    private static final String UINT_FIELD = "\\bparameters\\.(?:face|modelId|customId)\\b";
    private static final Pattern FIELD_THEN_LITERAL = Pattern.compile("(" + UINT_FIELD + "\\s*[&|^]\\s*)(\\d+)(?![\\w.])");
    private static final Pattern LITERAL_THEN_FIELD = Pattern.compile("(?<![\\w.])(\\d+)(\\s*[&|^]\\s*" + UINT_FIELD + ")");

    private PackShaderCompat() {}

    //Integer literals used in a bitwise operation with one of the uint VoxyFragmentParameters fields become unsigned
    public static String fixUintBitwiseLiterals(String src) {
        src = FIELD_THEN_LITERAL.matcher(src).replaceAll("$1$2u");
        return LITERAL_THEN_FIELD.matcher(src).replaceAll("$1u$2");
    }
}
