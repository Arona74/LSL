package io.arona74.lsl.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Voxy's queued bake (a private record): which block id it is for. */
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory$BlockBake", remap = false)
public interface BlockBakeAccessor {
    @Accessor("blockId")
    int lsl$blockId();
}
