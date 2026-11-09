package io.arona74.lsl.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * What the shared Voxy hooks (voxy/src) need from Minecraft 1.21.1: quads and models. Every module that
 * carries the hooks has its own VoxyMc with the same members. Only used once Voxy bakes models.
 */
public final class VoxyMc {
    private VoxyMc() {}

    //
    // A 1.21.1 BakedQuad is a packed vertex array in the block format, 8 ints per vertex (x, y, z as
    // float bits, colour, u, v as float bits, light, normal).

    private static final int STRIDE = 8;
    private static final int UV = 4;

    static Vector3fc position(BakedQuad quad, int vertex) {
        int[] data = quad.getVertices();
        int base = vertex * STRIDE;
        return new Vector3f(Float.intBitsToFloat(data[base]), Float.intBitsToFloat(data[base + 1]),
            Float.intBitsToFloat(data[base + 2]));
    }

    static float u(BakedQuad quad, int vertex) {
        return Float.intBitsToFloat(quad.getVertices()[vertex * STRIDE + UV]);
    }

    static float v(BakedQuad quad, int vertex) {
        return Float.intBitsToFloat(quad.getVertices()[vertex * STRIDE + UV + 1]);
    }

    /** A copy of {@code quad} with new corners, and new UVs unless {@code us} is null (colour, light and normal kept). */
    static BakedQuad with(BakedQuad quad, Vector3fc[] positions, float[] us, float[] vs) {
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

    private static final Direction[] SIDES = {
        null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Every quad of the state's block model (all sides, fixed random seed). */
    static List<BakedQuad> blockQuads(BlockState state) {
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> out = new ArrayList<>();
        for (Direction side : SIDES) out.addAll(model.getQuads(state, side, new SingleThreadedRandomSource(42L)));
        return out;
    }

    /** The state's random model offset at {@code pos} (plants). */
    static Vec3 offset(BlockState state, BlockPos pos) {
        return state.getOffset(EmptyBlockGetter.INSTANCE, pos);
    }
}
