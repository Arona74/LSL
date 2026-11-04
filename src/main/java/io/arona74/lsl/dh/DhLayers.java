package io.arona74.lsl.dh;

import io.arona74.lsl.LayerInfo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which blocks Distant Horizons should treat as layers, shared by the whole-block rounding
 * (DistantHorizonsCompat) and the exact-height meshes (DhLayerMesh). Names no DH type. The layer
 * knowledge itself comes from {@link LayerInfo} (AronaLayers-gen when installed, shapes otherwise).
 */
public final class DhLayers {
    private DhLayers() {}

    private static final Map<BlockState, Integer> PARTIAL = new ConcurrentHashMap<>();
    private static final Map<BlockState, BlockState> APPEARANCE = new ConcurrentHashMap<>();

    /**
     * Height in eighths of a partial floor layer (1-7), or 0 when the state is not one: not a layer
     * block, a wall piece, a full 8-layer block, vanilla snow (handled apart), or a block that merely
     * carries a layers property (Conquest Reforged plants).
     */
    public static int partialEighths(BlockState state) {
        // DH asks for every column it stores and every section it draws: cached per state.
        return PARTIAL.computeIfAbsent(state, DhLayers::computePartialEighths);
    }

    private static int computePartialEighths(BlockState state) {
        if (state.getBlock() == Blocks.SNOW || !LayerInfo.isFloorLayer(state)) return 0;
        if (!LayerInfo.isLayerBlock(state)) return 0;
        int count = LayerInfo.layerCount(state);
        return count >= 1 && count <= 7 ? count : 0;
    }

    /** How the layer looks from afar: its full block (or full-height layer), else the layer itself. */
    public static BlockState appearanceOf(BlockState layer) {
        return APPEARANCE.computeIfAbsent(layer, s -> {
            BlockState full = LayerInfo.fullAppearance(s);
            return full != null ? full : s;
        });
    }

    public static boolean isWaterlogged(BlockState state) {
        return state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER);
    }
}
