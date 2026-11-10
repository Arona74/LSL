package io.arona74.lsl.mixin.voxy;

import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section renderer's geometry and models, for the shadow pass (VoxyShadows). */
@Mixin(value = AbstractSectionRenderer.class, remap = false)
public interface SectionRendererAccessor {
    @Accessor("geometryManager")
    IGeometryData lsl$geometry();

    @Accessor("modelStore")
    ModelStore lsl$modelStore();
}
