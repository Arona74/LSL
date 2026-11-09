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
 * Connected and random textures (Continuity) in Voxy's LODs, Minecraft 26.x: drawing the model.
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
 * which gives the fully connected look walls and floors have from afar. Textures that change with
 * position (repeat patterns, random textures) are handled by {@link CtmAverage}. Option
 * {@code voxy_ctm}.
 */
public final class CtmBake {
    private CtmBake() {}

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
            // A piece bake (AVERAGE mode) draws its own piece.
            BlockPos piece = CtmAverage.piece();
            MutableMesh mesh = emit(model, state, piece != null ? piece : BlockPos.ZERO);
            if (!CtmAverage.useDrawn(state, sprite, uvs(mesh), pos -> uvs(emit(model, state, pos)))) {
                out.addAll(plain);
                return;
            }
            Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
            List<BakedQuad> unculled = new ArrayList<>();
            add(mesh, sprite, layer, culled, unculled);
            if (unculled.isEmpty() && culled.isEmpty()) {
                out.addAll(plain);
                return;
            }
            boolean ao = plain.isEmpty() || plain.get(0).useAmbientOcclusion();
            out.add(new Part(culled, unculled, ao, particle, model.materialFlags()));
        } catch (Throwable t) {
            CtmAverage.failed(state, t);
            out.addAll(plain);
        }
    }

    private static MutableMesh emit(BlockStateModel model, BlockState state, BlockPos pos) {
        MutableMesh mesh = Renderer.get().mutableMesh();
        model.emitQuads(mesh.emitter(), standIn(state, pos), pos, state, new SingleThreadedRandomSource(42L), d -> false);
        return mesh;
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

    /** The block at {@code origin}; around it copies of itself, or air for plants. */
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
