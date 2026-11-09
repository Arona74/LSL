package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.voxy.PlantLook;
import io.arona74.lsl.voxy.TintCapture;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Voxy's tint colours, as Roxy rewrites ModelFactory for 1.21.1: every colour Voxy keeps (the
 * constant one and the per-biome table) comes from {@code captureColourConstant}, which asks the
 * block's colour provider at block 0,0,0. Here it asks at a surface height, and a Polytone colormap
 * is sampled with Voxy's biome (TintCapture); then plant saturation is applied (PlantLook).
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelTintMixin {
    /** The real provider Roxy's proxy calls: Polytone colormaps wrapped so a capture samples them directly. */
    @WrapOperation(method = "getColourProvider", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/IdMapper;byId(I)Ljava/lang/Object;"))
    private static Object lsl$sampledProvider(IdMapper<?> mapper, int id, Operation<Object> original) {
        return TintCapture.wrap(original.call(mapper, id));
    }

    @WrapOperation(method = "captureColourConstant", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/color/block/BlockColor;getColor(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I"))
    private static int lsl$surfaceTint(BlockColor provider, BlockState state, BlockAndTintGetter getter, BlockPos pos, int tintIndex,
                                       Operation<Integer> original, @Local(argsOnly = true) Biome biome) {
        if (!LslConfig.voxyMatchTints) return original.call(provider, state, getter, pos, tintIndex);
        TintCapture.beginCapture(biome);
        try {
            return original.call(provider, state, getter, new BlockPos(0, TintCapture.HEIGHT, 0), tintIndex);
        } finally {
            TintCapture.endCapture();
        }
    }

    /**
     * Voxy tells biome-dependent colours apart by watching for a biome tint lookup; a Polytone
     * colormap without biome blending reads the biome another way, so it would get one colour for
     * every biome. Colormaps always count as biome dependent.
     */
    @ModifyReturnValue(method = "isBiomeDependentColour", at = @At("RETURN"))
    private static boolean lsl$colormapsVaryByBiome(boolean dependent, BlockColor provider, BlockState state) {
        if (dependent || !LslConfig.voxyMatchTints || state == null) return dependent;
        return TintCapture.isColormap(state);
    }

    @ModifyReturnValue(method = "captureColourConstant", at = @At("RETURN"))
    private static int lsl$plantSaturation(int colour, BlockColor provider, BlockState state, Biome biome) {
        return PlantLook.saturateTint(state, colour);
    }
}
