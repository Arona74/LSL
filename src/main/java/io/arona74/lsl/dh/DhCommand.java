package io.arona74.lsl.dh;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.arona74.lsl.Lsl;

import java.util.function.BiConsumer;

/**
 * Distant Horizons exact-height diagnostics (DhStats), as a client command: how long each hook takes
 * next to the DH work it sits in, and switches to build LODs without the refine pass or the column
 * view trim. (Was {@code /algdebug dh} in AronaLayers-gen.) Loader-neutral: each loader passes how
 * its command source shows a line of chat.
 *
 * <ul>
 *   <li>{@code /lsl dh}: timings since the last reset (also written to the log).</li>
 *   <li>{@code /lsl dh reset}: start a new measurement.</li>
 *   <li>{@code /lsl dh skip <none|refine>}: turn the refine pass off for new LODs.</li>
 *   <li>{@code /lsl dh skip trim}: toggle the column view trim in mesh builds.</li>
 * </ul>
 */
public final class DhCommand {
    private DhCommand() {}

    /** The {@code dh} branch, to add under {@code /lsl}. */
    public static <S> LiteralArgumentBuilder<S> node(BiConsumer<S, String> send) {
        return DhCommand.<S>literal("dh").executes(ctx -> {
                for (String line : DhStats.report()) {
                    send.accept(ctx.getSource(), line);
                    Lsl.LOGGER.info(line);
                }
                return Command.SINGLE_SUCCESS;
            })
            .then(DhCommand.<S>literal("reset").executes(ctx -> {
                DhStats.reset();
                send.accept(ctx.getSource(), "[LSL dh] timings reset.");
                return Command.SINGLE_SUCCESS;
            }))
            .then(DhCommand.<S>literal("skip")
                .then(DhCommand.<S>literal("none").executes(ctx -> skip(ctx.getSource(), send, false)))
                .then(DhCommand.<S>literal("refine").executes(ctx -> skip(ctx.getSource(), send, true)))
                .then(DhCommand.<S>literal("trim").executes(ctx -> {
                    DhStats.skipTrim = !DhStats.skipTrim;
                    DhStats.reset();
                    send.accept(ctx.getSource(), "[LSL dh] column view trim " + (DhStats.skipTrim ? "off" : "on")
                        + " from now on; timings reset.");
                    return Command.SINGLE_SUCCESS;
                })));
    }

    /** Brigadier's own builder, the same on every loader and version. */
    public static <S> LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S> int skip(S source, BiConsumer<S, String> send, boolean refine) {
        DhStats.skipRefine = refine;
        DhStats.skipTrim = false;
        DhStats.reset();
        send.accept(source, "[LSL dh] skipping " + (refine ? "refine" : "nothing") + " from now on; timings reset.");
        return Command.SINGLE_SUCCESS;
    }
}
