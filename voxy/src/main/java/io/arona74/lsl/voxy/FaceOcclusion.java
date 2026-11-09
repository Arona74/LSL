package io.arona74.lsl.voxy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Stops partial blocks from hiding their neighbours' faces in Voxy.
 *
 * <p>Voxy decides whether a baked face occludes the face next to it from the baked texture alone:
 * drawn, close to the block edge and covering more than 90% of the face. A Conquest Reforged layer
 * whose model fills the whole side (the grass fringe and {@code grass_block_ext} blades of overgrown
 * layers, for one) passes, although the block is only partly tall. The mesher then drops the solid
 * neighbour's face behind it, and the LOD shows a hole you can see through. The layer mip makes this
 * common, since distant cells become layer states.
 *
 * <p>Here a face keeps its "occludes" flag only when
 * <ol>
 *   <li>the block's outline shape covers that face in full, and</li>
 *   <li>the four faces around it are drawn flush with the block edge (depth under Voxy's own 0.1
 *       "close to the edge" limit). Voxy draws a non-cube block as one flat quad per side at that
 *       side's average depth, so a stair becomes a box about 3/4 tall: its back face covers the whole
 *       face by shape, but the box Voxy draws doesn't, and the strip of the neighbour's face it hid
 *       showed the sky. A layer's bottom face keeps hiding the ground (its sides sit on the edge).</li>
 * </ol>
 * A block with any face cleared is no longer "fully opaque".
 *
 * <p>Stairs: Voxy stores every {@code StairBlock} (Conquest Reforged's stairs extend it) as a full
 * cube of its base block, which looks better at a distance. The cube's faces then hid the block
 * behind the stair, yet near the edge of the normal render distance the game draws the real stair
 * there, and the hidden face showed as sky through the stair's open part. Such a stand-in never
 * hides a neighbour's face. All stairs of a material share one model (see {@link #dedupKey}), so
 * the rule can't depend on the stair's facing: every face is cleared.
 *
 * <p>The outline, not the
 * occlusion shape, is checked on purpose: leaves (and some glass) have an empty occlusion shape, and
 * using it would stop Voxy culling inside every canopy. For layers the two agree.
 *
 * <p>Same rule as AronaLayers-gen's Voxy fork (commit 4c0ab6a5, "CR changes for TOC server").
 */
public final class FaceOcclusion {
    private FaceOcclusion() {}

    private static final long FULLY_OPAQUE = 1L << (48 + 6);
    /** Voxy's own limit for a face "close to the block edge" (ModelFactory: offset < 0.1). */
    private static final float FLUSH = 0.1f;
    private static final Direction[] FACES = new Direction[6];

    static {
        for (int i = 0; i < 6; i++) FACES[i] = Direction.from3DDataValue(i);
    }

    /**
     * Voxy's model metadata with the occlusion bits of faces the shape doesn't cover cleared.
     * Layout (ModelFactory): one byte per face (face index = Direction 3D data value), 0xFF = no
     * face, bit 0 = occludes; bit 54 = fully opaque. {@code depths} is Voxy's per-face depth
     * (same face order), -1 for a face with nothing drawn.
     */
    public static long correct(long metadata, BlockState state, float[] depths) {
        return correct(metadata, state, state, depths);
    }

    /**
     * As {@link #correct(long, BlockState, float[])}, with the outline taken from {@code shapeState}:
     * the state the world holds, when Voxy baked a stand-in for it ({@code bakedState}, used when
     * the real state is unknown).
     */
    public static long correct(long metadata, BlockState bakedState, BlockState shapeState, float[] depths) {
        if (isStandIn(bakedState, shapeState)) {
            for (int face = 0; face < 6; face++) {
                int shift = face * 8;
                if (((metadata >>> shift) & 0xFF) != 0xFF) metadata &= ~(1L << shift);
            }
            return metadata & ~FULLY_OPAQUE;
        }
        BlockState state = shapeState != null ? shapeState : bakedState;
        VoxelShape shape;
        try {
            shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Throwable t) {
            return metadata; // a block that can't answer outside a level keeps Voxy's own guess
        }
        boolean cleared = false;
        for (int face = 0; face < 6; face++) {
            int shift = face * 8;
            long faceByte = (metadata >>> shift) & 0xFF;
            if (faceByte == 0xFF || (faceByte & 1) == 0) continue;
            if (!Block.isFaceFull(shape, FACES[face]) || !sidesFlush(face, depths)) {
                metadata &= ~(1L << shift);
                cleared = true;
            }
        }
        if (cleared) metadata &= ~FULLY_OPAQUE;
        return metadata;
    }

    /** Voxy baked another block in place of the world's one (a stair as its base block). */
    public static boolean isStandIn(BlockState bakedState, BlockState realState) {
        return realState != null && bakedState != null && realState.getBlock() != bakedState.getBlock();
    }

    /**
     * The key Voxy deduplicates a baked model under. A stand-in bakes to the very pictures of its
     * base block; under the plain key it would share that block's model and culling, so it gets a
     * key of its own, shared by every stand-in that bakes the same.
     */
    public static Object dedupKey(Object entry, BlockState bakedState, BlockState realState) {
        return isStandIn(bakedState, realState) ? new StandInKey(entry) : entry;
    }

    private record StandInKey(Object entry) {}

    /** The four faces around {@code face} (all but it and its opposite) are drawn at the block edge. */
    private static boolean sidesFlush(int face, float[] depths) {
        if (depths == null || depths.length < 6) return true;
        int axis = face >> 1;
        for (int other = 0; other < 6; other++) {
            if (other >> 1 == axis) continue;
            float depth = depths[other];
            if (depth < 0 || depth >= FLUSH) return false;
        }
        return true;
    }
}
