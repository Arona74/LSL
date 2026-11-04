package io.arona74.lsl.neoforge;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.config.ConfigTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge entrypoint. Distant Horizons support (both sides: DH builds LODs on dedicated servers
 * too), then the client part (config screen, {@code /lsl} commands).
 */
@Mod(Lsl.MOD_ID)
public final class LslNeoForge {
    public LslNeoForge(IEventBus modBus, ModContainer container) {
        LslNeoForgeConfig.load();
        boolean dh = LslNeoForgeConfig.isLoaded("distanthorizons");
        if (dh && GenDh.present()) {
            Lsl.LOGGER.warn("[LSL/DH] this AronaLayers-gen still has its own Distant Horizons support: LSL's stays off."
                + " Update AronaLayers-gen to use LSL's.");
            ConfigTab.DISTANT_HORIZONS.setCheck(() -> new ConfigTab.Unusable("config.lsl.tab.gen_dh"));
        } else if (dh) {
            // The compat class names DH types, so it is only loaded when DH is installed; any API
            // mismatch is logged rather than taking the game down.
            try {
                io.arona74.lsl.dh.DistantHorizonsCompat.register();
            } catch (Throwable t) {
                Lsl.LOGGER.warn("[LSL/DH] support disabled: {}", t.toString());
            }
        }
        if (FMLEnvironment.dist == Dist.CLIENT) LslNeoForgeClient.init(modBus, container, dh);
    }
}
