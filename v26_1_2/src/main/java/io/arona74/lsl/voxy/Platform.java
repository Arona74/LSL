package io.arona74.lsl.voxy;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the shared Voxy hooks (voxy/src) need from this module's loader (Fabric), and the Voxy
 * builds it was checked against (0.2.18-beta). Every module that carries the hooks has its own
 * Platform with the same members.
 *
 * <p>Loaded from LSL's mixin plugin (VoxyGate), before any mixin is applied: it must not touch a
 * Minecraft class, since a class loaded this early can no longer be mixed into (Sodium then fails
 * to start). Minecraft's side is {@link VoxyMc}.
 */
public final class Platform {
    private Platform() {}

    // ========== Voxy builds ==========

    /** Voxy builds whose hooked code is known to match. 0.2.18-beta = commit 4643445e. */
    static final String[] VOXY_BUILDS = {"0.2.18-beta"};
    /** Voxy runs natively here. */
    static final VoxyGate.Bridge BRIDGE = null;
    /** Polytone tint matching (TintCapture) is ported to this module. */
    static final boolean TINT_MATCHING = true;

    // ========== Loader ==========

    /** A mod's version, or null if it isn't installed. Works from a mixin plugin. */
    static String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    static boolean isLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /** Loads LSL's config file (the gate may run before the mod initialises). */
    static void loadConfig() {
        io.arona74.lsl.fabric.LslFabricConfig.load();
    }

    /** Every installed mod as {@code id@version} (a new, mutable list). */
    static List<String> modVersions() {
        List<String> mods = new ArrayList<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            mods.add(m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString());
        }
        return mods;
    }

    static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    static void onLeaveWorld(Runnable action) {
        Events.onLeaveWorld(action);
    }

    static void onGameStop(Runnable action) {
        Events.onGameStop(action);
    }

    /** Fabric's client events name Minecraft classes (their listeners take them): kept out of Platform itself. */
    private static final class Events {
        static void onLeaveWorld(Runnable action) {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> action.run());
        }

        static void onGameStop(Runnable action) {
            ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> action.run());
        }
    }
}
