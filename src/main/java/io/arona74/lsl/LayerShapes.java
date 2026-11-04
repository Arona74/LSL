package io.arona74.lsl;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a block state is, as far as an LOD renderer needs to know: a floor layer and how tall, a
 * full cube, or the same layer at another height.
 *
 * <p>Everything is detected from shapes and properties rather than listed per mod, so AronaLayers-gen
 * layers, Conquest Reforged layers/slabs and vanilla snow are all handled the same way. Pure
 * Minecraft API (version differences go through {@link Compat}): no LOD-mod or loader type appears
 * here, so every version module shares it.
 *
 * <p>Callers cache the results per state id; nothing here is fast.
 */
public final class LayerShapes {
    private LayerShapes() {}

    private static final double EPS = 1.0e-3;

    /** The integer property a layer block keeps its height in: {@code layers} (vanilla, CR) or {@code layer}. */
    public static IntegerProperty layerProperty(Block block) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            if (property instanceof IntegerProperty ip
                && (property.getName().equals("layers") || property.getName().equals("layer"))) {
                return ip;
            }
        }
        return null;
    }

    /**
     * Height of a floor layer in eighths (1-8), or 0 when the state is not one.
     *
     * <p>A floor layer has a layer property, is not a plant (CR plants carry {@code layers} to know
     * the soil they stand on), and its outline covers the whole footprint from the bottom of the
     * block up to some height. That excludes vertical slabs, top slabs and wall pieces.
     */
    public static int layerEighths(BlockState state) {
        Block block = state.getBlock();
        if (layerProperty(block) == null) return 0;
        if (Compat.isPlantBase(block)) return 0;
        AABB box = outlineBounds(state);
        if (box == null) return 0;
        if (box.minY > EPS || box.minX > EPS || box.minZ > EPS || box.maxX < 1 - EPS || box.maxZ < 1 - EPS) return 0;
        int eighths = (int) Math.round(box.maxY * 8);
        return eighths >= 1 && eighths <= 8 ? eighths : 0;
    }

    /** A plain full cube: what the mipper counts as solid ground one block tall. */
    public static boolean isFullCube(BlockState state) {
        if (state.isAir()) return false;
        AABB box = outlineBounds(state);
        if (box == null) return false;
        return box.minX <= EPS && box.minY <= EPS && box.minZ <= EPS
            && box.maxX >= 1 - EPS && box.maxY >= 1 - EPS && box.maxZ >= 1 - EPS;
    }

    /**
     * The state of the same layer block whose height is closest to {@code eighths}, keeping every
     * other property. With {@code waterlogged}, also sets WATERLOGGED when the block has it.
     * Returns null when the block has no layer property.
     */
    public static BlockState withEighths(BlockState state, int eighths, boolean waterlogged) {
        IntegerProperty property = layerProperty(state.getBlock());
        if (property == null) return null;
        BlockState best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (Integer value : property.getPossibleValues()) {
            BlockState candidate = state.setValue(property, (Integer) value);
            int e = layerEighths(candidate);
            if (e == 0) continue;
            int diff = Math.abs(e - eighths);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        if (best != null && waterlogged && best.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            best = best.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        }
        return best;
    }

    private static AABB outlineBounds(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return shape.isEmpty() ? null : shape.bounds();
        } catch (Throwable t) {
            // A modded block that cannot answer outside a real level is simply not classified.
            return null;
        }
    }
}
