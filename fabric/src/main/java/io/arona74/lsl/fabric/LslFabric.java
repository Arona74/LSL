package io.arona74.lsl.fabric;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Common entrypoint: Distant Horizons support. DH builds LODs on dedicated servers too, so this runs
 * on both sides.
 */
public final class LslFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        io.arona74.lsl.fabric.LslFabricConfig.load();
        if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) return;
        if (GenDh.present()) {
            Lsl.LOGGER.warn("[LSL/DH] this AronaLayers-gen still has its own Distant Horizons support: LSL's stays off."
                + " Update AronaLayers-gen to use LSL's.");
            return;
        }
        // The compat class names DH types, so it is only loaded when DH is installed; any API
        // mismatch is logged rather than taking the game down.
        try {
            io.arona74.lsl.dh.DistantHorizonsCompat.register();
        } catch (Throwable t) {
            Lsl.LOGGER.warn("[LSL/DH] support disabled: {}", t.toString());
        }
    }
}
