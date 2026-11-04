package io.arona74.lsl.voxy;

import net.caffeinemc.mods.sodium.client.render.frapi.mesh.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext;
import net.caffeinemc.mods.sodium.client.services.SodiumModelData;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The render context CtmBake hands to Continuity when Sodium is the Fabric renderer (always the case
 * with Roxy). Sodium replaces every plain model's {@code emitBlockQuads} with one that casts the
 * context to its own AbstractBlockRenderContext, so a context of any other class fails as soon as
 * Continuity forwards to the model it wraps. This one is Sodium's: Sodium applies the pushed quad
 * transforms (Continuity's CTM) as usual, and the finished quads, instead of being lit and written
 * to a chunk buffer, are kept as BakedQuads by cull face. Culling is off, and no light or world
 * access is needed on that path.
 *
 * <p>Sodium internals: written against Sodium 0.8.13 for NeoForge 1.21.1 (one abstract method,
 * {@code processQuad}; the fields set below). Only loaded when Sodium is the renderer.
 */
final class SodiumCtmContext extends AbstractBlockRenderContext implements CtmBake.Quads {
    private final Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
    private final List<BakedQuad> unculled = new ArrayList<>();
    private final TextureAtlasSprite sprite;

    SodiumCtmContext(TextureAtlasSprite sprite, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        this.sprite = sprite;
        this.level = level;
        this.state = state;
        this.pos = pos;
        this.type = null; // every quad of the model, unless a pass sets its render type
        this.modelData = SodiumModelData.EMPTY;
        this.useAO = TriState.DEFAULT;
        this.random = new SingleThreadedRandomSource(42L);
        this.randomSeed = 42L;
        this.prepareCulling(false);
    }

    /** The render type of the next pass (CtmBake draws the model once per type it declares). */
    void renderType(net.minecraft.client.renderer.RenderType type) {
        this.type = type;
    }

    @Override
    protected void processQuad(MutableQuadViewImpl quad) {
        BakedQuad baked = quad.toBakedQuad(this.sprite);
        Direction cull = quad.cullFace();
        if (cull == null) this.unculled.add(baked);
        else this.culled.computeIfAbsent(cull, k -> new ArrayList<>()).add(baked);
    }

    @Override
    public Map<Direction, List<BakedQuad>> culled() {
        return this.culled;
    }

    @Override
    public List<BakedQuad> unculled() {
        return this.unculled;
    }
}
