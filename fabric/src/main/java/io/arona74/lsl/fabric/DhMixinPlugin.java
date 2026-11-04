package io.arona74.lsl.fabric;

import io.arona74.lsl.LslConfig;
import io.arona74.lsl.dh.DhExactFlags;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Gates the Distant Horizons mixins (exact layer heights, see DhLayerMesh).
 *
 * <p>They reach into DH internals that change between releases, so they apply only to DH versions
 * whose internals were checked against them, and only when dh_exact_layers is on. Anything else
 * leaves DH untouched and the public-API rounding in DistantHorizonsCompat takes over.
 *
 * <p>This runs while mixins load, before Minecraft's blocks exist: it reads LslConfig (Gson only)
 * and inspects class bytes, nothing that would load a game class.
 */
public final class DhMixinPlugin implements IMixinConfigPlugin {

    /** DH releases whose LodDataBuilder, ColumnBox, LodQuadBuilder, ColumnRenderSource, ... match. */
    private static final Set<String> SUPPORTED = Set.of("3.3.2", "3.3.3", "3.3.4");

    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        io.arona74.lsl.fabric.LslFabricConfig.load();
        enabled = FabricLoader.getInstance().isModLoaded("distanthorizons") && !GenDh.present()
            && LslConfig.dhExactLayers && supportedVersion();
        DhExactFlags.active = enabled;
        DhExactFlags.colorBits = enabled && LslConfig.dhExactLayersColorBits;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!enabled) return false;
        // The height decoding only exists for the colour-bit encoding.
        if (mixinClassName.endsWith("DecodeMixin") && !DhExactFlags.colorBits) return false;
        try {
            // Optional targets (Iris) are skipped when their mod is absent.
            return MixinService.getService().getBytecodeProvider().getClassNode(targetClassName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean supportedVersion() {
        try {
            ClassNode info = MixinService.getService().getBytecodeProvider()
                .getClassNode("com.seibel.distanthorizons.coreapi.ModInfo");
            for (FieldNode field : info.fields) {
                if (field.name.equals("VERSION") && field.value instanceof String version) {
                    return SUPPORTED.contains(version);
                }
            }
        } catch (Exception ignored) {
            // No DH, or a layout we do not know: leave DH alone.
        }
        return false;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
