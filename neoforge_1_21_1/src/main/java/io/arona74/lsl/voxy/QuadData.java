package io.arona74.lsl.voxy;

import net.minecraft.client.renderer.block.model.BakedQuad;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Version seam for quads (Minecraft 1.21.1): a BakedQuad is a packed vertex array in the block
 * format, 8 ints per vertex (x, y, z as float bits, colour, u, v as float bits, light, normal).
 * 26.x has a record with positions and packed UVs instead; the code using quads goes through here.
 */
public final class QuadData {
    private QuadData() {}

    private static final int STRIDE = 8;
    private static final int UV = 4;

    public static Vector3f position(BakedQuad quad, int vertex) {
        int[] data = quad.getVertices();
        int base = vertex * STRIDE;
        return new Vector3f(Float.intBitsToFloat(data[base]), Float.intBitsToFloat(data[base + 1]),
            Float.intBitsToFloat(data[base + 2]));
    }

    public static float u(BakedQuad quad, int vertex) {
        return Float.intBitsToFloat(quad.getVertices()[vertex * STRIDE + UV]);
    }

    public static float v(BakedQuad quad, int vertex) {
        return Float.intBitsToFloat(quad.getVertices()[vertex * STRIDE + UV + 1]);
    }

    /** A copy of {@code quad} with new corners and UVs (colour, light and normal kept). */
    public static BakedQuad with(BakedQuad quad, Vector3fc[] positions, float[] us, float[] vs) {
        int[] data = quad.getVertices().clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * STRIDE;
            data[base] = Float.floatToRawIntBits(positions[vertex].x());
            data[base + 1] = Float.floatToRawIntBits(positions[vertex].y());
            data[base + 2] = Float.floatToRawIntBits(positions[vertex].z());
            if (us != null) {
                data[base + UV] = Float.floatToRawIntBits(us[vertex]);
                data[base + UV + 1] = Float.floatToRawIntBits(vs[vertex]);
            }
        }
        return new BakedQuad(data, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(),
            quad.hasAmbientOcclusion());
    }
}
