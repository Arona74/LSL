package io.arona74.lsl.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.arona74.lsl.client.config.LslConfigScreen;
import io.arona74.lsl.client.config.ScreenCompat;
import io.arona74.lsl.config.ConfigTab;
import io.arona74.lsl.dh.DhCommand;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client entrypoint: the {@code /lsl} client command (no permission needed).
 * <ul>
 *   <li>{@code /lsl config}: the config screen (also behind Mod Menu's Configure button).</li>
 *   <li>{@code /lsl dh ...}: Distant Horizons exact-height diagnostics, when DH is installed.</li>
 * </ul>
 */
public final class LslFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        boolean dh = FabricLoader.getInstance().isModLoaded("distanthorizons");
        if (dh && GenDh.present()) {
            ConfigTab.DISTANT_HORIZONS.setCheck(() -> new ConfigTab.Unusable("config.lsl.tab.gen_dh"));
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = DhCommand.<FabricClientCommandSource>literal("lsl")
                // Scheduled: the chat screen the command was typed in closes itself after running
                // it, and would close ours with it.
                .then(DhCommand.<FabricClientCommandSource>literal("config").executes(ctx -> {
                    ScreenCompat.schedule(() -> ScreenCompat.setScreen(new LslConfigScreen(null)));
                    return 1;
                }));
            if (dh) root.then(DhCommand.<FabricClientCommandSource>node((source, line) -> source.sendFeedback(Component.literal(line))));
            dispatcher.register(root);
        });
    }
}
