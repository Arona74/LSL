package io.arona74.lsl.voxy;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.arona74.lsl.LslConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * {@code /lsl voxy tint}: for the block under the crosshair, each tint source's colour in the world,
 * as plain Voxy captures it (block 0,0,0 in a stand-in world, this biome), and as LSL captures it.
 * Diagnostics for Match Tint Colours.
 */
public final class VoxyTintCommand {
    private VoxyTintCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(lit("lsl").then(lit("voxy")
                .then(lit("tint").executes(ctx -> run(ctx.getSource())))
                .then(lit("ctm").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("[LSL] " + CtmAverage.STATS.summary()));
                    return 1;
                })))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static int run(FabricClientCommandSource out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            out.sendFeedback(Component.literal("[LSL] Look at a block first."));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        Biome biome = mc.level.getBiome(pos).value();
        List<BlockTintSource> sources = mc.getBlockColors().getTintSources(state);
        out.sendFeedback(Component.literal("[LSL] " + state + " at " + pos.toShortString() + ", biome "
            + mc.level.getBiome(pos).getRegisteredName() + ", tint height " + TintCapture.HEIGHT
            + ", match tints " + (LslConfig.voxyMatchTints ? "on" : "off")));
        if (sources.isEmpty()) {
            out.sendFeedback(Component.literal("  no tint sources"));
            return 1;
        }
        BlockAndTintGetter standIn = standIn(state, biome);
        for (int i = 0; i < sources.size(); i++) {
            BlockTintSource s = sources.get(i);
            if (s == null) {
                out.sendFeedback(Component.literal("  #" + i + " none"));
                continue;
            }
            String world = hex(() -> s.colorInWorld(state, mc.level, pos));
            String voxy = hex(() -> s.colorInWorld(state, standIn, BlockPos.ZERO));
            String lsl = hex(() -> TintCapture.colour(s, state, standIn, biome));
            out.sendFeedback(Component.literal("  #" + i + " " + s.getClass().getName()
                + (TintCapture.isColormap(s) ? " (Polytone)" : "")
                + "\n     world " + world + "   voxy " + voxy + "   lsl " + lsl));
        }
        return 1;
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
            @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
            @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
            @Override public int getBlockTint(BlockPos p, ColorResolver r) { return r.getColor(biome, 0, 0); }
            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
            @Override public BlockState getBlockState(BlockPos p) { return state; }
            @Override public FluidState getFluidState(BlockPos p) { return state.getFluidState(); }
            @Override public int getHeight() { return 0; }
            @Override public int getMinY() { return 0; }
        };
    }
}
