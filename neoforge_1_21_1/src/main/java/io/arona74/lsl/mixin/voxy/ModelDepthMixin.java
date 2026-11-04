package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.lsl.voxy.PlantLook;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Where Voxy draws each face picture of a plant (PlantLook.insetFaces), right after it measured
 * them, and LSL's flat bit in the model flags of layers (PlantLook.flatLayerFlag).
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelDepthMixin {
    @ModifyVariable(method = "processTextureBakeResult", at = @At("STORE"), name = "depths")
    private float[] lsl$plantFaceInset(float[] depths, @Local(argsOnly = true) BlockState blockState) {
        return PlantLook.insetFaces(depths, blockState);
    }

    /** Every store of the model's flags gets LSL's flat bit (idempotent), so the uploaded value has it. */
    @ModifyVariable(method = "processTextureBakeResult", at = @At("STORE"), name = "modelFlags")
    private int lsl$flatLayerFlag(int modelFlags, @Local(argsOnly = true) BlockState blockState) {
        return PlantLook.flatLayerFlag(modelFlags, blockState);
    }
}
