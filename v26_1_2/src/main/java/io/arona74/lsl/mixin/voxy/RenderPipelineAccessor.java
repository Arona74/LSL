package io.arona74.lsl.mixin.voxy;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The pipeline's section renderer, for the shadow pass (VoxyShadows). */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public interface RenderPipelineAccessor {
    @Accessor("sectionRenderer")
    AbstractSectionRenderer<?, ?> lsl$sectionRenderer();
}
