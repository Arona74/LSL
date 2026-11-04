package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import io.arona74.lsl.neoforge.LslNeoForgeConfig;
import net.neoforged.fml.loading.FMLLoader;

/**
 * Whether the installed Voxy is one these mixins were written against. Voxy has no API: every hook
 * reaches into its internals (mipper, mesher, shader sources), so an unknown build leaves LSL idle
 * rather than guessing.
 */
public final class VoxyGate {
    private VoxyGate() {}

    /** Voxy builds whose hooked code is known to match: 0.2.16-beta (1.21.11 build, commit 59b62bee) through Roxy. */
    private static final String[] SUPPORTED = {"0.2.16-beta"};

    /** Ground ordinary plants on partial layers (resolved from the config once). */
    public static volatile boolean grounding;

    private static Boolean active;
    private static String reason = "";
    private static String version = "?";

    /**
     * Roxy builds whose patched Voxy was checked: LSL hooks Voxy as Roxy rewrites it for 1.21.1
     * (method shapes, Roxy's bridge calls), so a Roxy update can move what LSL hooks.
     */
    private static final String[] SUPPORTED_ROXY = {"0.4.1"};

    public static synchronized boolean active() {
        if (active == null) {
            LslNeoForgeConfig.load();
            String voxy = modVersion("voxy");
            String roxy = modVersion("roxy");
            if (voxy == null) {
                active = false;
                reason = "Voxy is not installed";
            } else {
                version = voxy;
                if (!contains(SUPPORTED, voxy)) {
                    active = false;
                    reason = "Voxy " + voxy + " is not a supported build (supported: " + String.join(", ", SUPPORTED) + ")";
                } else if (roxy == null) {
                    active = false;
                    reason = "Voxy runs on NeoForge through Roxy, which is not installed";
                } else if (!contains(SUPPORTED_ROXY, roxy)) {
                    active = false;
                    reason = "Roxy " + roxy + " is not a supported build (supported: " + String.join(", ", SUPPORTED_ROXY) + ")";
                } else {
                    active = true;
                    version = voxy + " (Roxy " + roxy + ")";
                }
            }
            // Kept here, not in PlantShift: this runs from the mixin plugin, before any Voxy class
            // may be loaded, and PlantShift names Voxy types.
            grounding = groundPlants();
        }
        return active;
    }

    private static boolean contains(String[] list, String value) {
        for (String v : list) if (v.equals(value)) return true;
        return false;
    }

    /** A mod's version from FML's list of mod files (available before mods load), or null. */
    private static String modVersion(String modId) {
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

    public static String reason() {
        return reason;
    }

    /** The supported builds, for display. */
    public static String supported() {
        return String.join(", ", SUPPORTED) + " with Roxy " + String.join(", ", SUPPORTED_ROXY);
    }

    public static String voxyVersion() {
        return version;
    }

    public static boolean groundPlants() {
        return LslConfig.groundPlants(LslNeoForgeConfig.isLoaded("aronalayersgen"));
    }
}
