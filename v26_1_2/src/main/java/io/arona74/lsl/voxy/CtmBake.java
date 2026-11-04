package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Connected and random textures (Continuity) in Voxy's LODs.
 *
 * <p>Continuity wraps block models and picks its textures only while the game draws a block in the
 * world, from the block's neighbours and position. Voxy bakes a block state once, from the model's
 * plain parts, so it got the base textures. Conquest Reforged leans on this: its fescues' base
 * textures are pre-coloured fallbacks while the textures Continuity swaps in are near grey, made to
 * be coloured by the Polytone tint, so the LODs showed them doubly coloured (small fescue yellow,
 * green meadow fescue dark green).
 *
 * <p>Here a Continuity model is asked to draw itself, through Fabric's renderer API, at block 0,0,0
 * of a stand-in world, and its quads replace the plain parts. One look per block state, as Voxy
 * stores: a plant stands alone (air around it); any other block is surrounded by copies of itself,
 * which gives the fully connected look walls and floors have from afar. A block whose textures
 * change with position needs more: Conquest Reforged's sandstones spread one large texture over 4x4
 * or 8x8 blocks ("repeat"), and a single piece of it on every block shows as a grid: with
 * {@code voxy_ctm_repeat} AVERAGE, every piece of the cycle (found per axis, up to 8) is baked and
 * averaged per pixel ({@link #average}); with BASE, the base texture, which is made to tile alone.
 * Random textures keep their base texture. Plants keep the Continuity tile whatever the method
 * (their base textures are the pre-coloured fallbacks). Option {@code voxy_ctm}.
 */
public final class CtmBake {
    private CtmBake() {}

    private static final String CONTINUITY = "me.pepperbell.continuity.";
    private static final java.util.concurrent.atomic.AtomicInteger FAILURES = new java.util.concurrent.atomic.AtomicInteger();

    public static boolean handles(BlockStateModel model) {
        return LslConfig.voxyCtm && model != null && model.getClass().getName().startsWith(CONTINUITY);
    }

    /**
     * Collects the model's parts as Continuity would draw them in a world. Falls back to the plain
     * parts if anything goes wrong.
     */
    public static void collect(BlockStateModel model, BlockState state, RandomSource random, List<BlockStateModelPart> out) {
        List<BlockStateModelPart> plain = new ArrayList<>();
        model.collectParts(random, plain);
        try {
            ChunkSectionLayer layer = firstLayer(plain);
            Material.Baked particle = model.particleMaterial();
            TextureAtlasSprite sprite = particle.sprite();
            // A piece bake (AVERAGE mode) draws its own piece; the periods are cached by then.
            BlockPos piece = PIECE.get();
            MutableMesh mesh = emit(model, state, piece != null ? piece : BlockPos.ZERO);
            Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
            List<BakedQuad> unculled = new ArrayList<>();
            int[] periods = PlantLook.isPlant(state) ? null : periods(model, state, mesh);
            if (periods != null) {
                // Repeat textures (a large texture spread over N x M blocks) change with position;
                // one fixed piece of the pattern on every block makes a grid.
                if (periods[0] == 0 || LslConfig.voxyCtmRepeat == LslConfig.CtmRepeat.BASE) {
                    out.addAll(plain); // random textures, or BASE: the base texture tiles alone
                    return;
                }
                // AVERAGE: one piece per bake; the bakery hook bakes the other pieces and averages
                // them. It only blends colours over the same geometry, so any shape works.
                if (piece == null) AVERAGE_REQUEST.set(new AverageRequest(periods, sprite));
            }
            add(mesh, sprite, layer, culled, unculled);
            if (unculled.isEmpty() && culled.isEmpty()) {
                out.addAll(plain);
                return;
            }
            boolean ao = plain.isEmpty() || plain.get(0).useAmbientOcclusion();
            out.add(new Part(culled, unculled, ao, particle, model.materialFlags()));
        } catch (Throwable t) {
            int n = FAILURES.incrementAndGet();
            if (n <= 5) Lsl.LOGGER.warn("[LSL/Voxy] Couldn't bake connected textures for {}, using its base textures: {}", state, t.toString());
            if (n == 5) Lsl.LOGGER.warn("[LSL/Voxy] (further connected-texture failures are not logged)");
            out.addAll(plain);
        }
    }

    private static final int MAX_PERIOD = 8;
    private static final int[] NOT_REPEAT = new int[0];
    private static final java.util.Map<BlockState, int[]> PERIODS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Piece of the repeat cycle to bake (AVERAGE mode, set by the bakery hook), null = normal bake. */
    static final ThreadLocal<BlockPos> PIECE = new ThreadLocal<>();
    /** Set by a normal bake of a repeat block in AVERAGE mode. */
    static final ThreadLocal<AverageRequest> AVERAGE_REQUEST = new ThreadLocal<>();

    /** A repeat block to average: its cycle's periods, and its particle sprite (new on every resource reload). */
    public record AverageRequest(int[] periods, TextureAtlasSprite sprite) {}

    /** The pending AVERAGE request of the bake that just finished (never inside a piece bake). */
    public static AverageRequest takeAverageRequest() {
        if (PIECE.get() != null) return null;
        AverageRequest r = AVERAGE_REQUEST.get();
        AVERAGE_REQUEST.remove();
        return r;
    }

    /** Repeat periods per axis ({0, 0, 0} if the textures vary but don't repeat), null if they don't vary. */
    private static int[] periods(BlockStateModel model, BlockState state, MutableMesh origin) {
        int[] p = PERIODS.computeIfAbsent(state, s -> {
            if (uvs(origin).equals(uvs(emit(model, s, OTHER)))) return NOT_REPEAT;
            int px = period(model, s, origin, 1, 0, 0);
            int py = period(model, s, origin, 0, 1, 0);
            int pz = period(model, s, origin, 0, 0, 1);
            return px == 0 || py == 0 || pz == 0 ? new int[]{0, 0, 0} : new int[]{px, py, pz};
        });
        return p == NOT_REPEAT ? null : p;
    }

    private static MutableMesh emit(BlockStateModel model, BlockState state, BlockPos pos) {
        MutableMesh mesh = Renderer.get().mutableMesh();
        model.emitQuads(mesh.emitter(), standIn(state, pos), pos, state, new SingleThreadedRandomSource(42L), d -> false);
        return mesh;
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
    public static void average(java.util.function.BiConsumer<BlockState, Long> bake, BlockState state, long output, AverageRequest request) {
        final int pixels = 16 * 16;
        final int bytes = 6 * pixels * 8;
        long start = System.nanoTime();
        int[] periods = request.periods();
        Object key = CtmAverageCache.key(state, request.sprite(), periods, output, bytes);
        int[] known = CtmAverageCache.get(key);
        if (known != null) {
            for (int i = 0; i < known.length; i++) org.lwjgl.system.MemoryUtil.memPutInt(output + (long) i * 8, known[i]);
            PIECE.remove();
            AVERAGE_REQUEST.remove();
            STATS.reused.incrementAndGet();
            STATS.record(System.nanoTime() - start, 0);
            return;
        }
        long scratch = org.lwjgl.system.MemoryUtil.nmemAlloc(bytes);
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
                org.lwjgl.system.MemoryUtil.memPutInt(output + (long) i * 8, (a << 24) | (b << 16) | (g << 8) | r);
            }
            int[] colours = new int[CtmAverageCache.COLOURS];
            for (int i = 0; i < colours.length; i++) colours[i] = org.lwjgl.system.MemoryUtil.memGetInt(output + (long) i * 8);
            CtmAverageCache.put(key, colours);
        } finally {
            PIECE.remove();
            AVERAGE_REQUEST.remove();
            org.lwjgl.system.MemoryUtil.nmemFree(scratch);
            STATS.record(System.nanoTime() - start, bakes);
        }
    }

    /** Bakes one piece and adds the faces of the planes it brings a new sample to. Returns 1. */
    private static int piece(java.util.function.BiConsumer<BlockState, Long> bake, BlockState state, long scratch,
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
                int c = org.lwjgl.system.MemoryUtil.memGetInt(picture + (long) i * 8);
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
        public final java.util.concurrent.atomic.AtomicInteger states = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger reused = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicLong bakes = new java.util.concurrent.atomic.AtomicLong();
        public final java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
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

    /** Smallest step along (dx, dy, dz) after which the textures repeat (1-8), 0 if none. */
    private static int period(BlockStateModel model, BlockState state, MutableMesh origin, int dx, int dy, int dz) {
        List<Float> base = uvs(origin);
        for (int k = 1; k <= MAX_PERIOD; k++) {
            if (uvs(emit(model, state, new BlockPos(dx * k, dy * k, dz * k))).equals(base)) return k;
        }
        return 0;
    }

    /** The mesh's quads as BakedQuads, bucketed by cull face. */
    private static void add(MutableMesh mesh, TextureAtlasSprite sprite, ChunkSectionLayer layer,
                            Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled) {
        mesh.forEach(q -> {
            BakedQuad quad = q.toBakedQuad(sprite);
            var m = quad.materialInfo();
            if (m.layer() == null && layer != null) {
                m = new BakedQuad.MaterialInfo(m.sprite(), layer, m.itemRenderType(), m.tintIndex(), m.shade(), m.lightEmission());
                quad = new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                    quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(), quad.direction(), m);
            }
            Direction cull = q.cullFace();
            if (cull == null) unculled.add(quad);
            else culled.computeIfAbsent(cull, k -> new ArrayList<>()).add(quad);
        });
    }

    private static ChunkSectionLayer firstLayer(List<BlockStateModelPart> parts) {
        for (BlockStateModelPart part : parts) {
            for (Direction d : DIRECTIONS) {
                for (BakedQuad q : part.getQuads(d)) {
                    if (q.materialInfo().layer() != null) return q.materialInfo().layer();
                }
            }
        }
        return null;
    }

    private static final Direction[] DIRECTIONS = {
        null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** The block at 0,0,0; around it copies of itself, or air for plants. */
    /** A second spot, off every 2, 3, 4 and 8 block repeat and with a different random hash. */
    private static final BlockPos OTHER = new BlockPos(5, 7, 3);

    private static List<Float> uvs(MutableMesh mesh) {
        List<Float> out = new ArrayList<>();
        mesh.forEach(q -> {
            for (int v = 0; v < 4; v++) {
                out.add(q.u(v));
                out.add(q.v(v));
            }
        });
        return out;
    }

    private static BlockAndTintGetter standIn(BlockState state, BlockPos origin) {
        BlockState around = PlantLook.isPlant(state) ? Blocks.AIR.defaultBlockState() : state;
        return new BlockAndTintGetter() {
            @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
            @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
            @Override public int getBlockTint(BlockPos p, ColorResolver r) { return -1; }
            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
            @Override public BlockState getBlockState(BlockPos p) { return p.equals(origin) ? state : around; }
            @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinY() { return -64; }
        };
    }

    private record Part(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled, boolean ao,
                        Material.Baked particle, int flags) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction side) {
            if (side == null) return this.unculled;
            return this.culled.getOrDefault(side, List.of());
        }

        @Override public boolean useAmbientOcclusion() { return this.ao; }
        @Override public Material.Baked particleMaterial() { return this.particle; }
        @Override public int materialFlags() { return this.flags; }
    }
}
