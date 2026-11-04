package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Reports which Voxy hooks are on (the hooks themselves are mixins, see LslMixinPlugin). */
public final class VoxyClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) return;
        VoxyTintCommand.register();
        if (!VoxyGate.active()) {
            Lsl.LOGGER.warn("[LSL/Voxy] {}: Voxy hooks are off", VoxyGate.reason());
            return;
        }
        String version = FabricLoader.getInstance().getModContainer("lsl")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        Lsl.LOGGER.info("[LSL/Voxy] LSL {} on Voxy {} - layer mip: {}, shape culling: {}, hide grass blades: {}, full mipmaps: {}, split large faces: {}, connected textures: {}, match tints: {} (height {}), flat layers: {}, plant offsets: {}{}, plant grounding: {},"
                + " draw plants: {}, plant face inset: {}, tall plants: {}, plant shading: {} (with shaders: {}), plant brightness: {}, plant saturation: {}",
            version, VoxyGate.voxyVersion(),
            onOff(LslConfig.voxyLayerMip), onOff(LslConfig.voxyShapeCulling),
            onOff(LslConfig.voxyHideExtBlades), onOff(LslConfig.voxyFullMipmaps), onOff(LslConfig.voxySplitLargeQuads), onOff(LslConfig.voxyCtm), onOff(LslConfig.voxyMatchTints), LslConfig.voxyTintHeight, onOff(LslConfig.voxyFlatLayers),
            onOff(LslConfig.voxyPlantOffset),
            LslConfig.voxyPlantOffset && !ShaderPatch.usable() ? " (shader anchors missing!)" : "",
            onOff(VoxyGate.groundPlants()), onOff(LslConfig.voxyDrawPlants), LslConfig.voxyPlantFaceInset, onOff(LslConfig.voxyTallPlants),
            onOff(LslConfig.voxyPlantShading), onOff(LslConfig.voxyPlantShadingWithShaders), LslConfig.voxyPlantBrightness, LslConfig.voxyPlantSaturation);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
