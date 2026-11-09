package io.arona74.lsl.voxy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.arona74.lsl.LslConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * {@code /lsl voxy} on NeoForge (loader-neutral over the command source, like DhCommand):
 * <ul>
 *   <li>{@code tint}: for the block under the crosshair, each tint index its model uses, coloured
 *       in the world, as plain Voxy captures it (block 0,0,0 in a stand-in world, this biome) and as
 *       LSL captures it. Diagnostics for Match Tint Colours.</li>
 *   <li>{@code ctm}: the repeat CTM average totals.</li>
 * </ul>
 * Every NeoForge module provides this class (LslNeoForgeClient adds it when the Voxy hooks are on).
 */
public final class VoxyCommand {
    private VoxyCommand() {}

    public static <S> LiteralArgumentBuilder<S> node(BiConsumer<S, String> send) {
        return VoxyCommand.<S>literal("voxy")
            .then(VoxyCommand.<S>literal("tint").executes(ctx -> tint(ctx.getSource(), send)))
            .then(VoxyCommand.<S>literal("ctm").executes(ctx -> {
                send.accept(ctx.getSource(), "[LSL] " + CtmAverage.STATS.summary());
                return Command.SINGLE_SUCCESS;
            }));
    }

    private static <S> LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S> int tint(S source, BiConsumer<S, String> send) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            send.accept(source, "[LSL] Look at a block first.");
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        Biome biome = mc.level.getBiome(pos).value();
        BlockColor provider = TintCapture.provider(state);
        send.accept(source, "[LSL] " + state + " at " + pos.toShortString() + ", biome "
            + mc.level.getBiome(pos).getRegisteredName() + ", tint height " + TintCapture.HEIGHT
            + ", match tints " + (LslConfig.voxyMatchTints ? "on" : "off"));
        if (provider == null) {
            send.accept(source, "  no colour provider");
            return Command.SINGLE_SUCCESS;
        }
        send.accept(source, "  provider " + provider.getClass().getName() + (TintCapture.isColormap(provider) ? " (Polytone)" : ""));
        TreeSet<Integer> indices = new TreeSet<>();
        for (BakedQuad quad : VoxyMc.blockQuads(state)) {
            if (quad.isTinted()) indices.add(quad.getTintIndex());
        }
        if (indices.isEmpty()) indices.add(0);
        BlockAndTintGetter standIn = standIn(state, biome);
        Object sampled = TintCapture.wrap(provider);
        for (int index : indices) {
            String world = hex(() -> provider.getColor(state, mc.level, pos, index));
            String voxy = hex(() -> provider.getColor(state, standIn, BlockPos.ZERO, index));
            String lsl = hex(() -> {
                TintCapture.beginCapture(biome);
                try {
                    return ((BlockColor) sampled).getColor(state, standIn, new BlockPos(0, TintCapture.HEIGHT, 0), index);
                } finally {
                    TintCapture.endCapture();
                }
            });
            send.accept(source, "  tint #" + index + "   world " + world + "   voxy " + voxy + "   lsl " + lsl);
        }
        return Command.SINGLE_SUCCESS;
    }

    private interface IntCall {
        int get();
    }

    private static String hex(IntCall call) {
        try {
            return String.format("#%06X", call.get() & 0xFFFFFF);
        } catch (Throwable t) {
            return "error " + t.getClass().getSimpleName();
        }
    }

    /** Like Voxy's capture world: one biome everywhere, no light, the block itself all around. */
    private static BlockAndTintGetter standIn(BlockState state, Biome biome) {
        return new BlockAndTintGetter() {
            @Override public float getShade(Direction direction, boolean shade) { return 1.0f; }
            @Override public LevelLightEngine getLightEngine() { return null; }
            @Override public int getBlockTint(BlockPos p, ColorResolver r) { return r.getColor(biome, 0, 0); }
            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
            @Override public BlockState getBlockState(BlockPos p) { return state; }
            @Override public FluidState getFluidState(BlockPos p) { return state.getFluidState(); }
            @Override public int getHeight() { return 0; }
            @Override public int getMinBuildHeight() { return 0; }
        };
    }
}
