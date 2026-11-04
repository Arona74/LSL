package io.arona74.lsl.fabric;

import io.arona74.lsl.LslConfig;
import io.arona74.lsl.config.ConfigOption;
import net.fabricmc.loader.api.FabricLoader;

/** Loads lsl.json the Fabric way: the game's config folder, and Fabric's answer to "is this mod loaded". */
public final class LslFabricConfig {
    private LslFabricConfig() {}

    public static void load() {
        ConfigOption.modLoaded = FabricLoader.getInstance()::isModLoaded;
        LslConfig.load(FabricLoader.getInstance().getConfigDir());
    }
}
