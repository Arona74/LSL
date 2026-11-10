package io.arona74.lsl.mixin.voxy;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The scene uniform buildDrawCalls fills (MVP, camera section), for the shadow pass (VoxyShadows). */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public interface MdicRendererAccessor {
    @Accessor("uniform")
    GlBuffer lsl$uniform();
}
