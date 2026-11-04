package io.arona74.lsl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LSL - Layer Supported LOD: layers drawn right in LOD renderers (Distant Horizons, Voxy), for
 * AronaLayers-gen and Conquest Reforged worlds. Loader entrypoints live in the loader source sets.
 */
public final class Lsl {
    private Lsl() {}

    public static final String MOD_ID = "lsl";
    public static final Logger LOGGER = LoggerFactory.getLogger("LSL");
}
