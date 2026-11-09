package io.arona74.lsl.voxy;

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
 * <p>Textures that change with position (repeat patterns, random textures) are handled by
 * {@link CtmAverage}. Option {@code voxy_ctm}.
 */
public final class CtmBake {
    private CtmBake() {}

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
        BlockPos piece = CtmAverage.piece();
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
            if (!CtmAverage.useDrawn(state, sprite, uvs(origin), pos -> uvs(emit(model, state, pos, sprite)))) {
                return new Baked(model, state, piece, null, null); // random textures, or BASE: the base texture tiles alone
            }
            if (origin.unculled().isEmpty() && origin.culled().isEmpty()) return new Baked(model, state, piece, null, null);
            return new Baked(model, state, piece, origin.culled(), origin.unculled());
        } catch (Throwable t) {
            CtmAverage.failed(state, t);
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

    private static List<Float> uvs(Quads capture) {
        List<Float> out = new ArrayList<>();
        for (Direction side : SIDES) {
            List<BakedQuad> quads = side == null ? capture.unculled() : capture.culled().getOrDefault(side, List.of());
            for (BakedQuad q : quads) {
                for (int v = 0; v < 4; v++) {
                    out.add(VoxyMc.u(q, v));
                    out.add(VoxyMc.v(q, v));
                }
            }
        }
        return out;
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
