package io.arona74.lsl.voxy;

import io.arona74.lsl.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/** Plant classification for the Voxy plant drop (26.x offset API, so kept out of the shared code). */
final class VoxyShapes {
    private VoxyShapes() {}

    /**
     * How far the block's own offset function lowers it, in eighths (0-7).
     *
     * <p>Conquest Reforged lowers its plants (and some decor) onto the layer below through the
     * vanilla offset function, driven by the plant's own {@code layers} value: layers 1 drops by
     * 7/8, layers 7 by 1/8. Voxy bakes models without ever calling the offset function, so those
     * plants float in the LODs. Only the state-driven part is kept: the vertical jitter some offset
     * types add is position-random and never positive, so the highest offset over a few positions is
     * the state's own.
     */
    static int selfOffsetEighths(BlockState state) {
        if (!state.hasOffsetFunction()) return 0;
        double maxY = Double.NEGATIVE_INFINITY;
        try {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    Vec3 offset = state.getOffset(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, new BlockPos(x * 7 + 1, 0, z * 13 + 3));
                    maxY = Math.max(maxY, offset.y);
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        if (!(maxY < 0)) return 0;
        int eighths = (int) Math.round(-maxY * 8);
        return Math.max(0, Math.min(7, eighths));
    }

    /**
     * A plant AronaLayers-gen would lower onto a partial layer below it (its "plant grounding"):
     * a vegetation block, or ground decoration with a native offset and no collision, that does not
     * already position itself (a layer property or CR's {@code offset_toggle}).
     */
    static boolean isGroundable(BlockState state) {
        Block block = state.getBlock();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            String name = property.getName();
            if (property instanceof IntegerProperty && (name.equals("layers") || name.equals("layer"))) return false;
            if (property instanceof BooleanProperty && name.equals("offset_toggle")) return false;
        }
        if (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) return false;
        if (Compat.isPlantBase(block)) return true;
        if (!state.hasOffsetFunction()) return false;
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
