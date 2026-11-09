package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text patches for Voxy's LOD shaders, applied as {@code ShaderLoader} reads each file.
 *
 * <ul>
 *   <li>{@code lod/quad_format.glsl}: the 32-bit fallback {@code extractStateId} reads 20 bits; it
 *       is narrowed to the 16 that model ids use (the 64-bit path already reads 16), and
 *       {@code lslExtractYShift} reads LSL's 3 bits at 42.</li>
 *   <li>{@code lod/quad_util.glsl}: {@code setupQuad} lowers the quad start by that many eighths
 *       (in LOD units, so it scales with the level like everything else).</li>
 *   <li>{@code lod/gl46/quads.frag}, shaderpack path only: a model flagged {@link #FLAT_FLAG}
 *       (layers, with Flat Layer Shading) reports its side faces to the shaderpack as facing up,
 *       so the pack lights a layer's sides like its top. Without a shaderpack Voxy's own side
 *       darkening is skipped through the model's "shaded" bit instead.</li>
 * </ul>
 *
 * <p>The vertex shader is Voxy's own even under Iris (Iris only patches the fragment stage), so this
 * covers both pipelines. The mesher writes shift bits only when {@link #usable()} found every
 * anchor in Voxy's sources, so a Voxy update that moves them disables the feature instead of
 * corrupting model ids.
 */
public final class ShaderPatch {
    private ShaderPatch() {}

    private static final String QUAD_FORMAT = "voxy:lod/quad_format.glsl";
    private static final String QUAD_UTIL = "voxy:lod/quad_util.glsl";

    private static final String STATE_ID_20 = "Eu32v(quad, 14, 32)";
    private static final String STATE_ID_16 = "Eu32v(quad, 10, 32)";
    private static final Pattern QUAD_START = Pattern.compile("(?m)^([ \\t]*)quadStart \\+= swizzelDataAxis\\(face>>1,.*;[ \\t]*$");

    private static final String EXTRACT_FN = """

        // ---- LSL (Layer Supported LOD): per-quad downward shift in eighths, bits 42-44 ----
        #ifdef QUAD_DATA_USE_64_BIT
        uint lslExtractYShift(uint64_t quad) { return uint(quad >> 42) & 7u; }
        #else
        uint lslExtractYShift(ivec2 quad) { return (uint(quad.y) >> 10u) & 7u; }
        #endif
        """;

    private static final String QUADS_FRAG = "voxy:lod/gl46/quads.frag";
    /** Model flagsA bit (Voxy uses 1, 2, 4, 8): "flat", side faces lit as up under shaderpacks. */
    public static final int FLAT_FLAG = 1 << 16;
    private static final Pattern FACE_FLIP = Pattern.compile("(?m)^([ \\t]*)face \\^= uint\\(.*;[ \\t]*$");

    private static volatile Boolean usable;

    /** Whether Voxy's shader sources still have every anchor the patch needs. */
    public static boolean usable() {
        Boolean u = usable;
        if (u == null) {
            String format = read("/assets/voxy/shaders/lod/quad_format.glsl");
            String util = read("/assets/voxy/shaders/lod/quad_util.glsl");
            u = format != null && util != null && format.contains(STATE_ID_20) && QUAD_START.matcher(util).find();
            if (!u) Lsl.LOGGER.warn("[LSL] Voxy shader anchors not found: plant offsets disabled");
            usable = u;
        }
        return u;
    }

    /** Patched source for {@code id}, or null to leave it untouched. */
    public static String patch(String id, String source) {
        if (source == null) return null;
        if (QUADS_FRAG.equals(id)) {
            if (!LslConfig.voxyFlatLayers) return null;
            Matcher m = FACE_FLIP.matcher(source);
            if (!m.find()) {
                Lsl.LOGGER.warn("[LSL] Voxy fragment shader anchor not found: flat layer shading stays off under shaderpacks");
                return null;
            }
            String insert = m.group() + "\n" + m.group(1)
                + "if ((model.flagsA & " + FLAT_FLAG + "u) != 0u && face > 1u) face = 1u; // LSL: flat layer sides";
            return source.substring(0, m.start()) + insert + source.substring(m.end());
        }
        if (!usable()) return null;
        if (QUAD_FORMAT.equals(id)) {
            return source.replace(STATE_ID_20, STATE_ID_16) + EXTRACT_FN;
        }
        if (QUAD_UTIL.equals(id)) {
            Matcher m = QUAD_START.matcher(source);
            if (!m.find()) return null;
            String indent = m.group(1);
            String insert = m.group() + "\n" + indent + "quadStart.y -= float(lslExtractYShift(rawQuad)) * 0.125; // LSL";
            return source.substring(0, m.start()) + insert + source.substring(m.end());
        }
        return null;
    }

    /**
     * One of Voxy's shader files. On NeoForge every mod is its own module, and a class only sees its
     * own module's files, so this asks Voxy's own class first, then the game's class loader.
     */
    private static String read(String path) {
        String text = read(() -> Class.forName("me.cortex.voxy.client.core.gl.shader.ShaderLoader", false,
            ShaderPatch.class.getClassLoader()).getResourceAsStream(path));
        if (text == null) text = read(() -> ShaderPatch.class.getResourceAsStream(path));
        if (text == null) text = read(() -> Thread.currentThread().getContextClassLoader().getResourceAsStream(path.substring(1)));
        if (text == null) text = read(() -> ShaderPatch.class.getClassLoader().getResourceAsStream(path.substring(1)));
        return text == null ? null : text.replace("\r\n", "\n");
    }

    private interface Opener {
        InputStream open() throws Exception;
    }

    private static String read(Opener opener) {
        try (InputStream in = opener.open()) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }
}
