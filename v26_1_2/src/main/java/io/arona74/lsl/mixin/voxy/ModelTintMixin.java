package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.voxy.PlantLook;
import io.arona74.lsl.voxy.TintCapture;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Voxy's tint colours. Every tint Voxy uses (the constant one, the per-biome table for new models
 * and the one rebuilt when biomes are added) is captured by this one method: each source is asked
 * at a surface height and Polytone colormaps get Voxy's biome (TintCapture), then plant saturation
 * is applied to the result (PlantLook).
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelTintMixin {
    @WrapOperation(method = "captureColourConstant", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/color/block/BlockTintSource;colorInWorld(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private static int lsl$surfaceTint(BlockTintSource source, BlockState state, BlockAndTintGetter getter, BlockPos pos,
                                       Operation<Integer> original, @Local(argsOnly = true) Biome biome) {
        if (!LslConfig.voxyMatchTints) return original.call(source, state, getter, pos);
        return TintCapture.colour(source, state, getter, biome);
    }

    /**
     * Voxy tells biome-dependent colours apart by watching for a biome tint lookup; a Polytone
     * colormap without biome blending reads the biome another way, so it would get one colour for
     * every biome. Colormaps always count as biome dependent.
     */
    @ModifyReturnValue(method = "isBiomeDependentColour", at = @At("RETURN"))
    private static boolean lsl$colormapsVaryByBiome(boolean dependent, List<BlockTintSource> tintSources, BlockState state) {
        if (dependent || !LslConfig.voxyMatchTints || tintSources == null) return dependent;
        for (BlockTintSource source : tintSources) {
            if (source != null && TintCapture.isColormap(source)) return true;
        }
        return false;
    }

    @ModifyReturnValue(method = "captureColourConstant", at = @At("RETURN"))
    private static int lsl$plantSaturation(int colour, List<BlockTintSource> tintSources, BlockState state, Biome biome) {
        return PlantLook.saturateTint(state, colour);
    }
}
