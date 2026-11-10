package io.arona74.lsl.mixin.voxy;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Voxy's render pipeline and LOD traversal, for the shadow pass (VoxyShadows). */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public interface VoxyRenderSystemAccessor {
    @Accessor("pipeline")
    AbstractRenderPipeline lsl$pipeline();

    @Accessor("traversal")
    HierarchicalOcclusionTraverser lsl$traversal();
}
