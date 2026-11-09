package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.model.ModelStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets Voxy use every mipmap level of its model atlas.
 *
 * <p>Voxy caps its atlas sampler at the game's own Mipmap Levels setting, so with mipmaps lowered
 * or off, distant LOD faces sample full-size textures and repeating terrain (layers, grass) turns
 * into moire. Voxy bakes the levels anyway (16, 8, 4 and 2 pixels per face), so this only lifts the
 * cap; the game's own terrain is untouched. Option {@code voxy_full_mipmaps}.
 */
@Mixin(value = ModelStore.class, remap = false)
public abstract class ModelStoreMixin {
    /** GL_TEXTURE_MAX_LOD. */
    private static final int LSL$MAX_LOD = 0x813B;
    /** Highest level Voxy uploads (ModelFactory.LAYERS - 1 for 16-pixel faces). */
    private static final int LSL$TOP_LEVEL = 3;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/opengl/GL33C;glSamplerParameteri(III)V"))
    private void lsl$fullMipmaps(int sampler, int pname, int param, Operation<Void> original) {
        if (pname == LSL$MAX_LOD && param < LSL$TOP_LEVEL) param = LSL$TOP_LEVEL;
        original.call(sampler, pname, param);
    }
}
