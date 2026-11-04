package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.lsl.voxy.CtmBake;
import io.arona74.lsl.voxy.ExtBlades;
import io.arona74.lsl.voxy.PlantLook;
import io.arona74.lsl.voxy.QuadSplit;
import io.arona74.lsl.voxy.TallPlants;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.util.RandomSource;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Voxy's model bakery: Continuity models drawn with their connected textures (CtmBake), grass ext blades left out of the bake (ExtBlades), tall-plant slices moved
 * down into their cell (TallPlants), quads bigger than a block cut up (QuadSplit), then plant shading and
 * brightness, and flat layer shading on the result (PlantLook).
 *
 * <p>Conquest Reforged's own Voxy mixin redirects two calls inside bakeBlockModel (the model lookup
 * and the leaves tag test); the quad lookup wrapped here is a different call, and the plant look
 * sits on the return of renderToOutput.
 */
@Mixin(value = SoftwareModelTextureBakery.class, remap = false)
public abstract class SoftwareModelTextureBakeryMixin {
    @WrapOperation(method = "bakeBlockModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;collectParts(Lnet/minecraft/util/RandomSource;Ljava/util/List;)V"))
    private void lsl$connectedTextures(BlockStateModel model, RandomSource random, List<BlockStateModelPart> out,
                                       Operation<Void> original, @Local(argsOnly = true) BlockState state) {
        if (CtmBake.handles(model)) CtmBake.collect(model, state, random, out);
        else original.call(model, random, out);
    }

    @WrapOperation(method = "bakeBlockModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;getQuads(Lnet/minecraft/core/Direction;)Ljava/util/List;"))
    private List<BakedQuad> lsl$dropExtBlades(BlockStateModelPart part, Direction direction,
                                              Operation<List<BakedQuad>> original,
                                              @Local(argsOnly = true) BlockState state) {
        return QuadSplit.split(TallPlants.reshape(ExtBlades.filter(original.call(part, direction), direction, state), state));
    }

    @Inject(method = "renderToOutput", at = @At("RETURN"), cancellable = true)
    private void lsl$plantLook(BlockState state, long outputBuffer, CallbackInfoReturnable<Integer> cir) {
        int[] average = CtmBake.takeAverageRequest();
        if (average != null) {
            SoftwareModelTextureBakery self = (SoftwareModelTextureBakery) (Object) this;
            CtmBake.average((s, out) -> self.renderToOutput(s, out), state, outputBuffer, average);
        }
        int flags = cir.getReturnValue();
        int adjusted = PlantLook.applyLayer(state, PlantLook.apply(state, outputBuffer, flags));
        if (adjusted != flags) cir.setReturnValue(adjusted);
    }
}
