package me.cortex.voxy.client.core.gl.shader;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL31C.GL_INVALID_INDEX;
import static org.lwjgl.opengl.GL31C.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL31C.glUniformBlockBinding;

//GLSL 4.10 (macOS) has no layout(binding = N) and no explicit uniform locations (layout(location = N) uniform)
// this strips them from the source and records them, after linking they are applied by name and explicit locations
// are mapped to the real ones through Shader.loc
final class Glsl410Transform {
    //layout(...) uniform <type> <name>;  (samplers, plain uniforms)
    private static final String LAYOUT_ARGS = "((?:[^()]|\\([^()]*\\))*)";//allows one level of parentheses, e.g. binding=(BASE+1)
    private static final Pattern UNIFORM_DECL = Pattern.compile("layout\\s*\\(" + LAYOUT_ARGS + "\\)\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    //layout(...) uniform <BlockName> {
    private static final Pattern UNIFORM_BLOCK = Pattern.compile("layout\\s*\\(" + LAYOUT_ARGS + "\\)\\s*uniform\\s+(\\w+)\\s*\\{");
    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\w+|\\([^()]*\\))");
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\w+)");
    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#define\\s+(\\w+)\\s+(\\S+)\\s*$");

    final Object2IntLinkedOpenHashMap<String> samplerUnits = new Object2IntLinkedOpenHashMap<>();
    final Object2IntLinkedOpenHashMap<String> blockBindings = new Object2IntLinkedOpenHashMap<>();
    final Int2ObjectOpenHashMap<String> logicalLocations = new Int2ObjectOpenHashMap<>();
    private final Map<String, String> defines;

    Glsl410Transform(Map<String, String> defines) {
        this.defines = new java.util.HashMap<>(defines);
    }

    String process(String src) {
        //Bindings are often macros defined inside the shader source itself
        Matcher d = DEFINE.matcher(src);
        while (d.find()) {
            this.defines.putIfAbsent(d.group(1), d.group(2));
        }
        //Declarations whose binding macro is undefined are left untouched, they are inside a disabled #ifdef branch
        // (the source is processed before the preprocessor runs), if they are not the glsl compiler reports it
        src = this.replaceAll(UNIFORM_BLOCK, src, m -> {
            String rest = this.extract(m.group(1), BINDING, binding -> this.blockBindings.put(m.group(2), binding));
            if (rest == null) return m.group(0);
            return layout(rest) + "uniform " + m.group(2) + " {";
        });
        src = this.replaceAll(UNIFORM_DECL, src, m -> {
            String args = m.group(1);
            String type = m.group(2);
            String name = m.group(3);
            args = this.extract(args, BINDING, binding -> this.samplerUnits.put(name, binding));
            if (args == null) return m.group(0);
            args = this.extract(args, LOCATION, location -> this.logicalLocations.put(location, name));
            if (args == null) return m.group(0);
            return layout(args) + "uniform " + type + " " + name + ";";
        });
        return src;
    }

    //Applies the recorded bindings, the program must have been linked
    int[] apply(int program) {
        int previous = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        for (var entry : this.samplerUnits.object2IntEntrySet()) {
            int location = glGetUniformLocation(program, entry.getKey());
            if (location != -1) {
                glUniform1i(location, entry.getIntValue());
            }
        }
        glUseProgram(previous);
        for (var entry : this.blockBindings.object2IntEntrySet()) {
            int index = glGetUniformBlockIndex(program, entry.getKey());
            if (index != GL_INVALID_INDEX) {
                glUniformBlockBinding(program, index, entry.getIntValue());
            }
        }
        int max = -1;
        for (int logical : this.logicalLocations.keySet()) {
            max = Math.max(max, logical);
        }
        int[] table = new int[max + 1];
        java.util.Arrays.fill(table, -1);
        for (var entry : this.logicalLocations.int2ObjectEntrySet()) {
            table[entry.getIntKey()] = glGetUniformLocation(program, entry.getValue());
        }
        return table;
    }

    private interface IntSink { void accept(int value); }
    private interface Replacer { String replace(Matcher m); }

    //Removes "key = value" from a layout argument list, reporting the resolved value, null if the value can not be resolved
    private String extract(String args, Pattern pattern, IntSink sink) {
        Matcher m = pattern.matcher(args);
        if (!m.find()) return args;
        Integer value = this.resolve(m.group(1));
        if (value == null) return null;
        sink.accept(value);
        String rest = (args.substring(0, m.start()) + args.substring(m.end())).trim();
        rest = rest.replaceAll("^\\s*,\\s*", "").replaceAll("\\s*,\\s*$", "").replaceAll(",\\s*,", ",");
        return rest;
    }

    private static String layout(String args) {
        return args.isBlank() ? "" : "layout(" + args.trim() + ") ";
    }

    //A literal, a macro, or a parenthesised sum of those
    private Integer resolve(String token) {
        token = token.trim();
        if (token.startsWith("(") && token.endsWith(")")) {
            int sum = 0;
            for (String part : token.substring(1, token.length()-1).split("\\+")) {
                Integer value = this.resolve(part);
                if (value == null) return null;
                sum += value;
            }
            return sum;
        }
        String value = token;
        for (int depth = 0; depth < 8; depth++) {
            try {
                return Integer.decode(value.trim());
            } catch (NumberFormatException e) {
                String next = this.defines.get(value.trim());
                if (next == null) break;
                value = next;
            }
        }
        return null;
    }

    private String replaceAll(Pattern pattern, String src, Replacer replacer) {
        Matcher m = pattern.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replacer.replace(m)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
