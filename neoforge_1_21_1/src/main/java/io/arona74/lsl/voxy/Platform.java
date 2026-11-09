package io.arona74.lsl.voxy;

import io.arona74.lsl.neoforge.LslNeoForgeConfig;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the shared Voxy hooks (voxy/src) need from this module's loader (NeoForge), and the Voxy
 * builds it was checked against (0.2.16-beta, the 1.21.11 build, as Roxy rewrites it). Every module that carries the hooks has its own
 * Platform with the same members.
 *
 * <p>Loaded from LSL's mixin plugin (VoxyGate), before any mixin is applied: it must not touch a
 * Minecraft class, since a class loaded this early can no longer be mixed into (Sodium then fails
 * to start). Minecraft's side is {@link VoxyMc}.
 */
public final class Platform {
    private Platform() {}

    // ========== Voxy builds ==========

    /** Voxy builds whose hooked code is known to match: 0.2.16-beta (1.21.11 build, commit 59b62bee). */
    static final String[] VOXY_BUILDS = {"0.2.16-beta"};
    /**
     * LSL hooks Voxy as Roxy rewrites it for 1.21.1 (method shapes, Roxy's bridge calls), so a Roxy
     * update can move what LSL hooks: only checked Roxy builds.
     */
    static final VoxyGate.Bridge BRIDGE = new VoxyGate.Bridge("roxy", "Roxy", new String[]{"0.4.1"});
    /** Polytone tint matching (TintCapture) isn't ported to this module yet. */
    static final boolean TINT_MATCHING = false;

    // ========== Loader ==========

    /** A mod's version from FML's list of mod files (available before mods load), or null. */
    static String modVersion(String modId) {
        try {
            var file = FMLLoader.getLoadingModList().getModFileById(modId);
            if (file == null) return null;
            for (var mod : file.getMods()) {
                if (mod.getModId().equals(modId)) return mod.getVersion().toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean isLoaded(String modId) {
        return LslNeoForgeConfig.isLoaded(modId);
    }

    /** Loads LSL's config file (the gate may run before the mod initialises). */
    static void loadConfig() {
        LslNeoForgeConfig.load();
    }

    /** Every installed mod as {@code id@version} (a new, mutable list). */
    static List<String> modVersions() {
        List<String> mods = new ArrayList<>();
        for (var m : ModList.get().getMods()) mods.add(m.getModId() + "@" + m.getVersion());
        return mods;
    }

    static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    static void onLeaveWorld(Runnable action) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> action.run());
    }

    static void onGameStop(Runnable action) {
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> action.run());
    }
}
