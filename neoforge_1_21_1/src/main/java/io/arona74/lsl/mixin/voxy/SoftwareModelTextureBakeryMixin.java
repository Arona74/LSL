package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.lsl.voxy.CtmAverage;
import io.arona74.lsl.voxy.CtmBake;
import io.arona74.lsl.voxy.ExtBlades;
import io.arona74.lsl.voxy.LeafLook;
import io.arona74.lsl.voxy.PlantLook;
import io.arona74.lsl.voxy.QuadSplit;
import io.arona74.lsl.voxy.TallPlants;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Voxy's model bakery, as Roxy rewrites it for 1.21.1 (it reads each side's quads with
 * {@code BakedModel.getQuads(state, side, random)}): grass ext blades left out of the bake
 * (ExtBlades), tall-plant slices moved down into their cell (TallPlants), quads bigger than a block
 * cut up (QuadSplit), then plant shading and brightness, and flat layer shading on the result
 * (PlantLook). Continuity models get the quads CtmBake drew through Fabric's renderer API instead
 * of their plain ones, and repeat patterns are averaged after the bake (CtmAverage.average).
 */
@Mixin(value = SoftwareModelTextureBakery.class, remap = false)
public abstract class SoftwareModelTextureBakeryMixin {
    @WrapOperation(method = "bakeBlockModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<BakedQuad> lsl$quads(BakedModel model, BlockState quadState, Direction direction, RandomSource random,
                                      Operation<List<BakedQuad>> original) {
        List<BakedQuad> quads = CtmAverage.handles(model) ? CtmBake.quads(model, quadState, direction) : null;
        if (quads == null) quads = original.call(model, quadState, direction, random);
        return QuadSplit.split(TallPlants.reshape(ExtBlades.filter(quads, direction, quadState), quadState));
    }

    @Inject(method = "renderToOutput", at = @At("HEAD"))
    private void lsl$freshBake(BlockState state, long outputBuffer, CallbackInfoReturnable<Integer> cir) {
        CtmBake.endBake();
    }

    @Inject(method = "renderToOutput", at = @At("RETURN"), cancellable = true)
    private void lsl$plantLook(BlockState state, long outputBuffer, CallbackInfoReturnable<Integer> cir) {
        CtmBake.endBake();
        CtmAverage.AverageRequest average = CtmAverage.takeAverageRequest();
        if (average != null) {
            SoftwareModelTextureBakery self = (SoftwareModelTextureBakery) (Object) this;
            CtmAverage.average((s, out) -> self.renderToOutput(s, out), state, outputBuffer, average);
        }
        LeafLook.apply(state, outputBuffer);
        int flags = cir.getReturnValue();
        int adjusted = PlantLook.applyLayer(state, PlantLook.apply(state, outputBuffer, flags));
        if (adjusted != flags) cir.setReturnValue(adjusted);
    }
}
