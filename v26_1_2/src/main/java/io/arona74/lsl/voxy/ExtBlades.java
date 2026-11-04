package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaves grass "ext blades" out of Voxy's baked models.
 *
 * <p>Conquest Reforged's grass layers and grass blocks add diagonal blade overlays
 * ({@code grass_block_ext}) with {@code cullface: up}. Voxy bakes a block by looking at it from each
 * of the six sides, so from above those blades turn into a dark cross printed on every top face.
 * Repeated block after block across a meadow, that cross is the moire pattern seen on distant
 * layers. The flat surfaces stay: a layer's top has no cullface, and the top of a full block is
 * axis-aligned.
 *
 * <p>Only diagonal quads in the {@code up} bucket are dropped, and never from plants (whose crosses
 * are the plant). Same idea as the AronaLayers Voxy fork (commit 4c0ab6a5), which dropped the whole
 * {@code up} bucket for {@code conquest:grass_block_layer}.
 */
public final class ExtBlades {
    private ExtBlades() {}

    private static final float EPS = 1.0e-4f;

    public static List<BakedQuad> filter(List<BakedQuad> quads, Direction direction, BlockState state) {
        if (direction != Direction.UP || !LslConfig.voxyHideExtBlades || quads.isEmpty()) return quads;
        if (PlantLook.isPlant(state)) return quads;
        List<BakedQuad> kept = null;
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            if (isDiagonal(quad)) {
                if (kept == null) kept = new ArrayList<>(quads.subList(0, i));
            } else if (kept != null) {
                kept.add(quad);
            }
        }
        return kept == null ? quads : kept;
    }

    /** No coordinate is shared by all four corners: the quad doesn't lie in a block-aligned plane. */
    private static boolean isDiagonal(BakedQuad quad) {
        Vector3fc a = quad.position(0);
        boolean sameX = true, sameY = true, sameZ = true;
        for (int v = 1; v < 4; v++) {
            Vector3fc b = quad.position(v);
            sameX &= Math.abs(b.x() - a.x()) < EPS;
            sameY &= Math.abs(b.y() - a.y()) < EPS;
            sameZ &= Math.abs(b.z() - a.z()) < EPS;
        }
        return !(sameX || sameY || sameZ);
    }
}
