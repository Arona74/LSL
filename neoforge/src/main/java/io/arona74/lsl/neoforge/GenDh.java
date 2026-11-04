package io.arona74.lsl.neoforge;

/**
 * AronaLayers-gen up to 2.0.0a3 shipped the Distant Horizons support LSL now carries (on NeoForge it
 * runs through Sinytra Connector). With such a gen installed both would hook DH, so LSL's DH support
 * stands aside. Detected by the class file, without loading it.
 */
final class GenDh {
    private GenDh() {}

    private static final String GEN_DH_CLASS = "io/arona74/aronalayersgen/compat/DistantHorizonsCompat.class";

    static boolean present() {
        return GenDh.class.getClassLoader().getResource(GEN_DH_CLASS) != null;
    }
}
