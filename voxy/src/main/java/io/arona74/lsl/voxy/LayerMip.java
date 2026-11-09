package io.arona74.lsl.voxy;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Layer-aware replacement for Voxy's 2x2x2 mip (every LOD level is built by it, from one Minecraft
 * section down to one voxel).
 *
 * <p>Voxy keeps the child with the highest light dampening, then the top-most. A partial layer
 * dampens less than the block under it, so a cell holding "stone below, 4/8 dirt layer above" keeps
 * the stone: past LOD0 the layers vanish, the terrain rounds up to whole blocks and the layer's top
 * texture (grass, sand, snow) gives way to the side of the block underneath. A cell holding "layer
 * below, plant above" keeps the plant instead, leaving a hole.
 *
 * <p>Here, when a layer is the top surface of the tallest of the cell's four columns, the cell
 * becomes that same layer block at the column's height relative to the cell: stone + 4/8 layer is
 * 12/16 of the cell, so a 6/8 layer state. Each LOD level mips the previous one, so the heights carry
 * all the way up. Cells whose top is anything else are left to Voxy.
 */
public final class LayerMip {
    private LayerMip() {}

    /** "Not handled": never a real result, since this never returns air. */
    public static final long PASS = 0L;

    public static long mip(long i000, long i100, long i001, long i101,
                           long i010, long i110, long i011, long i111, Mapper mapper) {
        // Fast reject: no layer anywhere in the cell (by far the common case).
        BlockTables t = BlockTables.of(mapper);
        int f000 = flags(t, i000), f100 = flags(t, i100), f001 = flags(t, i001), f101 = flags(t, i101);
        int f010 = flags(t, i010), f110 = flags(t, i110), f011 = flags(t, i011), f111 = flags(t, i111);
        if (((f000 | f100 | f001 | f101 | f010 | f110 | f011 | f111) & BlockTables.LAYER_MASK) == 0) return PASS;

        // Columns (x, z): bottom child is y=0, top child is y=1 (names are I<x><y><z>).
        int best = -1;
        long bestChild = 0;
        boolean bestIsLayer = false;
        boolean fluidAbove = false;

        long[] bottoms = {i000, i100, i001, i101};
        long[] tops = {i010, i110, i011, i111};
        int[] fb = {f000, f100, f001, f101};
        int[] ft = {f010, f110, f011, f111};
        for (int c = 0; c < 4; c++) {
            int hb = height(fb[c]);
            int col;
            long topChild;
            boolean topIsLayer;
            boolean fluid;
            if (hb == 8) {
                int ht = height(ft[c]);
                col = 8 + ht;
                topIsLayer = BlockTables.layerEighths(ft[c]) != 0;
                topChild = topIsLayer ? tops[c] : bottoms[c];
                // Anything above a layer in the top slot lies in the next cell up, not this one.
                fluid = false;
            } else {
                // Water (or a waterlogged plant) above a bottom layer shares this cell with it:
                // keep it by choosing the waterlogged variant of the layer.
                col = hb;
                topIsLayer = BlockTables.layerEighths(fb[c]) != 0;
                topChild = bottoms[c];
                fluid = topIsLayer && (ft[c] & BlockTables.HAS_FLUID) != 0;
            }
            if (col > best || (col == best && topIsLayer && !bestIsLayer)) {
                best = col;
                bestChild = topChild;
                bestIsLayer = topIsLayer;
                fluidAbove = fluid;
            }
        }
        if (!bestIsLayer || best <= 0) return PASS;

        // Height relative to the 2-block cell, in eighths, rounded up so a thin layer never vanishes.
        int eighths = Math.min(8, (best + 1) >>> 1);
        int blockId = Mapper.getBlockId(bestChild);
        int variant = t.variant(blockId, eighths, fluidAbove);
        if (variant < 0) return PASS; // no such height (or can't keep the water): let Voxy pick
        return Mapper.withBlockBiome(bestChild, variant, Mapper.getBiomeId(bestChild));
    }

    private static int flags(BlockTables t, long child) {
        if (Mapper.isAir(child)) return 0;
        return t.info(Mapper.getBlockId(child));
    }

    /** Height a child adds to its column, in eighths: 8 for a full cube, the layer's height, else 0. */
    private static int height(int flags) {
        if ((flags & BlockTables.FULL) != 0) return 8;
        return BlockTables.layerEighths(flags);
    }
}
