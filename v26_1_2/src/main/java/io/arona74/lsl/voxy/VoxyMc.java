package io.arona74.lsl.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * What the shared Voxy hooks (voxy/src) need from Minecraft 26.1.2: quads and models. Every module that
 * carries the hooks has its own VoxyMc with the same members. Only used once Voxy bakes models.
 */
public final class VoxyMc {
    private VoxyMc() {}


    /** A quad corner (26.x quads keep positions and packed UVs). */
    static Vector3fc position(BakedQuad quad, int vertex) {
        return quad.position(vertex);
    }

    static float u(BakedQuad quad, int vertex) {
        return UVPair.unpackU(quad.packedUV(vertex));
    }

    static float v(BakedQuad quad, int vertex) {
        return UVPair.unpackV(quad.packedUV(vertex));
    }

    /** A copy of {@code quad} with new corners, and new UVs unless {@code us} is null. */
    static BakedQuad with(BakedQuad quad, Vector3fc[] positions, float[] us, float[] vs) {
        long[] uv = new long[4];
        for (int k = 0; k < 4; k++) uv[k] = us == null ? quad.packedUV(k) : UVPair.pack(us[k], vs[k]);
        return new BakedQuad(positions[0], positions[1], positions[2], positions[3],
            uv[0], uv[1], uv[2], uv[3], quad.direction(), quad.materialInfo());
    }

    private static final Direction[] SIDES = {
        null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Every quad of the state's block model (all parts, all sides, fixed random seed). */
    static List<BakedQuad> blockQuads(BlockState state) {
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        List<BakedQuad> out = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) out.addAll(part.getQuads(side));
        }
        return out;
    }

    /** The state's random model offset at {@code pos} (plants). */
    static Vec3 offset(BlockState state, BlockPos pos) {
        return state.getOffset(pos);
    }
}
