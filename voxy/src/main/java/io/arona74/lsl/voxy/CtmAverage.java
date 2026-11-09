package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The loader- and version-neutral half of connected textures in Voxy's LODs: which models are
 * Continuity's, how a block's textures repeat, and the repeat AVERAGE bake. Each module's
 * {@code CtmBake} draws the model through its own Fabric renderer API and calls in here.
 *
 * <p>Conquest Reforged's sandstones spread one large texture over 4x4 or 8x8 blocks ("repeat"),
 * and a single piece of it on every block shows as a grid. With {@code voxy_ctm_repeat} AVERAGE,
 * every piece of the cycle (found per axis, up to 8) is baked and averaged per pixel
 * ({@link #average}); with BASE, the base texture, which is made to tile alone. Random textures
 * keep their base texture.
 */
public final class CtmAverage {
    private CtmAverage() {}

    private static final String CONTINUITY = "me.pepperbell.continuity.";
    private static final AtomicInteger FAILURES = new AtomicInteger();

    /** Whether {@code model} is one of Continuity's (and connected textures are on). */
    public static boolean handles(Object model) {
        return LslConfig.voxyCtm && model != null && model.getClass().getName().startsWith(CONTINUITY);
    }

    /** Logs a failed connected-texture bake (the first five). */
    public static void failed(BlockState state, Throwable t) {
        int n = FAILURES.incrementAndGet();
        if (n <= 5) Lsl.LOGGER.warn("[LSL/Voxy] Couldn't bake connected textures for {}, using its base textures: {}", state, t.toString());
        if (n == 5) Lsl.LOGGER.warn("[LSL/Voxy] (further connected-texture failures are not logged)");
    }

    // ========== Repeat detection ==========

    private static final int MAX_PERIOD = 8;
    private static final int[] NOT_REPEAT = new int[0];
    private static final Map<BlockState, int[]> PERIODS = new ConcurrentHashMap<>();
    /** A second spot, off every 2, 3, 4 and 8 block repeat and with a different random hash. */
    private static final BlockPos OTHER = new BlockPos(5, 7, 3);

    /** Piece of the repeat cycle to bake (AVERAGE mode, set by {@link #average}), null = normal bake. */
    private static final ThreadLocal<BlockPos> PIECE = new ThreadLocal<>();
    /** Set by a normal bake of a repeat block in AVERAGE mode. */
    private static final ThreadLocal<AverageRequest> AVERAGE_REQUEST = new ThreadLocal<>();

    /** A repeat block to average: its cycle's periods, and its particle sprite (new on every resource reload). */
    public record AverageRequest(int[] periods, TextureAtlasSprite sprite) {}

    /** The piece of the cycle the current bake draws, or null for a normal bake. */
    public static BlockPos piece() {
        return PIECE.get();
    }

    /**
     * How a drawn Continuity model is used, given the UVs it drew at its piece (or block 0,0,0) and
     * a way to draw it elsewhere: true to use the drawn quads, false to use the plain ones (random
     * textures, or BASE mode). A normal bake of a repeat block in AVERAGE mode also files an
     * average request, which the bakery hook picks up when the bake ends.
     *
     * @param uvsAt the model's UVs, all sides in a fixed order, drawn at a position
     */
    public static boolean useDrawn(BlockState state, TextureAtlasSprite sprite, List<Float> base, Function<BlockPos, List<Float>> uvsAt) {
        if (PlantLook.isPlant(state)) return true; // plants keep the Continuity tile
        int[] periods = periods(state, base, uvsAt);
        if (periods == null) return true;
        // Repeat textures (a large texture spread over N x M blocks) change with position; one
        // fixed piece of the pattern on every block makes a grid.
        if (periods[0] == 0 || LslConfig.voxyCtmRepeat == LslConfig.CtmRepeat.BASE) return false;
        // AVERAGE: one piece per bake; the bakery hook bakes the other pieces and averages them.
        // It only blends colours over the same geometry, so any shape works.
        if (PIECE.get() == null) AVERAGE_REQUEST.set(new AverageRequest(periods, sprite));
        return true;
    }

    /** Repeat periods per axis ({0, 0, 0} if the textures vary but don't repeat), null if they don't vary. */
    private static int[] periods(BlockState state, List<Float> base, Function<BlockPos, List<Float>> uvsAt) {
        int[] p = PERIODS.computeIfAbsent(state, s -> {
            if (base.equals(uvsAt.apply(OTHER))) return NOT_REPEAT;
            int px = period(base, uvsAt, 1, 0, 0);
            int py = period(base, uvsAt, 0, 1, 0);
            int pz = period(base, uvsAt, 0, 0, 1);
            return px == 0 || py == 0 || pz == 0 ? new int[]{0, 0, 0} : new int[]{px, py, pz};
        });
        return p == NOT_REPEAT ? null : p;
    }

    /** Smallest step along (dx, dy, dz) after which the textures repeat (1-8), 0 if none. */
    private static int period(List<Float> base, Function<BlockPos, List<Float>> uvsAt, int dx, int dy, int dz) {
        for (int k = 1; k <= MAX_PERIOD; k++) {
            if (uvsAt.apply(new BlockPos(dx * k, dy * k, dz * k)).equals(base)) return k;
        }
        return 0;
    }

    // ========== AVERAGE bake ==========

    /** The pending AVERAGE request of the bake that just finished (never inside a piece bake). */
    public static AverageRequest takeAverageRequest() {
        if (PIECE.get() != null) return null;
        AverageRequest r = AVERAGE_REQUEST.get();
        AVERAGE_REQUEST.remove();
        return r;
    }

    /**
     * AVERAGE mode, after the bakery baked piece (0,0,0) of a repeat block into {@code output}:
     * bakes the other pieces of the cycle and writes the per-pixel average colour of each face
     * (depth and coverage stay those of piece (0,0,0): every piece has the same geometry, only
     * textures differ, so this works for slabs and other partial blocks too). A face's texture
     * depends only on the offsets in its own plane, so each face averages over that plane's grid.
     * A whole cycle tiles with itself, and so does its average.
     *
     * <p>Speed (this runs on Voxy's single model thread, and every piece is a full 6-view software
     * bake): one piece (x, y, z) serves all three planes at once, the XY sample (x, y) for north and
     * south, (y, z) for west and east, (x, z) for down and up. With z = (x + y) mod period, an
     * N x N x N cycle covers all three planes in N*N bakes instead of 3*N*N; each sample is still
     * taken exactly once, so the average is unchanged. Results are kept by {@link CtmAverageCache}
     * (in memory and on disk) for any later bake whose piece (0,0,0) comes out identical:
     * waterlogged twins, every world rejoin (Voxy rebakes all models on each world load) and the
     * next game launch.
     */
    public static void average(BiConsumer<BlockState, Long> bake, BlockState state, long output, AverageRequest request) {
        final int pixels = 16 * 16;
        final int bytes = 6 * pixels * 8;
        long start = System.nanoTime();
        int[] periods = request.periods();
        Object key = CtmAverageCache.key(state, request.sprite(), periods, output, bytes);
        int[] known = CtmAverageCache.get(key);
        if (known != null) {
            for (int i = 0; i < known.length; i++) MemoryUtil.memPutInt(output + (long) i * 8, known[i]);
            PIECE.remove();
            AVERAGE_REQUEST.remove();
            STATS.reused.incrementAndGet();
            STATS.record(System.nanoTime() - start, 0);
            return;
        }
        long scratch = MemoryUtil.nmemAlloc(bytes);
        long[] sum = new long[6 * pixels * 4];
        int[] count = new int[6 * pixels];
        int bakes = 0;
        try {
            int px = periods[0], py = periods[1], pz = periods[2];
            boolean[] xy = new boolean[px * py], yz = new boolean[py * pz], xz = new boolean[px * pz];
            // faces: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. Piece (0,0,0) is already in output.
            add(output, sum, count, true, true, true);
            xy[0] = yz[0] = xz[0] = true;
            // Every (x, y), with the z that also brings new (y, z) and (x, z) samples.
            for (int x = 0; x < px; x++) {
                for (int y = 0; y < py; y++) {
                    if (xy[x * py + y]) continue;
                    int best = 0, bestScore = -1;
                    for (int k = 0; k < pz; k++) {
                        int z = (x + y + k) % pz;
                        int score = (yz[y * pz + z] ? 0 : 1) + (xz[x * pz + z] ? 0 : 1);
                        if (score > bestScore) {
                            best = z;
                            bestScore = score;
                            if (score == 2) break;
                        }
                    }
                    bakes += piece(bake, state, scratch, sum, count, x, y, best, xy, yz, xz, px, py, pz);
                }
            }
            // (y, z) samples still missing (unequal periods), each with a missing (x, z) if there is one.
            for (int y = 0; y < py; y++) {
                for (int z = 0; z < pz; z++) {
                    if (yz[y * pz + z]) continue;
                    int x = 0;
                    for (int k = 0; k < px; k++) {
                        if (!xz[k * pz + z]) {
                            x = k;
                            break;
                        }
                    }
                    bakes += piece(bake, state, scratch, sum, count, x, y, z, xy, yz, xz, px, py, pz);
                }
            }
            for (int x = 0; x < px; x++) {
                for (int z = 0; z < pz; z++) {
                    if (!xz[x * pz + z]) bakes += piece(bake, state, scratch, sum, count, x, 0, z, xy, yz, xz, px, py, pz);
                }
            }
            for (int i = 0; i < 6 * pixels; i++) {
                int n = count[i];
                if (n == 0) continue;
                int a = (int) (sum[i * 4] / n), b = (int) (sum[i * 4 + 1] / n), g = (int) (sum[i * 4 + 2] / n), r = (int) (sum[i * 4 + 3] / n);
                MemoryUtil.memPutInt(output + (long) i * 8, (a << 24) | (b << 16) | (g << 8) | r);
            }
            int[] colours = new int[CtmAverageCache.COLOURS];
            for (int i = 0; i < colours.length; i++) colours[i] = MemoryUtil.memGetInt(output + (long) i * 8);
            CtmAverageCache.put(key, colours);
        } finally {
            PIECE.remove();
            AVERAGE_REQUEST.remove();
            MemoryUtil.nmemFree(scratch);
            STATS.record(System.nanoTime() - start, bakes);
        }
    }

    /** Bakes one piece and adds the faces of the planes it brings a new sample to. Returns 1. */
    private static int piece(BiConsumer<BlockState, Long> bake, BlockState state, long scratch,
                             long[] sum, int[] count, int x, int y, int z, boolean[] xy, boolean[] yz, boolean[] xz,
                             int px, int py, int pz) {
        boolean newXy = !xy[x * py + y], newYz = !yz[y * pz + z], newXz = !xz[x * pz + z];
        xy[x * py + y] = yz[y * pz + z] = xz[x * pz + z] = true;
        PIECE.set(new BlockPos(x, y, z));
        bake.accept(state, scratch);
        add(scratch, sum, count, newXy, newYz, newXz);
        return 1;
    }

    private static void add(long picture, long[] sum, int[] count, boolean northSouth, boolean westEast, boolean downUp) {
        final int pixels = 16 * 16;
        for (int face = 0; face < 6; face++) {
            boolean take = face < 2 ? downUp : face < 4 ? northSouth : westEast;
            if (!take) continue;
            for (int p = 0; p < pixels; p++) {
                int i = face * pixels + p;
                int c = MemoryUtil.memGetInt(picture + (long) i * 8);
                if ((c >>> 24) == 0) continue;
                sum[i * 4] += c >>> 24;
                sum[i * 4 + 1] += (c >>> 16) & 0xFF;
                sum[i * 4 + 2] += (c >>> 8) & 0xFF;
                sum[i * 4 + 3] += c & 0xFF;
                count[i]++;
            }
        }
    }

    /** Totals for {@code /lsl voxy ctm} and the log. */
    public static final AverageStats STATS = new AverageStats();

    public static final class AverageStats {
        public final AtomicInteger states = new AtomicInteger();
        public final AtomicInteger reused = new AtomicInteger();
        public final AtomicLong bakes = new AtomicLong();
        public final AtomicLong nanos = new AtomicLong();
        private volatile long lastLog;

        void record(long nanos, int bakes) {
            this.states.incrementAndGet();
            this.bakes.addAndGet(bakes);
            this.nanos.addAndGet(nanos);
            long now = System.nanoTime();
            if (now - this.lastLog > 2_000_000_000L) {
                this.lastLog = now;
                Lsl.LOGGER.info("[LSL/Voxy] {}", this.summary());
            }
        }

        public String summary() {
            return String.format("Repeat CTM average so far: %d block states (%d reused), %d piece bakes, %.0f ms on the Voxy model thread, %d cached",
                this.states.get(), this.reused.get(), this.bakes.get(), this.nanos.get() / 1e6, CtmAverageCache.size());
        }
    }
}
