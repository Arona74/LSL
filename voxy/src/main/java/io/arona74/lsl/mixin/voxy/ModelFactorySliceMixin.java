package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.lsl.voxy.BlockTables;
import io.arona74.lsl.voxy.TallPlants;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Lets Voxy bake tall-plant slices (TallPlants): a slice id is not the mapper's, so its state comes
 * from LSL's table, and while its bake runs the bakery is told which slice to move down.
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelFactorySliceMixin {
    @Shadow @Final private Mapper mapper;

    @WrapOperation(method = "addEntry", at = @At(value = "INVOKE",
        target = "Lme/cortex/voxy/common/world/other/Mapper;getBlockStateFromBlockId(I)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lsl$sliceState(Mapper mapper, int blockId, Operation<BlockState> original) {
        BlockTables tables = BlockTables.of(mapper);
        if (tables.isSlice(blockId)) return tables.sliceState(blockId);
        return original.call(mapper, blockId);
    }

    @WrapOperation(method = "processModelResult", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/ConcurrentLinkedDeque;poll()Ljava/lang/Object;"))
    private Object lsl$sliceBake(ConcurrentLinkedDeque<?> queue, Operation<Object> original) {
        Object bake = original.call(queue);
        int slice = 0;
        if (bake instanceof BlockBakeAccessor b) slice = BlockTables.of(this.mapper).sliceLevel(b.lsl$blockId());
        TallPlants.setBaking(slice);
        return bake;
    }
}
