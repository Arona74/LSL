package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether the installed Voxy is one these mixins were written against. Voxy has no API: every hook
 * reaches into its internals (mipper, mesher, shader sources), so an unknown build leaves LSL idle
 * rather than guessing.
 */
public final class VoxyGate {
    private VoxyGate() {}

    /** Voxy builds whose hooked code is known to match. 0.2.18-beta = commit 4643445e. */
    private static final String[] SUPPORTED = {"0.2.18-beta"};

    /** Ground ordinary plants on partial layers (resolved from the config once). */
    public static volatile boolean grounding;

    private static Boolean active;
    private static String reason = "";
    private static String version = "?";

    public static synchronized boolean active() {
        if (active == null) {
            io.arona74.lsl.fabric.LslFabricConfig.load();
            var voxy = FabricLoader.getInstance().getModContainer("voxy");
            if (voxy.isEmpty()) {
                active = false;
                reason = "Voxy is not installed";
            } else {
                version = voxy.get().getMetadata().getVersion().getFriendlyString();
                boolean known = false;
                for (String v : SUPPORTED) known |= version.equals(v);
                active = known;
                if (!known) {
                    reason = "Voxy " + version + " is not a supported build (supported: " + String.join(", ", SUPPORTED) + ")";
                }
            }
            // Kept here, not in PlantShift: this runs from the mixin plugin, before any Voxy class
            // may be loaded, and PlantShift names Voxy types.
            grounding = groundPlants();
        }
        return active;
    }

    public static String reason() {
        return reason;
    }

    public static String voxyVersion() {
        return version;
    }

    public static boolean groundPlants() {
        return LslConfig.groundPlants(FabricLoader.getInstance().isModLoaded("aronalayersgen"));
    }
}
