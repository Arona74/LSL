package io.arona74.lsl.mixin.voxy;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import io.arona74.lsl.voxy.VoxyShadows;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * voxy_shadows: when Iris draws its shadow map, Sodium draws the vanilla chunks with the shadow
 * camera's matrices; at the start of its cut-out pass the shadow framebuffer is bound and Sodium
 * has not set up its own state yet. VoxyShadows draws the Voxy LODs into it there. Only applied
 * with the option on (LslMixinPlugin).
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class SodiumShadowMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void lsl$voxyShadows(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                                 TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters,
                                 boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformBuffer,
                                 GlTexelBuffer sectionTimeInfoTexture, CallbackInfo ci) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT && IrisUtil.irisShadowActive()) {
            VoxyShadows.render(matrices.projection(), matrices.modelView(), camera.x, camera.y, camera.z, parameters);
        }
    }
}
