package io.arona74.lsl.neoforge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.arona74.lsl.client.config.LslConfigScreen;
import io.arona74.lsl.client.config.ScreenCompat;
import io.arona74.lsl.dh.DhCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client side: the config screen behind the mod list's Config button, and the {@code /lsl} client
 * command ({@code config}, and {@code dh} when Distant Horizons is installed).
 */
final class LslNeoForgeClient {
    private LslNeoForgeClient() {}

    static void init(IEventBus modBus, ModContainer container, boolean dh) {
        io.arona74.lsl.voxy.VoxyClientInit.init();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new LslConfigScreen(parent));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = DhCommand.<CommandSourceStack>literal("lsl")
                // Scheduled: the chat screen the command was typed in closes itself after running
                // it, and would close ours with it.
                .then(DhCommand.<CommandSourceStack>literal("config").executes(ctx -> {
                    ScreenCompat.schedule(() -> ScreenCompat.setScreen(new LslConfigScreen(null)));
                    return 1;
                }));
            if (dh) root.then(DhCommand.<CommandSourceStack>node((source, line) -> source.sendSuccess(() -> Component.literal(line), false)));
            event.getDispatcher().register(root);
        });
    }
}
