package io.arona74.lsl.mixin.voxy;

import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The mesher's own packing helpers, so tall-plant slices are packed exactly like Voxy's blocks. */
@Mixin(value = RenderDataFactory.class, remap = false)
public interface RenderDataFactoryInvoker {
    @Invoker("packPartialQuadData")
    static long lsl$pack(int modelId, long state, long metadata) {
        throw new AssertionError();
    }

    @Invoker("getNeighborMsk")
    static int lsl$neighborMsk(long notEmpty, int i) {
        throw new AssertionError();
    }
}
