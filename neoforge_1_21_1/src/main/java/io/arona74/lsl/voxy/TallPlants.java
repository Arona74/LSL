package io.arona74.lsl.voxy;

import io.arona74.lsl.Compat;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.mixin.voxy.RenderDataFactoryInvoker;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Plants taller than their block, drawn at full height in Voxy's LODs.
 *
 * <p>Conquest Reforged's reeds (giant_wild_reeds, cattail_reeds, ...) are a single block whose model
 * reaches up to 4 blocks high; the visible height is in the texture. Voxy bakes each block state into
 * its own 1x1x1 cell and cuts the rest off, so only the bottom slice showed, sunk into the layer by
 * the plant offset.
 *
 * <p>Here the empty cells above such a plant get "slices" of it: the same model baked 1, 2 or 3
 * blocks lower, so each cell holds the next part. A slice is a model like any other, under a block
 * id Voxy's mapper never hands out ({@link BlockTables#slice}): the world data is never touched, the
 * slices exist only in the mesh. They are added while meshing (LOD0 only), after Voxy has prepared
 * the section, and carry the plant's drop, light and biome. A slice that is not baked yet fails the
 * mesh the way any unbaked block does, and Voxy bakes it and retries.
 *
 * <p>Limits: LOD1 and beyond show the bottom slice only; a cell above the plant that isn't empty
 * stops it there.
 */
public final class TallPlants {
    private TallPlants() {}

    private static final int ROW = 32 * 32;
    private static final long LIGHT = 0xFFL << 56;
    private static final long SHIFT_BITS = 0x7L << PlantShift.SHIFT_BIT;
    /** Voxy metadata with all six face bytes 0xFF: nothing drawn. */
    private static final long NO_FACES = 0xFFFF_FFFF_FFFFL;
    private static final long CONTAINS_FLUID = 8L << 48;
    public static final int MAX_SLICES = 3;

    /** Slice being baked on this thread (0 = a normal block). Set by the ModelFactory hook. */
    private static final ThreadLocal<int[]> BAKING = ThreadLocal.withInitial(() -> new int[1]);

    public static void setBaking(int slice) {
        BAKING.get()[0] = slice;
    }

    public static int baking() {
        return BAKING.get()[0];
    }

    /**
     * A tall plant's model bounds: {slices, minX, maxX, minZ, maxZ}; slices 0 = not tall. A slice
     * counts only when the model reaches at least half way into that block, so plants a little over
     * one block (most grasses lean out a bit) keep a single cell instead of growing specks above.
     */
    private static final java.util.Map<BlockState, float[]> SHAPES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final float[] NOT_TALL = {0};

    /** How many blocks a plant's model reaches above its own (0-3). Plants only. */
    public static int extraSlices(BlockState state) {
        return (int) shape(state)[0];
    }

    private static float[] shape(BlockState state) {
        if (!LslConfig.voxyTallPlants || !Compat.isPlantBase(state.getBlock())) return NOT_TALL;
        return SHAPES.computeIfAbsent(state, TallPlants::measure);
    }

    private static float[] measure(BlockState state) {
        if (dry(state) == null) return NOT_TALL;
        try {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            float top = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            {
                for (Direction d : DIRECTIONS) {
                    for (BakedQuad quad : model.getQuads(state, d, new SingleThreadedRandomSource(42L))) {
                        for (int v = 0; v < 4; v++) {
                            Vector3fc p = QuadData.position(quad, v);
                            top = Math.max(top, p.y());
                            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
                        }
                    }
                }
            }
            int slices = Math.max(0, Math.min(MAX_SLICES, (int) Math.floor(top - 0.5f)));
            return slices == 0 ? NOT_TALL : new float[]{slices, minX, maxX, minZ, maxZ};
        } catch (Throwable t) {
            return NOT_TALL;
        }
    }

    private static final Direction[] DIRECTIONS = {
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    /** The state a slice bakes: the plant without its water (a slice is in the air above it). Null if it can't. */
    static BlockState dry(BlockState state) {
        BlockState dry = state;
        if (dry.hasProperty(BlockStateProperties.WATERLOGGED)) dry = dry.setValue(BlockStateProperties.WATERLOGGED, false);
        return dry.getFluidState().isEmpty() ? dry : null;
    }

    /**
     * The bakery's quads for a tall plant: squeezed sideways into the block (Conquest Reforged's reed
     * clumps are 3 blocks wide, and Voxy keeps only what lies inside the cell, which left the middle
     * third, thin and see-through) and, for a slice, moved down into the cell.
     */
    public static List<BakedQuad> reshape(List<BakedQuad> quads, BlockState state) {
        if (quads.isEmpty()) return quads;
        float[] shape = shape(state);
        int slice = baking();
        if (shape == NOT_TALL && slice == 0) return quads;
        float cx = 0.5f, cz = 0.5f, scale = 1;
        if (shape != NOT_TALL) {
            float width = Math.max(shape[2] - shape[1], shape[4] - shape[3]);
            if (width > 1.001f) {
                scale = 1 / width;
                cx = (shape[1] + shape[2]) / 2;
                cz = (shape[3] + shape[4]) / 2;
            }
        }
        if (scale == 1 && slice == 0) return quads;
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            Vector3fc[] moved = new Vector3fc[4];
            for (int v = 0; v < 4; v++) moved[v] = move(QuadData.position(q, v), slice, cx, cz, scale);
            out.add(QuadData.with(q, moved, null, null));
        }
        return out;
    }

