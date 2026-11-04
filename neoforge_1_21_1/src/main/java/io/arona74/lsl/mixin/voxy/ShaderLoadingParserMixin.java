package io.arona74.lsl.mixin.voxy;

import io.arona74.lsl.voxy.ShaderPatch;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Patches Voxy's quad decoding as each shader file is read. See ShaderPatch. */
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public abstract class ShaderLoadingParserMixin {
    @Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true)
    private static void lsl$patch(ResourceLocation id, CallbackInfoReturnable<String> cir) {
        String patched = ShaderPatch.patch(id.getNamespace() + ":" + id.getPath(), cir.getReturnValue());
        if (patched != null) cir.setReturnValue(patched);
    }
}
