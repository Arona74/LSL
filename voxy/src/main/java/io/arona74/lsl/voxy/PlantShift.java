package io.arona74.lsl.voxy;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Lowers plants onto the layer they stand on, at LOD0.
 *
 * <p>Voxy bakes one model per block state into its own block space, so geometry can never reach
 * below the block's cell. Plants on a partial layer must, so the drop is applied per quad on the GPU
 * instead: the mesher writes a "lower by N eighths" value into bits 42-44 of the quad (inside the
 * 20-bit state field, above the 16 bits model ids actually use) and the patched vertex shader moves
 * the quad down by it ({@link ShaderPatch}). Greedy meshing compares the whole quad word, so only
 * plants with the same drop merge.
 *
 * <p>The drop comes from:
 * <ul>
 *   <li>the block's own offset function (Conquest Reforged plants, keyed on their {@code layers}),
 *       needing no neighbour, or</li>
 *   <li>with grounding on, the partial layer under an ordinary plant, as AronaLayers-gen draws it,
 *       through the same plant's lower half for double plants. Only within the section: a plant
 *       on the bottom row of a 32-block section is left as it is (proof-of-concept limit).</li>
 * </ul>
 * Blocks holding a fluid are skipped: the fluid quad shares the word and would sink with the plant.
 */
public final class PlantShift {
    private PlantShift() {}

    static final int SHIFT_BIT = 42;
    private static final int ROW = 32 * 32;       // index step for one block down (y<<10)
    private static final int MAX_COLUMN = 4;      // plant halves walked down to find the soil
    private static final long CONTAINS_FLUID = 8L << 48; // Voxy model metadata, "has a fluid state"

    /**
     * @param raw     the section's raw Voxy mapping ids, 32^3, index (y<<10)|(z<<5)|x
     * @param section RenderDataFactory's prepared data: [2i] = partial quad word, [2i+1] = model metadata
     */
    public static void apply(long[] raw, long[] section, BlockTables tables) {
        final boolean ground = VoxyGate.grounding;
        for (int i = 0; i < 32 * 32 * 32; i++) {
            long block = raw[i];
            if (Mapper.isAir(block)) continue;
            long meta = section[i * 2 + 1];
            if (meta == 0 || (meta & CONTAINS_FLUID) != 0) continue;
            int id = Mapper.getBlockId(block);
            int info = tables.info(id);
            int shift = BlockTables.selfShift(info);
            if (shift == 0 && ground && (info & BlockTables.GROUNDABLE) != 0) {
                shift = groundShift(raw, i, id, tables);
            }
            if (shift != 0) {
                section[i * 2] |= ((long) shift) << SHIFT_BIT;
            }
        }
    }

    private static int groundShift(long[] raw, int index, int plantId, BlockTables tables) {
        int key = tables.blockKey(plantId);
        int j = index;
        for (int step = 0; step < MAX_COLUMN; step++) {
            j -= ROW;
            if (j < 0) return 0; // soil is in the section below: not visible from here
            long below = raw[j];
            if (Mapper.isAir(below)) return 0;
            int belowId = Mapper.getBlockId(below);
            if (tables.blockKey(belowId) == key) continue; // lower half / stacked part of the same plant
            int layer = BlockTables.layerEighths(tables.info(belowId));
            return layer != 0 && layer < 8 ? 8 - layer : 0;
        }
        return 0;
    }
}