    private static Vector3fc move(Vector3fc p, int down, float cx, float cz, float scale) {
        return new Vector3f(0.5f + (p.x() - cx) * scale, p.y() - down, 0.5f + (p.z() - cz) * scale);
    }

    /**
     * Writes the slices into RenderDataFactory's prepared section data.
     *
     * @param raw      this section's Voxy ids, index (y<<10)|(z<<5)|x
     * @param below    the section below's ids (plants near its top reach into this one), or null
     * @param section  prepared data: [2i] partial quad word, [2i+1] model metadata
     * @param nonOpaque RenderDataFactory.nonOpaqueMasks (one bit per cell, 32 per int)
     * @param shiftBelow whether plants from the section below get their own offset (plant offsets on)
     * @return extra neighbour mask bits (>= 0), or {@code sliceId | 1<<31} for a slice not baked yet
     */
    public static int apply(long[] raw, long[] below, long[] section, int[] nonOpaque, int[] modelIds,
                            ModelFactory models, BlockTables tables, boolean shiftBelow) {
        int extra = 0;
        for (int i = 0; i < 32 * 32 * 32; i++) {
            long base = raw[i];
            if (Mapper.isAir(base)) continue;
            int id = Mapper.getBlockId(base);
            int slices = BlockTables.tallSlices(tables.info(id));
            if (slices == 0) continue;
            int y = i >> 10;
            long shift = section[i * 2] & SHIFT_BITS;
            for (int k = 1; k <= slices && y + k < 32; k++) {
                int r = place(raw, section, nonOpaque, modelIds, models, tables, base, id, k, i + k * ROW, shift);
                if (r == STOP) break;
                if (r < 0) return r;
                extra |= r;
            }
        }
        if (below != null) {
            for (int i = (32 - MAX_SLICES) * ROW; i < 32 * ROW; i++) {
                long base = below[i];
                if (Mapper.isAir(base)) continue;
                int id = Mapper.getBlockId(base);
                int info = tables.info(id);
                int slices = BlockTables.tallSlices(info);
                if (slices == 0) continue;
                int y = i >> 10;
                if (y + slices < 32) continue;
                long shift = shiftBelow && (info & BlockTables.HAS_FLUID) == 0
                    ? ((long) BlockTables.selfShift(info)) << PlantShift.SHIFT_BIT : 0;
                boolean blocked = false;
                for (int k = 1; k <= slices; k++) {
                    int target = y + k;
                    if (target < 32) {
                        if (!Mapper.isAir(below[i + k * ROW])) { blocked = true; break; }
                        continue;
                    }
                    if (blocked) break;
                    int r = place(raw, section, nonOpaque, modelIds, models, tables, base, id, k,
                        ((target - 32) << 10) | (i & (ROW - 1)), shift);
                    if (r == STOP) break;
                    if (r < 0) return r;
                    extra |= r;
                }
            }
        }
        return extra;
    }

    private static final int STOP = Integer.MIN_VALUE + 1;

    private static int place(long[] raw, long[] section, int[] nonOpaque, int[] modelIds, ModelFactory models,
                             BlockTables tables, long base, int id, int k, int j, long shift) {
        long cell = raw[j];
        if (!Mapper.isAir(cell) || section[j * 2 + 1] != 0) return STOP;
        int sliceId = tables.slice(id, k);
        if (sliceId < 0) return STOP;
        int model = modelIds[sliceId];
        if (model == -1) return sliceId | (1 << 31);
        if (model == 0) return 0; // baked to nothing (the texture ends lower)
        long meta = models.getModelMetadataFromClientId(model);
        if ((meta & NO_FACES) == NO_FACES || (meta & CONTAINS_FLUID) != 0) return 0;
        long state = (base & ~LIGHT) | (cell & LIGHT);
        section[j * 2] = RenderDataFactoryInvoker.lsl$pack(model, state, meta) | shift;
        section[j * 2 + 1] = meta;
        nonOpaque[j >> 5] |= 1 << (j & 31);
        return RenderDataFactoryInvoker.lsl$neighborMsk(1L << (j & 63), (j & ~63) + 64);
    }
}
