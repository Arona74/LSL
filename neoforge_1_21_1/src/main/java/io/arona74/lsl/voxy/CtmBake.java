package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
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
 * Connected and random textures (Continuity) in Voxy's LODs; the 1.21.1 / NeoForge form of the 26.1.2
 * class. Continuity wraps block models and picks its textures only while the game draws a block in
 * the world, so Voxy's bake, which reads the plain quads, got the base textures.
 *
 * <p>Here a Continuity model is asked to draw itself through Fabric's renderer API (Forgified Fabric
 * API, which Roxy requires) at block 0,0,0 of a stand-in world, into a small render context that
 * applies the model's quad transforms and keeps the result as BakedQuads (see {@link Capture}); the bakery's
 * {@code getQuads(state, side, random)} then returns those (SoftwareModelTextureBakeryMixin). A plant
 * stands alone (air around it); any other block is surrounded by copies of itself, the fully
 * connected look walls and floors have from afar.
 *
 * <p>Textures that change with position: a "repeat" pattern (one large texture over N x M blocks,
 * the cycle found per axis, up to 8) is averaged per pixel over the whole cycle with
 * {@code voxy_ctm_repeat} AVERAGE ({@link #average}, results kept by {@link CtmAverageCache}), or
 * left to the base texture with BASE. Random textures keep their base texture. Plants keep the
 * Continuity tile whatever the method. Option {@code voxy_ctm}.
 */
public final class CtmBake {
    private CtmBake() {}

    private static final String CONTINUITY = "me.pepperbell.continuity.";
    private static final java.util.concurrent.atomic.AtomicInteger FAILURES = new java.util.concurrent.atomic.AtomicInteger();

    public static boolean handles(BakedModel model) {
        return LslConfig.voxyCtm && model != null && model.getClass().getName().startsWith(CONTINUITY);
    }

    /** The quads a capture context kept, by cull face. */
    interface Quads {
        Map<Direction, List<BakedQuad>> culled();

        List<BakedQuad> unculled();
    }

    /** What one bake of a Continuity model gave: quads by cull face, or null to use the plain quads. */
    private record Baked(BakedModel model, BlockState state, BlockPos piece, Map<Direction, List<BakedQuad>> culled,
                         List<BakedQuad> unculled) {}

    /** The current bake's result (the bakery asks once per side). Cleared after each renderToOutput. */
    private static final ThreadLocal<Baked> CURRENT = new ThreadLocal<>();

    /**
     * The quads Voxy's bakery gets for {@code side} of a Continuity model, or null to let it read the
     * plain quads (random textures, BASE mode, or anything going wrong).
     */
    public static List<BakedQuad> quads(BakedModel model, BlockState state, Direction side) {
        BlockPos piece = PIECE.get();
        Baked baked = CURRENT.get();
        if (baked == null || baked.model != model || baked.state != state || !java.util.Objects.equals(baked.piece, piece)) {
            baked = bake(model, state, piece);
            CURRENT.set(baked);
        }
        if (baked.culled == null) return null;
        if (side == null) return baked.unculled;
        return baked.culled.getOrDefault(side, List.of());
    }

    /** Called when a bake finishes, so the next one starts fresh. */
    public static void endBake() {
        CURRENT.remove();
    }

    private static Baked bake(BakedModel model, BlockState state, BlockPos piece) {
        try {
            TextureAtlasSprite sprite = model.getParticleIcon();
            Quads origin = emit(model, state, piece != null ? piece : BlockPos.ZERO, sprite);
            int[] periods = PlantLook.isPlant(state) ? null : periods(model, state, origin, sprite);
            if (periods != null) {
                // Repeat textures (a large texture spread over N x M blocks) change with position;
                // one fixed piece of the pattern on every block makes a grid.
                if (periods[0] == 0 || LslConfig.voxyCtmRepeat == LslConfig.CtmRepeat.BASE) {
                    return new Baked(model, state, piece, null, null); // random textures, or BASE: the base texture tiles alone
                }
                // AVERAGE: one piece per bake; the bakery hook bakes the other pieces and averages
                // them. It only blends colours over the same geometry, so any shape works.
                if (piece == null) AVERAGE_REQUEST.set(new AverageRequest(periods, sprite));
            }
            if (origin.unculled().isEmpty() && origin.culled().isEmpty()) return new Baked(model, state, piece, null, null);
            return new Baked(model, state, piece, origin.culled(), origin.unculled());
        } catch (Throwable t) {
            int n = FAILURES.incrementAndGet();
            if (n <= 5) Lsl.LOGGER.warn("[LSL/Voxy] Couldn't bake connected textures for {}, using its base textures: {}", state, t.toString());
            if (n == 5) Lsl.LOGGER.warn("[LSL/Voxy] (further connected-texture failures are not logged)");
            return new Baked(model, state, piece, null, null);
        }
    }

    // ========== Drawing a model through Fabric's renderer API ==========

    /**
     * A render context that keeps what a model emits, using only the active renderer's public API:
     * the fallback when the renderer isn't Sodium (see {@link SodiumCtmContext}, which Sodium needs).
     *
     * <p>A renderer applies the pushed quad transforms as each quad is emitted. Plain models emit
     * straight into {@link #getEmitter} (FFAPI's VanillaModelEncoder), which a mesh emitter can't
     * intercept, so each pushed transform opens a segment with its own mesh: quads emitted while it
     * is the innermost one collect there, and when it is popped every transform active at that time
     * runs on them, in push order. Continuity resets its CTM transform only after popping it, so
     * this matches what a renderer does. Quads emitted with no transform active are kept as they are.
     */
    private static final class Capture implements RenderContext, Quads {
        final Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        final List<BakedQuad> unculled = new ArrayList<>();
        final TextureAtlasSprite sprite;
        final RandomSource random = new SingleThreadedRandomSource(42L);
        final Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        final RenderMaterial standard = renderer.materialFinder().find();
        final MeshBuilder work = renderer.meshBuilder();
        final List<Segment> segments = new ArrayList<>();
        final BakedModelConsumer models = new BakedModelConsumer() {
            @Override
            public void accept(BakedModel model) {
                accept(model, null);
            }

            @Override
            public void accept(BakedModel model, BlockState state) {
                for (Direction side : SIDES) {
                    random.setSeed(42L);
                    for (BakedQuad quad : model.getQuads(state, side, random)) {
                        getEmitter().fromVanilla(quad, standard, side).emit();
                    }
                }
            }
        };

        /** The quads emitted while {@code transforms} (outermost first) were active. */
        private record Segment(MeshBuilder mesh, List<QuadTransform> transforms) {}

        Capture(TextureAtlasSprite sprite) {
            this.sprite = sprite;
            this.segments.add(new Segment(this.renderer.meshBuilder(), List.of()));
        }

        void store(QuadView quad) {
            BakedQuad baked = quad.toBakedQuad(this.sprite);
            Direction cull = quad.cullFace();
            if (cull == null) this.unculled.add(baked);
            else this.culled.computeIfAbsent(cull, k -> new ArrayList<>()).add(baked);
        }

        /** Runs a closed segment's transforms on each of its quads and keeps those that pass. */
        void close(Segment segment) {
            segment.mesh().build().forEach(view -> {
                QuadEmitter quad = this.work.getEmitter();
                quad.copyFrom(view);
                boolean pass = true;
                for (int i = 0; i < segment.transforms().size() && pass; i++) {
                    pass = segment.transforms().get(i).transform(quad);
                }
                if (pass) store(quad);
                quad.emit(); // into the work mesh, which is never read: resets the emitter
            });
        }

        /** After the model drew itself: anything still open (an unbalanced push), then the plain quads. */
        Capture finish() {
            while (this.segments.size() > 1) close(this.segments.remove(this.segments.size() - 1));
            this.segments.get(0).mesh().build().forEach(this::store);
            return this;
        }

        @Override public Map<Direction, List<BakedQuad>> culled() { return this.culled; }
        @Override public List<BakedQuad> unculled() { return this.unculled; }
        @Override public QuadEmitter getEmitter() { return this.segments.get(this.segments.size() - 1).mesh().getEmitter(); }
        @Override public boolean hasTransform() { return this.segments.size() > 1; }
        @Override public boolean isFaceCulled(Direction face) { return false; }
        @Override public BakedModelConsumer bakedModelConsumer() { return this.models; }

        @Override
        public void pushTransform(QuadTransform transform) {
            List<QuadTransform> active = new ArrayList<>(this.segments.get(this.segments.size() - 1).transforms());
            active.add(transform);
            this.segments.add(new Segment(this.renderer.meshBuilder(), active));
        }

        @Override
        public void popTransform() {
            if (this.segments.size() > 1) close(this.segments.remove(this.segments.size() - 1));
        }

        @Override
        public java.util.function.Consumer<Mesh> meshConsumer() {
            return mesh -> mesh.outputTo(getEmitter());
        }
    }

    private static final Direction[] SIDES = {
        null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Sodium is the Fabric renderer (always, with Roxy): its own context class is required. */
    private static final boolean SODIUM = isSodium();

    private static boolean isSodium() {
        try {
            return RendererAccess.INSTANCE.getRenderer().getClass().getName().startsWith("net.caffeinemc.");
        } catch (Throwable t) {
            return false;
        }
    }

    private static Quads emit(BakedModel model, BlockState state, BlockPos pos, TextureAtlasSprite sprite) {
        BlockAndTintGetter level = standIn(state, pos);
        if (SODIUM) {
            // Sodium draws a block once per render type of its model, and some FRAPI models refuse to
            // draw without one (Continuity's emissive overlays): the same here, one pass per type.
            SodiumCtmContext context = new SodiumCtmContext(sprite, level, state, pos);
            boolean any = false;
            for (net.minecraft.client.renderer.RenderType type : model.getRenderTypes(state,
                    new SingleThreadedRandomSource(42L), net.neoforged.neoforge.client.model.data.ModelData.EMPTY)) {
                context.renderType(type);
                ((FabricBakedModel) model).emitBlockQuads(level, state, pos, () -> new SingleThreadedRandomSource(42L), context);
                any = true;
            }
            if (!any) ((FabricBakedModel) model).emitBlockQuads(level, state, pos, () -> new SingleThreadedRandomSource(42L), context);
            return context;
        }
        Capture capture = new Capture(sprite);
        ((FabricBakedModel) model).emitBlockQuads(level, state, pos, () -> new SingleThreadedRandomSource(42L), capture);
        return capture.finish();
    }

    // ========== Repeat textures ==========

    private static final int MAX_PERIOD = 8;
    private static final int[] NOT_REPEAT = new int[0];
    private static final Map<BlockState, int[]> PERIODS = new java.util.concurrent.ConcurrentHashMap<>();
    /** A second spot, off every 2, 3, 4 and 8 block repeat and with a different random hash. */
    private static final BlockPos OTHER = new BlockPos(5, 7, 3);

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
    private static int[] periods(BakedModel model, BlockState state, Quads origin, TextureAtlasSprite sprite) {
        int[] p = PERIODS.computeIfAbsent(state, s -> {
            List<Float> base = uvs(origin);
            if (base.equals(uvs(emit(model, s, OTHER, sprite)))) return NOT_REPEAT;
            int px = period(model, s, base, sprite, 1, 0, 0);
            int py = period(model, s, base, sprite, 0, 1, 0);
            int pz = period(model, s, base, sprite, 0, 0, 1);
            return px == 0 || py == 0 || pz == 0 ? new int[]{0, 0, 0} : new int[]{px, py, pz};
        });
        return p == NOT_REPEAT ? null : p;
    }

    /** Smallest step along (dx, dy, dz) after which the textures repeat (1-8), 0 if none. */
    private static int period(BakedModel model, BlockState state, List<Float> base, TextureAtlasSprite sprite, int dx, int dy, int dz) {
        for (int k = 1; k <= MAX_PERIOD; k++) {
            if (uvs(emit(model, state, new BlockPos(dx * k, dy * k, dz * k), sprite)).equals(base)) return k;
        }
        return 0;
    }

    private static List<Float> uvs(Quads capture) {
        List<Float> out = new ArrayList<>();
        for (Direction side : SIDES) {
            List<BakedQuad> quads = side == null ? capture.unculled() : capture.culled().getOrDefault(side, List.of());
            for (BakedQuad q : quads) {
                for (int v = 0; v < 4; v++) {
                    out.add(QuadData.u(q, v));
                    out.add(QuadData.v(q, v));
                }
            }
        }
        return out;
    }

    /**
     * AVERAGE mode, after the bakery baked piece (0,0,0) of a repeat block into {@code output}:
     * bakes the other pieces of the cycle and writes the per-pixel average colour of each face
     * (depth and coverage stay those of piece (0,0,0): every piece has the same geometry). A face's
     * texture depends only on the offsets in its own plane; one piece (x, y, z) serves all three
     * planes, and with z = (x + y) mod period an N x N x N cycle takes N*N bakes. Results are kept
     * by {@link CtmAverageCache}.
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

    /** Totals for the log. */
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

    // ========== Stand-in world ==========

    /** The block at {@code origin}; around it copies of itself, or air for plants. */
    private static BlockAndTintGetter standIn(BlockState state, BlockPos origin) {
        BlockState around = PlantLook.isPlant(state) ? Blocks.AIR.defaultBlockState() : state;
        return new BlockAndTintGetter() {
            @Override public float getShade(Direction direction, boolean shade) { return 1.0f; }
            @Override public LevelLightEngine getLightEngine() { return null; }
            @Override public int getBlockTint(BlockPos p, ColorResolver r) { return -1; }
            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
            @Override public BlockState getBlockState(BlockPos p) { return p.equals(origin) ? state : around; }
            @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
    }
}
