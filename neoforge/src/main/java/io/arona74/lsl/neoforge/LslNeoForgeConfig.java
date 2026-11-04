package io.arona74.lsl.neoforge;

import io.arona74.lsl.LslConfig;
import io.arona74.lsl.config.ConfigOption;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Loads lsl.json the NeoForge way: the game's config folder, and FML's list of mod files for "is
 * this mod loaded". That list exists before any mod is constructed, so the mixin plugins can ask too.
 */
public final class LslNeoForgeConfig {
    private LslNeoForgeConfig() {}

    public static void load() {
        ConfigOption.modLoaded = LslNeoForgeConfig::isLoaded;
        LslConfig.load(FMLPaths.CONFIGDIR.get());
    }

    public static boolean isLoaded(String modId) {
        try {
            return FMLLoader.getLoadingModList().getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
