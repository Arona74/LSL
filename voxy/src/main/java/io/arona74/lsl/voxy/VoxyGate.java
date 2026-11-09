package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;

/**
 * Whether the installed Voxy is one these mixins were written against. Voxy has no API: every hook
 * reaches into its internals (mipper, mesher, shader sources), so an unknown build leaves LSL idle
 * rather than guessing. Where Voxy runs through a bridge (Roxy on NeoForge), the bridge's build is
 * checked too: LSL hooks Voxy as the bridge rewrote it.
 */
public final class VoxyGate {
    private VoxyGate() {}

    /** A mod Voxy runs through here (Roxy on NeoForge), and its builds whose rewrite of Voxy was checked. */
    public record Bridge(String modId, String name, String[] builds) {}

    /** Ground ordinary plants on partial layers (resolved from the config once). */
    public static volatile boolean grounding;

    private static Boolean active;
    private static String reason = "";
    private static String version = "?";

    public static synchronized boolean active() {
        if (active == null) {
            Platform.loadConfig();
            String voxy = Platform.modVersion("voxy");
            Bridge bridge = Platform.BRIDGE;
            String bridgeVersion = bridge == null ? null : Platform.modVersion(bridge.modId());
            active = false;
            if (voxy == null) {
                reason = "Voxy is not installed";
            } else {
                version = voxy;
                if (!contains(Platform.VOXY_BUILDS, voxy)) {
                    reason = "Voxy " + voxy + " is not a supported build (supported: " + String.join(", ", Platform.VOXY_BUILDS) + ")";
                } else if (bridge != null && bridgeVersion == null) {
                    reason = "Voxy runs here through " + bridge.name() + ", which is not installed";
                } else if (bridge != null && !contains(bridge.builds(), bridgeVersion)) {
                    reason = bridge.name() + " " + bridgeVersion + " is not a supported build (supported: " + String.join(", ", bridge.builds()) + ")";
                } else {
                    active = true;
                    if (bridge != null) version = voxy + " (" + bridge.name() + " " + bridgeVersion + ")";
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

    public static String reason() {
        return reason;
    }

    /** The supported builds, for display. */
    public static String supported() {
        String voxy = String.join(", ", Platform.VOXY_BUILDS);
        Bridge bridge = Platform.BRIDGE;
        return bridge == null ? voxy : voxy + " with " + bridge.name() + " " + String.join(", ", bridge.builds());
    }

    public static String voxyVersion() {
        return version;
    }

    public static boolean groundPlants() {
        return LslConfig.groundPlants(Platform.isLoaded("aronalayersgen"));
    }
}
