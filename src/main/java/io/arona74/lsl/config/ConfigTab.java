package io.arona74.lsl.config;

import io.arona74.lsl.Compat;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The groups options are sorted into: a tab of the config screen, and a section of lsl.json.
 * Declaration order is the order of both. One tab per LOD renderer.
 */
public enum ConfigTab {
    DISTANT_HORIZONS(true, "distanthorizons", "Distant Horizons"),
    /** Only in the version modules that carry Voxy hooks (Compat.VOXY_HOOKS, a compile-time constant). */
    VOXY(Compat.VOXY_HOOKS, "voxy", "Voxy");

    /** Kept for the screen code shared with AronaLayers-gen's design: no LSL tab shapes worldgen. */
    public final boolean worldgen = false;

    /** Whether this version of LSL has anything to configure here. */
    public final boolean available;

    /** The mod this tab configures, and its display name. */
    public final String mod;
    public final String modName;

    /** Why a tab can't be used right now: a lang key and its arguments. */
    public record Unusable(String langKey, String... args) {}

    /** Extra check a version module sets (e.g. a supported Voxy build); null = none. */
    private volatile Supplier<Unusable> check;

    ConfigTab(boolean available, String mod, String modName) {
        this.available = available;
        this.mod = mod;
        this.modName = modName;
    }

    public void setCheck(Supplier<Unusable> check) {
        this.check = check;
    }

    /** Why this tab can't be used (its mod missing, or a version LSL doesn't support), or null. */
    public Unusable unusable() {
        if (!ConfigOption.modLoaded.test(mod)) return new Unusable("config.lsl.tab.missing", modName);
        if (!available) return new Unusable("config.lsl.tab.no_support", modName);
        Supplier<Unusable> c = check;
        return c == null ? null : c.get();
    }

    public boolean usable() {
        return unusable() == null;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String langKey() {
        return "config.lsl.tab." + id();
    }
}
