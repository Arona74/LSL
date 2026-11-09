package io.arona74.lsl.mixin.voxy;

import io.arona74.lsl.voxy.LayerMip;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every LOD level is built by Mipper.mip; layer cells get a layer of the right height. See LayerMip. */
@Mixin(value = Mipper.class, remap = false)
public abstract class MipperMixin {
    @Inject(method = "mip", at = @At("HEAD"), cancellable = true)
    private static void lsl$layerMip(long i000, long i100, long i001, long i101,
                                     long i010, long i110, long i011, long i111,
                                     Mapper mapper, CallbackInfoReturnable<Long> cir) {
        long result = LayerMip.mip(i000, i100, i001, i101, i010, i110, i011, i111, mapper);
        if (result != LayerMip.PASS) cir.setReturnValue(result);
    }
}
