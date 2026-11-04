package io.arona74.lsl.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.lsl.Lsl;
import io.arona74.lsl.dh.DhStats;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;


/**
 * Distant Horizons exact-height diagnostics (DhStats), as a client command: how long each hook takes
 * next to the DH work it sits in, and switches to build LODs without the refine pass or the column
 * view trim. (Was {@code /algdebug dh} in AronaLayers-gen.)
 *
 * <ul>
 *   <li>{@code /lsl dh}: timings since the last reset (also written to the log).</li>
 *   <li>{@code /lsl dh reset}: start a new measurement.</li>
 *   <li>{@code /lsl dh skip <none|refine>}: turn the refine pass off for new LODs.</li>
 *   <li>{@code /lsl dh skip trim}: toggle the column view trim in mesh builds.</li>
 * </ul>
 */
public final class DhDebugCommand {
    private DhDebugCommand() {}

    /** The {@code dh} branch, added under {@code /lsl} by LslFabricClient. */
    static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return literal("dh").executes(DhDebugCommand::report)
            .then(literal("reset").executes(DhDebugCommand::reset))
            .then(literal("skip")
                .then(literal("none").executes(ctx -> skip(ctx, false)))
                .then(literal("refine").executes(ctx -> skip(ctx, true)))
                .then(literal("trim").executes(DhDebugCommand::skipTrim)));
    }

    /** Brigadier's own builder: Fabric renamed its ClientCommandManager helper to ClientCommands in 26.x. */
    static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static int report(CommandContext<FabricClientCommandSource> ctx) {
        for (String line : DhStats.report()) {
            send(ctx.getSource(), line);
            Lsl.LOGGER.info(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<FabricClientCommandSource> ctx) {
        DhStats.reset();
        send(ctx.getSource(), "[LSL dh] timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    private static int skipTrim(CommandContext<FabricClientCommandSource> ctx) {
        DhStats.skipTrim = !DhStats.skipTrim;
        DhStats.reset();
        send(ctx.getSource(), "[LSL dh] column view trim " + (DhStats.skipTrim ? "off" : "on") + " from now on; timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    private static int skip(CommandContext<FabricClientCommandSource> ctx, boolean refine) {
        DhStats.skipRefine = refine;
        DhStats.skipTrim = false;
        DhStats.reset();
        send(ctx.getSource(), "[LSL dh] skipping " + (refine ? "refine" : "nothing") + " from now on; timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    private static void send(FabricClientCommandSource source, String msg) {
        source.sendFeedback(Component.literal(msg));
    }
}
