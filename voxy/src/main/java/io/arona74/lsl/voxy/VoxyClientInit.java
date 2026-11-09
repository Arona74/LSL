package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.config.ConfigTab;

/**
 * Client start with Voxy: marks the Voxy tab usable or not, and reports which hooks are on (the
 * hooks themselves are mixins, see LslMixinPlugin). Called by the loader's client entry point
 * (VoxyFabricEntry, LslNeoForgeClient); every NeoForge module provides this class.
 */
public final class VoxyClientInit {
    private VoxyClientInit() {}

    public static void init() {
        if (!Platform.isLoaded("voxy")) return;
        ConfigTab.VOXY.setCheck(() -> VoxyGate.active() ? null
            : new ConfigTab.Unusable("config.lsl.tab.unsupported", "Voxy", VoxyGate.voxyVersion(), VoxyGate.supported()));
        if (!VoxyGate.active()) {
            Lsl.LOGGER.warn("[LSL/Voxy] {}: Voxy hooks are off", VoxyGate.reason());
            return;
        }
        CtmAverageCache.register();
        String version = Platform.modVersion(Lsl.MOD_ID);
        Lsl.LOGGER.info("[LSL/Voxy] LSL {} on Voxy {} - layer mip: {}, shape culling: {}, hide grass blades: {}, full mipmaps: {}, split large faces: {}, connected textures: {} (repeat: {}), match tints: {}, flat layers: {}, plant offsets: {}{}, plant grounding: {},"
                + " draw plants: {}, plant face inset: {}, tall plants: {}, plant shading: {} (with shaders: {}), plant brightness: {}, plant saturation: {}",
            version == null ? "?" : version, VoxyGate.voxyVersion(),
            onOff(LslConfig.voxyLayerMip), onOff(LslConfig.voxyShapeCulling),
            onOff(LslConfig.voxyHideExtBlades), onOff(LslConfig.voxyFullMipmaps), onOff(LslConfig.voxySplitLargeQuads),
            onOff(LslConfig.voxyCtm), LslConfig.voxyCtmRepeat,
            Platform.TINT_MATCHING ? onOff(LslConfig.voxyMatchTints) : "not available here", onOff(LslConfig.voxyFlatLayers),
            onOff(LslConfig.voxyPlantOffset),
            LslConfig.voxyPlantOffset && !ShaderPatch.usable() ? " (shader anchors missing!)" : "",
            onOff(VoxyGate.groundPlants()), onOff(LslConfig.voxyDrawPlants), LslConfig.voxyPlantFaceInset, onOff(LslConfig.voxyTallPlants),
            onOff(LslConfig.voxyPlantShading), onOff(LslConfig.voxyPlantShadingWithShaders), LslConfig.voxyPlantBrightness, LslConfig.voxyPlantSaturation);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
