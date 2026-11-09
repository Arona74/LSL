package io.arona74.lsl.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.lsl.voxy.BlockTables;
import io.arona74.lsl.voxy.FaceOcclusion;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Corrects a baked model's face occlusion just before Voxy caches it (the method's one read of
 * metadataCache is that store, after every face and the fully-opaque bit are in). See FaceOcclusion.
 *
 * <p>{@code blockState} is what Voxy baked, which for a stair is its base block (see addEntry); the
 * mapper gives back the stair itself.
 *
 * <p>A stand-in (a stair baked as its base block) bakes to exactly the pictures of that block, so
 * Voxy's model deduplication would hand it the block's own model, culling included, and return
 * before the metadata is ever built. Its dedup key is wrapped ({@link FaceOcclusion#dedupKey}) so
 * stand-ins share models among themselves but never with the full block. The map only ever sees
 * getInt / put / size, so a foreign key type is safe.
 *
 * <p>Conquest Reforged's own Voxy mixins redirect calls in processModelResult and bakeBlockModel;
 * this touches neither.
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelFactoryMixin {
    @Shadow @Final private Mapper mapper;

    @ModifyVariable(
        method = "processTextureBakeResult",
        at = @At(value = "FIELD", target = "Lme/cortex/voxy/client/core/model/ModelFactory;metadataCache:[J", ordinal = 0),
        name = "metadata"
    )
    private long lsl$shapeAwareOcclusion(long metadata, @Local(argsOnly = true) int blockId,
                                         @Local(argsOnly = true) BlockState blockState,
                                         @Local(name = "depths") float[] depths) {
        return FaceOcclusion.correct(metadata, blockState, lsl$realState(blockId, blockState), depths);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "processTextureBakeResult", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;getInt(Ljava/lang/Object;)I"))
    private int lsl$standInLookup(Object2IntOpenHashMap map, Object entry, Operation<Integer> original,
                                  @Local(argsOnly = true) int blockId, @Local(argsOnly = true) BlockState blockState) {
        return original.call(map, FaceOcclusion.dedupKey(entry, blockState, lsl$realState(blockId, blockState)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "processTextureBakeResult", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;put(Ljava/lang/Object;I)I"))
    private int lsl$standInStore(Object2IntOpenHashMap map, Object entry, int modelId, Operation<Integer> original,
                                 @Local(argsOnly = true) int blockId, @Local(argsOnly = true) BlockState blockState) {
        return original.call(map, FaceOcclusion.dedupKey(entry, blockState, lsl$realState(blockId, blockState)), modelId);
    }

    private BlockState lsl$realState(int blockId, BlockState baked) {
        try {
            if (BlockTables.of(this.mapper).isSlice(blockId)) return baked; // a tall-plant slice
            BlockState real = this.mapper.getBlockStateFromBlockId(blockId);
            return real != null ? real : baked;
        } catch (Throwable t) {
            return baked;
        }
    }
}
