package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts quads bigger than a block into small pieces before Voxy bakes them.
 *
 * <p>Voxy's software rasterizer (the one that turns a block model into its six face pictures) works
 * in fixed point with about ±512 of range, and a triangle's edge function is the product of two side
 * lengths in pixels (16 per block). A quad much over a block, such as Conquest Reforged's 2x2 lily
 * pads and duckweed or its 4-block reeds, overflows it: the triangle's area comes out wrong and it is
 * dropped or drawn broken. So the pads were missing (only the small flower was left) and the reeds
 * full of holes. Pieces of at most half a block stay far inside the range; pieces lying wholly
 * outside the block are dropped, since Voxy keeps only what is inside it anyway.
 *
 * <p>Position and UV are interpolated bilinearly over the quad, exact for the parallelograms models
 * are made of. Option {@code voxy_split_large_quads}.
 */
public final class QuadSplit {
    private QuadSplit() {}

    private static final float MAX_PIECE = 0.5f;
    private static final float TOO_BIG = 1.0f;
    private static final float EPS = 1.0e-4f;

    public static List<BakedQuad> split(List<BakedQuad> quads) {
        if (!LslConfig.voxySplitLargeQuads || quads.isEmpty()) return quads;
        List<BakedQuad> out = null;
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad q = quads.get(i);
            if (!isBig(q)) {
                if (out != null) out.add(q);
                continue;
            }
            if (out == null) out = new ArrayList<>(quads.subList(0, i));
            cut(q, out);
        }
        return out == null ? quads : out;
    }

    private static boolean isBig(BakedQuad q) {
        for (int a = 0; a < 3; a++) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (int v = 0; v < 4; v++) {
                float c = coord(QuadData.position(q, v), a);
                min = Math.min(min, c);
                max = Math.max(max, c);
            }
            if (max - min > TOO_BIG) return true;
        }
        return false;
    }

    private static void cut(BakedQuad q, List<BakedQuad> out) {
        Vector3fc p0 = QuadData.position(q, 0), p1 = QuadData.position(q, 1), p2 = QuadData.position(q, 2), p3 = QuadData.position(q, 3);
        int ns = Math.max(1, (int) Math.ceil(Math.max(p0.distance(p1), p3.distance(p2)) / MAX_PIECE));
        int nt = Math.max(1, (int) Math.ceil(Math.max(p0.distance(p3), p1.distance(p2)) / MAX_PIECE));
        float[] u = new float[4], v = new float[4];
        for (int k = 0; k < 4; k++) {
            u[k] = QuadData.u(q, k);
            v[k] = QuadData.v(q, k);
        }
        for (int j = 0; j < nt; j++) {
            float t0 = (float) j / nt, t1 = (float) (j + 1) / nt;
            for (int i = 0; i < ns; i++) {
                float s0 = (float) i / ns, s1 = (float) (i + 1) / ns;
                Vector3f a = at(p0, p1, p2, p3, s0, t0), b = at(p0, p1, p2, p3, s1, t0);
                Vector3f c = at(p0, p1, p2, p3, s1, t1), d = at(p0, p1, p2, p3, s0, t1);
                if (outside(a, b, c, d)) continue;
                float[] pu = {lerpU(u, s0, t0), lerpU(u, s1, t0), lerpU(u, s1, t1), lerpU(u, s0, t1)};
                float[] pv = {lerpU(v, s0, t0), lerpU(v, s1, t0), lerpU(v, s1, t1), lerpU(v, s0, t1)};
                out.add(QuadData.with(q, new Vector3fc[]{a, b, c, d}, pu, pv));
            }
        }
    }

    /** Corner order p0, p1, p2, p3: s runs p0 to p1 (and p3 to p2), t runs p0 to p3. */
    private static Vector3f at(Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3, float s, float t) {
        Vector3f bottom = new Vector3f(p0).lerp(p1, s);
        Vector3f top = new Vector3f(p3).lerp(p2, s);
        return bottom.lerp(top, t);
    }

    /** One texture coordinate at (s, t), interpolated like the corners. */
    private static float lerpU(float[] c, float s, float t) {
        float bottom = c[0] + (c[1] - c[0]) * s, top = c[3] + (c[2] - c[3]) * s;
        return bottom + (top - bottom) * t;
    }

    /** All four corners beyond one face of the block. */
    private static boolean outside(Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        for (int axis = 0; axis < 3; axis++) {
            float ca = coord(a, axis), cb = coord(b, axis), cc = coord(c, axis), cd = coord(d, axis);
            if (ca < -EPS && cb < -EPS && cc < -EPS && cd < -EPS) return true;
            if (ca > 1 + EPS && cb > 1 + EPS && cc > 1 + EPS && cd > 1 + EPS) return true;
        }
        return false;
    }

    private static float coord(Vector3fc p, int axis) {
        return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
    }
}
