package io.arona74.lsl.voxy;

import net.fabricmc.api.ClientModInitializer;

/** Fabric client entry point for the Voxy hooks: the {@code /lsl voxy} commands, then VoxyClientInit. */
public final class VoxyFabricEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (Platform.isLoaded("voxy")) VoxyTintCommand.register();
        VoxyClientInit.init();
    }
}
