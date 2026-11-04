package io.arona74.lsl.voxy;

import io.arona74.lsl.LayerShapes;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-world lookup tables indexed by Voxy block id. Voxy ids belong to a {@link Mapper} (one per
 * world), so the tables do too.
 *
 * <p>Both hooks run on hot paths (the mipper for every 2x2x2 cell ingested, the mesher for every
 * block of every section built), so each id is classified once and read back as one int. Writes
 * are idempotent, so the benign races between worker threads need no locking; only growing the
 * array does.
 */
public final class BlockTables {
    // Info bits per block id.
    static final int COMPUTED = 1;
    static final int FULL = 1 << 1;
    static final int LAYER_SHIFT = 2;              // bits 2-5: layer eighths 1-8, 0 = not a layer
    static final int LAYER_MASK = 0xF << LAYER_SHIFT;
    static final int SELF_SHIFT = 6;               // bits 6-8: own offset, eighths 0-7
    static final int SELF_MASK = 0x7 << SELF_SHIFT;
    static final int GROUNDABLE = 1 << 9;
    static final int HAS_FLUID = 1 << 10;
    static final int TALL_SHIFT = 11;              // bits 11-12: blocks the model reaches above its own (TallPlants)
    static final int TALL_MASK = 0x3 << TALL_SHIFT;

    /**
     * Slice ids (TallPlants) count down from the top of Voxy's 20-bit block id range, far above any
     * id the mapper hands out.
     */
    static final int SLICE_TOP = (1 << 20) - 1;
    private static final int SLICE_LIMIT = 1 << 16;

    private static final Map<Mapper, BlockTables> TABLES = new WeakHashMap<>();
    private static volatile BlockTables last;

    public static BlockTables of(Mapper mapper) {
        BlockTables t = last;
        if (t != null && t.mapper == mapper) return t;
        synchronized (TABLES) {
            t = TABLES.computeIfAbsent(mapper, BlockTables::new);
        }
        last = t;
        return t;
    }

    private final Mapper mapper;
    private volatile int[] info = new int[4096];
    private volatile int[] blockKey = new int[4096];
    /** (blockId, eighths, waterlogged) -> Voxy id of that layer variant. */
    private final Map<Long, Integer> variants = new ConcurrentHashMap<>();

    private BlockTables(Mapper mapper) {
        this.mapper = mapper;
    }

    public int info(int blockId) {
        int[] arr = this.info;
        if (blockId < arr.length) {
            int v = arr[blockId];
            if (v != 0) return v;
        }
        return compute(blockId);
    }

    public static int layerEighths(int info) {
        return (info & LAYER_MASK) >>> LAYER_SHIFT;
    }

    public static int tallSlices(int info) {
        return (info & TALL_MASK) >>> TALL_SHIFT;
    }

    private final Map<Integer, Integer> slices = new ConcurrentHashMap<>();
    private final BlockState[] sliceStates = new BlockState[SLICE_LIMIT];
    private final int[] sliceLevels = new int[SLICE_LIMIT];
    private volatile int sliceCount;

    /** The block id standing for slice {@code k} (1-3) of {@code blockId}; -1 when out of ids. */
    public int slice(int blockId, int k) {
        Integer key = (blockId << 2) | k;
        Integer id = this.slices.get(key);
        if (id != null) return id;
        synchronized (this.slices) {
            id = this.slices.get(key);
            if (id != null) return id;
            int n = this.sliceCount;
            BlockState state = null;
            try {
                state = TallPlants.dry(this.mapper.getBlockStateFromBlockId(blockId));
            } catch (Throwable ignored) {
            }
            if (n >= SLICE_LIMIT || state == null || SLICE_TOP - n < this.mapper.getBlockStateCount()) {
                this.slices.put(key, -1);
                return -1;
            }
            this.sliceStates[n] = state;
            this.sliceLevels[n] = k;
            this.sliceCount = n + 1;
            this.slices.put(key, SLICE_TOP - n);
            return SLICE_TOP - n;
        }
    }

    public boolean isSlice(int blockId) {
        int n = SLICE_TOP - blockId;
        return n >= 0 && n < this.sliceCount;
    }

    /** State a slice id bakes (the plant, dry). */
    public BlockState sliceState(int blockId) {
        return this.sliceStates[SLICE_TOP - blockId];
    }

    /** How many blocks down a slice id's model is moved. */
    public int sliceLevel(int blockId) {
        return isSlice(blockId) ? this.sliceLevels[SLICE_TOP - blockId] : 0;
    }

    public static int selfShift(int info) {
        return (info & SELF_MASK) >>> SELF_SHIFT;
    }

    /** Registry id of the block, to tell "same plant, other half" apart from a different plant. */
    public int blockKey(int blockId) {
        info(blockId);
        return this.blockKey[blockId];
    }

    /**
     * Voxy id of the same layer block at {@code eighths} tall (waterlogged when asked and possible),
     * registering the state with the mapper if Voxy has not seen it yet. -1 when there is none.
     */
    public int variant(int blockId, int eighths, boolean waterlogged) {
        long key = ((long) blockId << 5) | ((long) eighths << 1) | (waterlogged ? 1 : 0);
        Integer cached = this.variants.get(key);
        if (cached != null) return cached;
        int id = -1;
        try {
            BlockState base = this.mapper.getBlockStateFromBlockId(blockId);
            BlockState target = LayerShapes.withEighths(base, eighths, waterlogged);
            boolean keepsWater = !waterlogged || (target != null && !target.getFluidState().isEmpty());
            if (target != null && keepsWater && LayerShapes.layerEighths(target) == eighths) {
                id = this.mapper.getIdForBlockState(target);
            }
        } catch (Throwable t) {
            id = -1;
        }
        this.variants.put(key, id);
        return id;
    }

    private int compute(int blockId) {
        int value = COMPUTED;
        int key = -1;
        try {
            BlockState state = this.mapper.getBlockStateFromBlockId(blockId);
            if (state != null && !state.isAir()) {
                int self = VoxyShapes.selfOffsetEighths(state);
                // A block that lowers itself is decor or a plant, never a floor layer.
                int layer = self == 0 ? LayerShapes.layerEighths(state) : 0;
                if (layer != 0) {
                    value |= layer << LAYER_SHIFT;
                } else if (LayerShapes.isFullCube(state)) {
                    value |= FULL;
                }
                value |= self << SELF_SHIFT;
                if (layer == 0 && VoxyShapes.isGroundable(state)) value |= GROUNDABLE;
                if (!state.getFluidState().isEmpty()) value |= HAS_FLUID;
                value |= TallPlants.extraSlices(state) << TALL_SHIFT;
                key = BuiltInRegistries.BLOCK.getId(state.getBlock());
            }
        } catch (Throwable t) {
            // Unknown id or a block that cannot be classified: treat as "nothing special".
        }
        synchronized (this) {
            if (blockId >= this.info.length) {
                int size = Math.max(blockId + 1, this.info.length * 2);
                int[] grownInfo = Arrays.copyOf(this.info, size);
                int[] grownKey = Arrays.copyOf(this.blockKey, size);
                this.blockKey = grownKey;
                this.info = grownInfo;
            }
            this.blockKey[blockId] = key;
            this.info[blockId] = value;
        }
        return value;
    }
}
