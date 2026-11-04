package io.arona74.lsl;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Layer questions the LOD hooks ask, answered by AronaLayers-gen when it is installed and by shape
 * detection ({@link LayerShapes}) otherwise.
 *
 * <p>Gen knows things shapes can't tell: its configured mappings (which full block a layer stands
 * for, even when the names don't match), VanillaLayer+ 3D layers, and wall pieces. So with gen
 * present, its {@code io.arona74.aronalayersgen.api.LayersApi} is used, reached by reflection so
 * neither mod needs the other's jar to build. Without gen (a plain Conquest Reforged world), a floor
 * layer is whatever {@link LayerShapes#layerEighths} accepts, and its full block is found by name.
 *
 * <p>Nothing here caches; the DH and Voxy hooks cache per state.
 */
public final class LayerInfo {
    private LayerInfo() {}

    private static final String GEN_API = "io.arona74.aronalayersgen.api.LayersApi";

    private static final MethodHandle IS_LAYER_BLOCK;
    private static final MethodHandle HAS_LAYER_PROPERTY;
    private static final MethodHandle IS_FLOOR_LAYER;
    private static final MethodHandle IS_WALL_PIECE;
    private static final MethodHandle LAYER_COUNT;
    private static final MethodHandle FULL_BLOCK_OF;

    static {
        MethodHandle isLayerBlock = null, hasLayerProperty = null, isFloorLayer = null, isWallPiece = null,
            layerCount = null, fullBlockOf = null;
        try {
            Class<?> api = Class.forName(GEN_API);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isLayerBlock = lookup.findStatic(api, "isLayerBlock", MethodType.methodType(boolean.class, Block.class));
            hasLayerProperty = lookup.findStatic(api, "hasLayerProperty", MethodType.methodType(boolean.class, BlockState.class));
            isFloorLayer = lookup.findStatic(api, "isFloorLayer", MethodType.methodType(boolean.class, BlockState.class));
            isWallPiece = lookup.findStatic(api, "isWallPiece", MethodType.methodType(boolean.class, BlockState.class));
            layerCount = lookup.findStatic(api, "layerCount", MethodType.methodType(int.class, BlockState.class));
            fullBlockOf = lookup.findStatic(api, "fullBlockOf", MethodType.methodType(Block.class, Block.class));
            Lsl.LOGGER.info("[LSL] using AronaLayers-gen's layer knowledge");
        } catch (ClassNotFoundException e) {
            // No gen (or a gen older than its API): shape detection.
        } catch (ReflectiveOperationException | LinkageError e) {
            Lsl.LOGGER.warn("[LSL] AronaLayers-gen's LayersApi is not the expected shape ({}): using shape detection", e.toString());
            isLayerBlock = null;
        }
        boolean complete = isLayerBlock != null && hasLayerProperty != null && isFloorLayer != null
            && isWallPiece != null && layerCount != null && fullBlockOf != null;
        IS_LAYER_BLOCK = complete ? isLayerBlock : null;
        HAS_LAYER_PROPERTY = complete ? hasLayerProperty : null;
        IS_FLOOR_LAYER = complete ? isFloorLayer : null;
        IS_WALL_PIECE = complete ? isWallPiece : null;
        LAYER_COUNT = complete ? layerCount : null;
        FULL_BLOCK_OF = complete ? fullBlockOf : null;
    }

    /** Whether answers come from AronaLayers-gen. */
    public static boolean fromGen() {
        return IS_LAYER_BLOCK != null;
    }

    /** Carries a layer property at all (plants can too: pair with {@link #isLayerBlock}). Cheap pre-filter. */
    public static boolean hasLayerProperty(BlockState state) {
        if (fromGen()) {
            try {
                return (boolean) HAS_LAYER_PROPERTY.invokeExact(state);
            } catch (Throwable t) {
                return false;
            }
        }
        return LayerShapes.layerProperty(state.getBlock()) != null;
    }

    /** A real layer block (not a plant or decor that merely carries a layers property). */
    public static boolean isLayerBlock(BlockState state) {
        if (fromGen()) {
            try {
                return (boolean) IS_LAYER_BLOCK.invokeExact(state.getBlock());
            } catch (Throwable t) {
                return false;
            }
        }
        return LayerShapes.layerEighths(state) != 0;
    }

    /** A layer standing against a wall (3D layers, sideways layers). Only gen can tell; false without it. */
    public static boolean isWallPiece(BlockState state) {
        if (fromGen()) {
            try {
                return (boolean) IS_WALL_PIECE.invokeExact(state);
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** A layer lying on the ground. */
    public static boolean isFloorLayer(BlockState state) {
        if (fromGen()) {
            try {
                return (boolean) IS_FLOOR_LAYER.invokeExact(state);
            } catch (Throwable t) {
                return false;
            }
        }
        return LayerShapes.layerEighths(state) != 0;
    }

    /** Height in eighths (1-8) of a layer state. */
    public static int layerCount(BlockState state) {
        if (fromGen()) {
            try {
                return (int) LAYER_COUNT.invokeExact(state);
            } catch (Throwable t) {
                return 1;
            }
        }
        int eighths = LayerShapes.layerEighths(state);
        return eighths == 0 ? 1 : eighths;
    }

    /** The whole block a layer stands for, or null when none is known. */
    public static Block fullBlockOf(Block layer) {
        if (fromGen()) {
            try {
                return (Block) FULL_BLOCK_OF.invokeExact(layer);
            } catch (Throwable t) {
                return null;
            }
        }
        return fullBlockByName(layer);
    }

    /**
     * How a layer looks drawn as a whole block: its full block, else the same layer at 8 layers
     * (a full-height layer keeps the layer's own top texture), else null.
     */
    public static BlockState fullAppearance(BlockState layer) {
        Block full = fullBlockOf(layer.getBlock());
        if (full != null) return full.defaultBlockState();
        BlockState eight = LayerShapes.withEighths(layer, 8, false);
        return eight != null && LayerShapes.layerEighths(eight) == 8 ? eight : null;
    }

    /** {@code ns:x_layer} / {@code ns:x_slab} -> {@code ns:x}, when that block exists. */
    private static Block fullBlockByName(Block layer) {
        String id = Compat.blockId(layer);
        if (id == null) return null;
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        for (String suffix : new String[]{"_layer", "_slab"}) {
            if (path.endsWith(suffix)) {
                Block full = Compat.blockFromId(namespace + ":" + path.substring(0, path.length() - suffix.length()));
                if (full != null && full != Blocks.AIR) return full;
            }
        }
        return null;
    }
}
