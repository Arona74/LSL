package io.arona74.lsl.config;

import io.arona74.lsl.Compat;

import java.util.Locale;

/**
 * The groups options are sorted into: a tab of the config screen, and a section of lsl.json.
 * Declaration order is the order of both. One tab per LOD renderer.
 */
public enum ConfigTab {
    DISTANT_HORIZONS(true),
    /** Only in the version modules that carry Voxy hooks (Compat.VOXY_HOOKS, a compile-time constant). */
    VOXY(Compat.VOXY_HOOKS);

    /** Kept for the screen code shared with AronaLayers-gen's design: no LSL tab shapes worldgen. */
    public final boolean worldgen = false;

    /** Whether this version of LSL has anything to configure here. */
    public final boolean available;

    ConfigTab(boolean available) {
        this.available = available;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String langKey() {
        return "config.lsl.tab." + id();
    }
}
