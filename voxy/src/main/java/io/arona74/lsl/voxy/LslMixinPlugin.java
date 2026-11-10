package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the Voxy mixins only on a supported Voxy build, and only those the config enables. */
public final class LslMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!VoxyGate.active()) return false;
        if (mixinClassName.endsWith(".MipperMixin")) return LslConfig.voxyLayerMip;
        if (mixinClassName.endsWith(".ModelFactoryMixin")) return LslConfig.voxyShapeCulling;
        if (mixinClassName.endsWith(".ModelStoreMixin")) return LslConfig.voxyFullMipmaps;
        // Always applied: ext blades, plant shading and brightness are read at bake time and have a no-op value.
        if (mixinClassName.endsWith(".ShaderLoadingParserMixin")) return LslConfig.voxyPlantOffset || LslConfig.voxyFlatLayers;
        if (mixinClassName.endsWith(".SodiumShadowMixin")) return LslConfig.voxyShadows;
        if (mixinClassName.endsWith(".RenderDataFactoryMixin")) return LslConfig.voxyPlantOffset || LslConfig.voxyTallPlants;
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
